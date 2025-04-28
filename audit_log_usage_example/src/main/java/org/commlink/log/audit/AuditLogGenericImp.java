package org.commlink.log.audit;


import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import org.springframework.stereotype.Service;

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
