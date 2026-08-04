package io.github.bitaron.auditLog.core;

import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.properties.AuditLogProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreemarkerTemplateResolverTest {

    private final FreemarkerTemplateResolver resolver = new FreemarkerTemplateResolver();

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

    private AuditLogClientData clientDataWithActor(String actorName) {
        // isActorCommon=false with no getter and no request context leaves actor fields
        // untouched by the constructor, so the explicit setActorName below is the only thing
        // that sets it.
        AuditLogClientData data = new AuditLogClientData(
                fixtureAudit(), null, null, false, null, new AuditLogProperties(), null);
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
        @Audit(auditType = "test", isActorCommon = false)
        void action() {
        }
    }
}
