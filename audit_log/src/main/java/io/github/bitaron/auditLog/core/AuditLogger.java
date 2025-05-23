package io.github.bitaron.auditLog.core;

import com.google.gson.Gson;
import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.entity.AuditGroup;
import io.github.bitaron.auditLog.entity.AuditLog;
import io.github.bitaron.auditLog.entity.AuditTemplate;
import io.github.bitaron.auditLog.repository.AuditGroupRepository;
import io.github.bitaron.auditLog.repository.AuditLogRepository;
import io.github.bitaron.auditLog.repository.AuditTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class AuditLogger {

    AuditLogRepository auditLogRepository;
    AuditTemplateRepository auditTemplateRepository;
    AuditGroupRepository auditGroupRepository;
    AuditLogTemplateResolver auditLogTemplateResolver;


    public AuditLogger(AuditLogRepository auditLogRepository,
                       AuditTemplateRepository auditTemplateRepository,
                       AuditGroupRepository auditGroupRepository,
                       AuditLogTemplateResolver auditLogTemplateResolver) {
        this.auditLogRepository = auditLogRepository;
        this.auditTemplateRepository = auditTemplateRepository;
        this.auditGroupRepository = auditGroupRepository;
        this.auditLogTemplateResolver = auditLogTemplateResolver;
    }

    @Async
    public void log(Audit audit, AuditLogClientData clientData) {
        Gson gson = new Gson();
        List<String> templateNameList = Arrays.stream(audit.templateNameList()).toList();
        List<AuditTemplate> auditTemplateList = auditTemplateRepository.findAllByNameIn(
                templateNameList);
        Long groupId = null;
        if (!audit.groupName().isEmpty()) {
            AuditGroup auditGroup = new AuditGroup();
            auditGroup.setName(audit.groupName());
            auditGroupRepository.save(auditGroup);
            groupId = auditGroup.getId();
        }
        List<AuditLog> auditLogList = new ArrayList<>();
        for (String template : templateNameList) {
            for (AuditTemplate auditTemplate : auditTemplateList) {
                if (auditTemplate.getName().equals(template)) {

                    LocalDateTime currentTime = LocalDateTime.now(ZoneOffset.UTC);
                    String message = auditLogTemplateResolver.resolveTemplate(auditTemplate.getName(),
                            auditTemplate.getTemplate(), clientData);
                    AuditLog auditLog = new AuditLog();
                    auditLog.setAuditType(audit.auditType());
                    auditLog.setActionName(audit.actionName());
                    auditLog.setActionType(audit.actionType());
                    auditLog.setActorId(clientData.getActorId());
                    auditLog.setActorName(clientData.getActorName());
                    auditLog.setClientIp(clientData.getClientIp());
                    auditLog.setClientLocation(clientData.getClientLocation());
                    auditLog.setUserAgent(clientData.getUserAgent());
                    auditLog.setCreatedAt(currentTime);
                    auditLog.setTemplateId(auditTemplate.getId());
                    auditLog.setMessage(message);
                    auditLog.setData(gson.toJson(clientData));
                    auditLog.setGroupId(groupId);
                    auditLogList.add(auditLog);
                }
            }
        }
        auditLogRepository.saveAll(auditLogList);
    }
}
