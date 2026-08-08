package org.commlink.log.audit;


import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import org.springframework.stereotype.Service;

/**
 * Sample {@link AuditLogGenericDataGetter}. Registering a bean of this type is how a consuming
 * application overrides the starter's default header-based actor resolution - the starter picks
 * it up automatically via {@code @ConditionalOnMissingBean}. This is the extension point to
 * implement for a real application (e.g. reading the authenticated user from your own security
 * context); it was previously missing {@code @Service} and so was never actually registered.
 * <p>
 * Every method on {@link AuditLogGenericDataGetter} is {@code default} (WP17) - this sample only
 * overrides the two actor-identity methods it actually cares about; {@code getClientLocation}/
 * {@code getClientIp}/{@code getUserAgent} fall back to the interface's documented defaults.
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
}
