-- Adds opt-in multi-tenancy support (audit.log.multi-tenancy.enabled) - see AGENTS.md and the
-- autoconfigure module's README "Multi-tenancy" section. Written for PostgreSQL, like
-- V2__audit_log_v2.sql; adjust types/syntax for your own dialect if different.
--
-- Nullable, no backfill: existing single-tenant deployments upgrade with zero behavior change.
-- tenant_id stays null on every row and no read is ever tenant-filtered unless
-- audit.log.multi-tenancy.enabled is explicitly turned on (see JpaAuditLogQueryService) - "null"
-- is this starter's "no tenant"/"single-tenant deployment" convention, same as trace_id,
-- client_location, and group_id are all already nullable "unset means N/A" columns.
alter table audit_log
    add column tenant_id varchar(255);

-- Tenant-first composite: once enabled, the hot-path read is "this tenant's records, newest
-- first" - a single-column tenant_id index alone would still force a secondary sort on
-- created_at once a tenant accumulates many rows.
create index idx_audit_log_tenant_created_at on audit_log (tenant_id, created_at);

-- audit_log_message intentionally gains no tenant_id: it's always looked up via audit_log_id from
-- an already tenant-scoped AuditLog row (JpaAuditLogQueryService never queries it independently),
-- so duplicating tenant onto the child table would be pure redundant denormalization.
--
-- audit_template / audit_group intentionally remain global (not tenant-scoped): templates are
-- code-like/versioned with the application, not tenant data, and the sensitive rows under a group
-- are already tenant-tagged via audit_log.tenant_id.
