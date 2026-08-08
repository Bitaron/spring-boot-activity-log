package io.github.bitaron.auditlog.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP17 acceptance: {@link AuditLogGenericDataGetter}'s methods are all {@code default}, each
 * returning the fallback documented on it - an implementation overriding only one method still
 * compiles and gets sensible values for the rest.
 */
class AuditLogGenericDataGetterTest {

    @Test
    void unoverriddenMethodsReturnTheirDocumentedDefaults() {
        AuditLogGenericDataGetter getter = new AuditLogGenericDataGetter() {
            @Override
            public String getActorId() {
                return "actor-42";
            }
        };

        assertThat(getter.getActorId()).isEqualTo("actor-42");
        assertThat(getter.getActorName()).isEqualTo("");
        assertThat(getter.getClientLocation()).isEqualTo("");
        assertThat(getter.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(getter.getUserAgent()).isEqualTo("");
    }

    @Test
    void fullyDefaultedImplementationCompilesAndReturnsAllDefaults() {
        AuditLogGenericDataGetter getter = new AuditLogGenericDataGetter() {
        };

        assertThat(getter.getActorId()).isEqualTo("SYSTEM");
        assertThat(getter.getActorName()).isEqualTo("");
        assertThat(getter.getClientLocation()).isEqualTo("");
        assertThat(getter.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(getter.getUserAgent()).isEqualTo("");
    }
}
