-- Seeds the template the demo controller's @Audit(templates = {"test_template"}) references.
-- Without this row, @Audit would still fire but produce no audit_log row (see AuditLogWriter):
-- a template name that doesn't resolve to an existing audit_template row is skipped with a warning.
--
-- Used by both /test (success) and /test/fail (exception): AuditContext only populates
-- "result" on success and "exception" on failure, so both references are wrapped in parens -
-- FreeMarker's default operator only suppresses a null/missing error for the whole parenthesized
-- expression, not a bare chained reference, so unparenthesized result.body.value would still
-- throw when result itself is null on the failure path.
-- tenant_id = '' (AuditTemplate.GLOBAL_TENANT_ID): this demo has multi-tenancy disabled, so every
-- template resolves through the global/tenant-agnostic layer - see DatabaseAuditTemplateSource.
insert into audit_template (name, template, tenant_id) values
    ('test_template', 'Actor ${actorName!"unknown"} (${actorId!"n/a"}) called the test endpoint<#if exceptionThrown> and it failed: ${(exception.message)!"unknown error"}<#else> and got value ${(result.body.value)!"n/a"}</#if>', '');
