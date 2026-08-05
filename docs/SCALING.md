# Scaling the audit-log starter

This document covers what changes once `audit_log` stops being a small table: read pagination,
write-side batching (and why it's more limited than you might expect), scheduled retention, and
table partitioning. See `MIGRATION.md` for the 1.x -> 2.x API mapping and the main `README.md` for
everyday usage - this document is specifically about high-volume operation.

## Reads: offset pagination vs. keyset pagination

`AuditLogQueryService.find(query, pageable)` is standard offset pagination (`OFFSET`/`LIMIT` under
the hood) - simple, and the right choice for "page 1, 2, 3..." UI navigation over a filtered result
set that a person is actually going to page through by hand. Its cost is not constant, though:
fetching page 500 means the database still has to walk and discard the 499 pages before it, so cost
grows with how deep into the result set you ask for, not just with the page size.

Two things bound the damage in the meantime:

- **`audit.log.query.max-page-size`** (default `200`) rejects an oversized `Pageable` outright
  (`IllegalArgumentException`), rather than silently clamping it - a single caller-supplied page
  size can't turn into an unbounded scan.
- **`pageable.getSort()`** is honored, but restricted to a whitelist of indexed/filterable
  properties (`id`, `createdAt`, `actorId`, `auditType` - see `AuditLog`'s `@Table(indexes = ...)`),
  so every accepted sort stays index-backed instead of forcing a full sort of the filtered rows.

Once the offset itself is the bottleneck - a batch export, a background job walking the entire
table, anything that isn't a human clicking "next page" - switch to
**`AuditLogQueryService.findAfter(query, cursor, limit)`**: keyset ("seek") pagination. Cost is
independent of how deep `cursor` is; there is no offset to skip, because each call filters directly
on `(created_at, id) < (cursor.created_at, cursor.id)` and takes the next `limit` rows in
`created_at desc, id desc` order (the `id` tie-breaker matters - two rows can share a timestamp, and
without it, rows on the cursor boundary could be skipped or repeated across pages). Start with
`cursor = null` for the first page, then pass `new AuditCursor(lastRecord.createdAt(), lastRecord.id())`
of the last row of each page back in for the next one; a page shorter than `limit` means you've
reached the end.

## Writes: why batching doesn't apply here

`AuditLog` and `AuditLogMessage` both use `@GeneratedValue(strategy = GenerationType.IDENTITY)`
(auto-increment / `bigserial`-style columns). This is a real Hibernate limitation, not a missed
configuration flag: **Hibernate cannot JDBC-batch IDENTITY-generated inserts.** Because the
database assigns the id at insert time and Hibernate needs that id back immediately (to persist
dependent rows, populate the entity, etc.), each `IDENTITY` insert has to be its own round trip -
setting `hibernate.jdbc.batch_size` has no effect on these two tables no matter how high it's set.

The fix that actually works is switching to `GenerationType.SEQUENCE` (a database sequence,
pre-fetched in batches by Hibernate), which *is* batchable. This starter deliberately does not ship
that as a flip-a-property option:

- `@GeneratedValue`'s strategy is effectively fixed at entity-mapping time. Making it truly
  runtime-configurable would mean either shipping a second, parallel entity mapping (real
  complexity for a single config toggle) or a custom identifier generator that switches behavior at
  runtime underneath the same annotation - which is fragile precisely where a compliance artifact's
  data model most needs to *not* be fragile.
- The switch is schema-affecting regardless: existing `IDENTITY` columns and a `SEQUENCE`-based
  mapping are not interchangeable without a real migration. A property that silently "just works"
  either lies about that or races the actual schema change.

If your write volume genuinely needs batched inserts, fork/extend the entities to use `SEQUENCE`
and run a migration like the following (PostgreSQL; adjust for your dialect) before switching the
mapping:

```sql
-- One sequence per table, matching Hibernate's default SequenceStyleGenerator allocation size.
create sequence audit_log_seq start with 1 increment by 50;
create sequence audit_log_message_seq start with 1 increment by 50;

-- Reseed each sequence past the current max id so newly-generated ids don't collide with
-- existing IDENTITY-assigned ones.
select setval('audit_log_seq', (select coalesce(max(id), 0) + 1 from audit_log), false);
select setval('audit_log_message_seq', (select coalesce(max(id), 0) + 1 from audit_log_message), false);

alter table audit_log alter column id drop default;
alter table audit_log_message alter column id drop default;
```

...then change `AuditLog`/`AuditLogMessage`'s `@GeneratedValue` to
`strategy = GenerationType.SEQUENCE, generator = "..."` with a matching `@SequenceGenerator`, and
set `hibernate.jdbc.batch_size` (the demo app's `application.properties` already sets one, unused
today because of the `IDENTITY` limitation above). Do this as a fork or a local patch, not by
depending on internal starter classes.

## Retention: scheduled deletion of old records

`AuditLogRetentionService` deletes `AuditLog` (and their child `AuditLogMessage`) rows older than a
configured age - **off by default**, since deleting audit history is a decision this starter must
never make for a consuming application unasked. Enable it explicitly:

```properties
audit.log.retention.enabled=true
audit.log.retention.max-age=P90D
audit.log.retention.cron=0 0 3 * * *
audit.log.retention.batch-size=1000
```

It runs on its own dedicated `ThreadPoolTaskScheduler` - not `@Scheduled`/`@EnableScheduling` -
for the same reason `AuditLogTaskExecutor` doesn't use `@EnableAsync`/`@Async`: turning on
scheduling support context-wide is a consumer-visible behavior change this starter should not
impose just because retention is turned on. Deletion happens in bounded batches (`batch-size` rows
per iteration, oldest first), not one unbounded `DELETE`, so clearing a large backlog doesn't hold
a long lock on `audit_log`.

## Partitioning: for tables retention alone won't keep small enough

Retention bounds how much history accumulates, but a table doing thousands of inserts a day still
benefits from native time-based partitioning once it's large - row-by-row `DELETE`s (even batched)
are far more expensive than dropping a whole partition, and query planners can skip partitions
outside a `created_at` filter's range entirely.

**PostgreSQL** (declarative partitioning, native since PG 10): partition `audit_log` by `RANGE
(created_at)`, one partition per month or week depending on volume:

```sql
create table audit_log (
    -- same columns as db/migration/V2__audit_log_v2.sql
    ...
) partition by range (created_at);

create table audit_log_2026_01 partition of audit_log
    for values from ('2026-01-01') to ('2026-02-01');
create table audit_log_2026_02 partition of audit_log
    for values from ('2026-02-01') to ('2026-03-01');
-- ...create new partitions ahead of time (e.g. via a scheduled job or pg_partman), and drop old
-- ones directly instead of running the retention job's row-by-row DELETE on partitioned data:
drop table audit_log_2025_10;
```

`audit_log_message` should follow the same partitioning key so a dropped `audit_log` partition and
its child messages stay aligned - since it has no `created_at` of its own, either denormalize one
onto it (a copy of the parent's `created_at`, kept in sync at insert time) or partition it by
`audit_log_id` range instead and accept that message cleanup trails log cleanup slightly.

**MySQL** (8.0+): the equivalent is `PARTITION BY RANGE (TO_DAYS(created_at))`, with
`ALTER TABLE audit_log DROP PARTITION ...` in place of `DROP TABLE`.

Once partitioned, point `AuditLogRetentionService` at dropping whole old partitions instead of (or
in addition to) its row-by-row batched delete - it doesn't do this automatically today, since
partition management is a deployment-specific decision (partition naming scheme, how partitions get
created ahead of time) this starter can't safely automate blindly.
