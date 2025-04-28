package org.commlink.log.audit;

import io.github.bitaron.auditLog.contract.AuditLogDataGetter;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.dto.AuditLogTemplateData;
import lombok.Data;
import org.commlink.log.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class TestActivityLog implements AuditLogDataGetter {
    @Override
    public String getAuditType() {
        return "test";
    }

    @Override
    public String getActionName() {
        return "aNAME";
    }

    @Override
    public String getActionType() {
        return "aType";
    }

    @Override
    public String getGroupName() {
        return null;
    }

    @Override
    public List<String> getTemplateList() {
        return Collections.singletonList("test");
    }

    @Override
    public AuditLogTemplateData getData(AuditLogClientData auditLogClientData) {
        ResponseEntity response = (ResponseEntity) auditLogClientData.getResponse();
        return new TemData((Test.Response) response.getBody());
    }


    @Data
    public static class TemData extends AuditLogTemplateData {
        Test.Response data;

        public TemData(Test.Response data) {
            this.data = data;
        }
    }
}
