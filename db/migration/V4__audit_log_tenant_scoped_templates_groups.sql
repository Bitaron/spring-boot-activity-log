-- Adds tenant scoping to audit_template/audit_group (WP16). Written for PostgreSQL, like
-- V2__audit_log_v2.sql and V3__audit_log_multi_tenancy.sql; adjust types/syntax for your own
-- dialect if different.
--
-- Unlike V3's audit_log.tenant_id (nullable, "null = default tenant"), this column is NOT NULL
-- with a '' (empty string) default for "not tenant-specific" - both tables have a real composite
-- unique constraint on (tenant_id, name), and standard SQL treats every NULL as distinct for
-- uniqueness purposes, so a NULL-based convention here would silently allow duplicate global
-- template/group names. '' participates in the unique constraint like any other value. See
-- AuditTemplate/AuditGroup's GLOBAL_TENANT_ID javadoc.
alter table audit_template add column tenant_id varchar(255) not null default '';
alter table audit_template drop constraint uk_audit_template_name;
alter table audit_template add constraint uk_audit_template_tenant_name unique (tenant_id, name);

alter table audit_group add column tenant_id varchar(255) not null default '';
alter table audit_group drop constraint uk_audit_group_name;
alter table audit_group add constraint uk_audit_group_tenant_name unique (tenant_id, name);

-- No index added beyond what the unique constraints above already provide (both are
-- looked up by the exact (tenant_id, name) pair - see DatabaseAuditTemplateSource and
-- AuditLogWriter.resolveGroupId - which the unique constraint's backing index already serves).
