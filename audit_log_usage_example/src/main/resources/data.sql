-- Seeds the template the demo controller's @Audit(templateNameList = {"test_template"}) references.
-- Without this row, @Audit would still fire but produce no audit_log row (see AuditLogWriter):
-- a template name that doesn't resolve to an existing audit_template row is skipped with a warning.
--
-- Used by both /test (success) and /test/fail (exception): AuditLogClientData only populates
-- "response" on success and "exception" on failure, so both references are wrapped in parens -
-- FreeMarker's default operator only suppresses a null/missing error for the whole parenthesized
-- expression, not a bare chained reference, so unparenthesized response.body.value would still
-- throw when response itself is null on the failure path.
insert into audit_template (name, template) values
    ('test_template', 'Actor ${actorName!"unknown"} (${actorId!"n/a"}) called the test endpoint<#if exceptionThrown> and it failed: ${(exception.message)!"unknown error"}<#else> and got value ${(response.body.value)!"n/a"}</#if>');
