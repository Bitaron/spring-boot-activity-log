package org.commlink.log.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitaron.auditLog.contract.TemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogTemplateData;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CustomTemplateResolver implements TemplateResolver {
    @Autowired
    ObjectMapper objectMapper;

    @SneakyThrows
    @Override
    public String resolveTemplate(String template, AuditLogTemplateData dto) {
        return template + objectMapper.writeValueAsString(dto);
    }
}
