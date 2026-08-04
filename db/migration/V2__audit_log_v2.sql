-- Migrates the audit-log starter's schema from 1.x to 2.x. Written for PostgreSQL (the demo
-- app's non-default profile); adjust types/syntax for your own dialect if different.
--
-- Run this against a database that still has the 1.x schema:
--   audit_log(id, audit_type, actor_id, actor_name, client_ip, client_location, user_agent,
--             action_type, action_name, created_at, data, template_id, message, group_id)
--   audit_template(id, name, template)
--   audit_group(id, name)
--
-- See MIGRATION.md for the full 1.x -> 2.x API/behavior mapping this schema change corresponds to.

-- 1. One row per rendered template moves from a duplicated audit_log row to a child
--    audit_log_message row (see MIGRATION.md, "One row per event"). Create the table and
--    backfill it from the 1.x columns before dropping them, so no rendered message is lost.
create table audit_log_message (
    id            bigserial primary key,
    audit_log_id  bigint       not null references audit_log (id),
    template_name varchar(255),
    message       varchar(4000)
);

create index idx_audit_log_message_audit_log_id on audit_log_message (audit_log_id);

insert into audit_log_message (audit_log_id, template_name, message)
select al.id, at.name, al.message
from audit_log al
         left join audit_template at on at.id = al.template_id
where al.message is not null
   or al.template_id is not null;

-- 2. Drop the now-migrated 1.x columns and add the 2.x ones. outcome cannot be reconstructed
--    for pre-existing rows - the 1.x schema never recorded it - so it's backfilled to 'SUCCESS'
--    as the least-wrong default (most audit rows describe successful operations); duration_ms
--    and trace_id have no 1.x equivalent at all and are simply left null for old rows.
alter table audit_log
    drop column template_id,
    drop column message,
    add column outcome     varchar(16),
    add column duration_ms bigint,
    add column trace_id    varchar(255);

update audit_log
set outcome = 'SUCCESS'
where outcome is null;

-- 3. audit_group.name gains a unique constraint in 2.x (AuditLogWriter now reuses an existing
--    group by name instead of inserting a new row per invocation - see MIGRATION.md). Dedupe
--    existing rows first: repoint every audit_log.group_id to the lowest-id row per name, then
--    delete the now-unreferenced duplicates.
update audit_log al
set group_id = keep.id
from audit_group dup
         join (select min(id) as id, name from audit_group group by name) keep
              on keep.name = dup.name
where al.group_id = dup.id
  and dup.id <> keep.id;

delete
from audit_group dup
    using (select min(id) as id, name from audit_group group by name) keep
where dup.name = keep.name
  and dup.id <> keep.id;

alter table audit_group
    add constraint uk_audit_group_name unique (name);
