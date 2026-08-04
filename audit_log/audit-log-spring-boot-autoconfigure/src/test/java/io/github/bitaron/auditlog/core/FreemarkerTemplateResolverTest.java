package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.dto.AuditLogClientData;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreemarkerTemplateResolverTest {

    private final FreemarkerTemplateResolver resolver = new FreemarkerTemplateResolver(256);

    @Test
    void resolvesPlaceholdersAgainstClientData() {
        AuditLogClientData data = clientDataWithActor("Ada");
        String message = resolver.resolveTemplate("greeting", "Hello ${actorName}!", data);
        assertThat(message).isEqualTo("Hello Ada!");
    }

    @Test
    void repeatedResolutionUsesTheCompiledTemplateCache() {
        AuditLogClientData data = clientDataWithActor("Ada");
        String first = resolver.resolveTemplate("greeting", "Hello ${actorName}!", data);
        String second = resolver.resolveTemplate("greeting", "Hello ${actorName}!", data);
        assertThat(first).isEqualTo(second).isEqualTo("Hello Ada!");
    }

    @Test
    void malformedTemplateThrowsRatherThanSilentlyFailing() {
        AuditLogClientData data = clientDataWithActor("Ada");
        assertThatThrownBy(() -> resolver.resolveTemplate("broken", "${nope.", data))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void reflectiveApiBuiltinIsDisabled() {
        AuditLogClientData data = clientDataWithActor("Ada");
        assertThatThrownBy(() -> resolver.resolveTemplate("escape", "${actorName?api}", data))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void compiledTemplateCacheIsBoundedNotUnbounded() {
        FreemarkerTemplateResolver bounded = new FreemarkerTemplateResolver(5);
        AuditLogClientData data = clientDataWithActor("Ada");

        for (int i = 0; i < 50; i++) {
            bounded.resolveTemplate("template-" + i, "Hello ${actorName} #" + i, data);
        }

        assertThat(bounded.cacheSize()).isLessThanOrEqualTo(5);
    }

    private AuditLogClientData clientDataWithActor(String actorName) {
        // CONTEXT actor source with no getter and no request context leaves actor fields
        // untouched by the constructor, so the explicit setActorName below is the only thing
        // that sets it.
        AuditLogClientData data = new AuditLogClientData(
                fixtureAudit(), null, null, false, null, new AuditLogProperties(), null, null);
        data.setActorName(actorName);
        return data;
    }

    private Audit fixtureAudit() {
        try {
            return Fixture.class.getDeclaredMethod("action").getAnnotation(Audit.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Fixture {
        @Audit(auditType = "test")
        void action() {
        }
    }
}
