package org.commlink.log.audit;


import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import org.springframework.stereotype.Service;

/**
 * Sample {@link AuditLogGenericDataGetter}. Registering a bean of this type is how a consuming
 * application overrides the starter's default header-based actor resolution - the starter picks
 * it up automatically via {@code @ConditionalOnMissingBean}. This is the extension point to
 * implement for a real application (e.g. reading the authenticated user from your own security
 * context); it was previously missing {@code @Service} and so was never actually registered.
 */
@Service
public class AuditLogGenericImp implements AuditLogGenericDataGetter {


    @Override
    public String getActorId() {
        return "actId";
    }

    @Override
    public String getActorName() {
        return "actName";
    }

    @Override
    public String getClientLocation() {
        return "ctLoc";
    }

    @Override
    public String getClientIp() {
        return "ctIP";
    }

    @Override
    public String getUserAgent() {
        return "ctUA";
    }
}
