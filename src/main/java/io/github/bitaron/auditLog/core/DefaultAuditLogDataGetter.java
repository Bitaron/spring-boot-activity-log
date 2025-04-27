package io.github.bitaron.auditLog.core;

import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.dto.AuditLogTemplateData;

import java.util.List;

public class DefaultAuditLogDataGetter implements AuditLogDataGetter {
    @Override
    public String getAuditType() {
        return "";
    }

    @Override
    public String getActionName() {
        return "";
    }

    @Override
    public String getActionType() {
        return "";
    }

    @Override
    public String getGroupName() {
        return "";
    }

    @Override
    public List<String> getTemplateList() {
        return List.of();
    }

    @Override
    public AuditLogTemplateData getData(AuditLogClientData auditLogClientData) {
        return null;
    }
}
