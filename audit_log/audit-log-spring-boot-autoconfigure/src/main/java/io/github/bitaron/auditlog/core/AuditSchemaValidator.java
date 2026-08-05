package io.github.bitaron.auditlog.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fails application startup with an actionable error if any of this starter's required tables are
 * missing from the configured database, instead of the previous behavior of no check at all - the
 * first sign of a missing table would otherwise be a runtime {@code SQLException} on the first
 * audited call, buried in a warning log inside {@link AuditLogger}'s failure-isolation try/catch,
 * easy to miss until an audit is silently never recorded.
 * <p>
 * Runs as a {@link SmartInitializingSingleton}, i.e. after every singleton bean in the context -
 * including the {@code EntityManagerFactory} - has already been created. That ordering matters:
 * Hibernate's own {@code ddl-auto=create}/{@code update} schema generation happens as a side
 * effect of creating the {@code EntityManagerFactory} bean itself, so by the time this check runs,
 * a {@code ddl-auto} user's tables already exist and this check correctly passes without racing
 * that generation.
 * <p>
 * Enabled by default; set {@code audit.log.schema-validation.enabled=false} to skip it entirely -
 * e.g. for a deployment that already validates its schema some other way (a migration tool's own
 * pre-flight check, a separate readiness probe) and doesn't want the extra startup queries.
 */
@Slf4j
public class AuditSchemaValidator implements SmartInitializingSingleton {

    /**
     * This starter's own tables - see {@code db/migration/V2__audit_log_v2.sql} and
     * {@code MIGRATION.md}. Hardcoded rather than derived reflectively from the {@code @Table}
     * annotations on {@code AuditLog}/{@code AuditLogMessage}/{@code AuditTemplate}/
     * {@code AuditGroup}: these four names are a stable, already-documented contract, and
     * hardcoding keeps this check plain JDBC rather than pulling in JPA entity metadata for it.
     */
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "audit_log", "audit_log_message", "audit_template", "audit_group");

    private final DataSource dataSource;

    public AuditSchemaValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> missing = new LinkedHashSet<>();
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(table)) {
                missing.add(table);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "audit-log: the following required table(s) are missing from the configured "
                            + "database: " + missing + ". Apply db/migration/V2__audit_log_v2.sql "
                            + "(or an equivalent Flyway/Liquibase migration), or let Hibernate create "
                            + "them by setting spring.jpa.hibernate.ddl-auto=create or update. Set "
                            + "audit.log.schema-validation.enabled=false to skip this check if the "
                            + "schema is managed and validated another way.");
        }
    }

    /**
     * Probes with a fresh JDBC {@link Connection} per table rather than through JPA: a
     * {@code PersistenceException} from a missing-table query would leave that
     * {@code EntityManager}'s transaction unusable for every table checked afterward, turning a
     * four-table check into per-table transaction bookkeeping. Raw JDBC sidesteps that entirely
     * and needs no entity-manager lifecycle handling. {@code WHERE 1 = 0} keeps the probe itself
     * cheap and dialect-portable - it never actually scans the table.
     */
    private boolean tableExists(String table) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("select 1 from " + table + " where 1 = 0");
            return true;
        } catch (SQLException e) {
            log.debug("audit-log schema check: table '{}' is not queryable", table, e);
            return false;
        }
    }
}
