package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.model.AuditContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreemarkerTemplateResolverTest {

    private final FreemarkerTemplateResolver resolver = new FreemarkerTemplateResolver(256);

    @Test
    void resolvesPlaceholdersAgainstClientData() {
        AuditContext context = contextWithActor("Ada");
        String message = resolver.resolveTemplate("greeting", "Hello ${actorName}!", context);
        assertThat(message).isEqualTo("Hello Ada!");
    }

    @Test
    void repeatedResolutionUsesTheCompiledTemplateCache() {
        AuditContext context = contextWithActor("Ada");
        String first = resolver.resolveTemplate("greeting", "Hello ${actorName}!", context);
        String second = resolver.resolveTemplate("greeting", "Hello ${actorName}!", context);
        assertThat(first).isEqualTo(second).isEqualTo("Hello Ada!");
    }

    @Test
    void malformedTemplateThrowsRatherThanSilentlyFailing() {
        AuditContext context = contextWithActor("Ada");
        assertThatThrownBy(() -> resolver.resolveTemplate("broken", "${nope.", context))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void reflectiveApiBuiltinIsDisabled() {
        AuditContext context = contextWithActor("Ada");
        assertThatThrownBy(() -> resolver.resolveTemplate("escape", "${actorName?api}", context))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void compiledTemplateCacheIsBoundedNotUnbounded() {
        FreemarkerTemplateResolver bounded = new FreemarkerTemplateResolver(5);
        AuditContext context = contextWithActor("Ada");

        for (int i = 0; i < 50; i++) {
            bounded.resolveTemplate("template-" + i, "Hello ${actorName} #" + i, context);
        }

        assertThat(bounded.cacheSize()).isLessThanOrEqualTo(5);
    }

    private AuditContext contextWithActor(String actorName) {
        return new AuditContext(null, actorName, null, null, null, null, null, null, false, 0, null, null);
    }
}
