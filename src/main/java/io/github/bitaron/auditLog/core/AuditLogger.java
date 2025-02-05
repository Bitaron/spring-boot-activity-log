package io.github.bitaron.auditLog.core;

import com.google.gson.Gson;
import io.github.bitaron.auditLog.contract.AuditLogDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.TemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.dto.AuditLogTemplateData;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class AuditLogger {

    AuditLogGenericDataGetter auditLogGenericDataGetter;
    AuditLogRepository auditLogRepository;
    AuditTemplateRepository auditTemplateRepository;
    AuditGroupRepository auditGroupRepository;

    Map<String, AuditLogDataGetter> auditTypeToDataGetterMap;

    TemplateResolver templateResolver;


    public AuditLogger(AuditLogGenericDataGetter auditLogGenericDataGetter,
                       List<AuditLogDataGetter> auditLogDataGetterList,
                       AuditLogRepository auditLogRepository,
                       AuditTemplateRepository auditTemplateRepository,
                       AuditGroupRepository auditGroupRepository,
                       TemplateResolver templateResolver) {
        this.auditLogGenericDataGetter = auditLogGenericDataGetter;
        this.auditLogRepository = auditLogRepository;
        this.auditTemplateRepository = auditTemplateRepository;
        this.auditGroupRepository = auditGroupRepository;
        this.auditTypeToDataGetterMap = new HashMap<>();
        this.templateResolver = templateResolver;

        for (AuditLogDataGetter auditLogDataGetter : auditLogDataGetterList) {
            if (auditLogDataGetter.getAuditType() != null
                    && !auditLogDataGetter.getAuditType().isEmpty()) {
                this.auditTypeToDataGetterMap.put(auditLogDataGetter.getAuditType(), auditLogDataGetter);
            }
        }
    }

    @Async
    public void log(String auditType, AuditLogClientData clientData) {
        clientData.setActorId(auditLogGenericDataGetter.getActorId());
        Gson gson = new Gson();
        AuditLogDataGetter auditLogDataGetter = auditTypeToDataGetterMap.get(auditType);
        if (auditLogDataGetter == null) {
            log.error("No data getter found for audit type: {}",auditType);
        } else {
            List<AuditTemplate> auditTemplateList = auditTemplateRepository.findAllByNameIn(auditLogDataGetter.getTemplateList());
            Long groupId = null;
            if (auditLogDataGetter.getGroupName() != null) {
                AuditGroup auditGroup = new AuditGroup();
                auditGroup.setName(auditLogDataGetter.getGroupName());
                auditGroupRepository.save(auditGroup);
                groupId = auditGroup.getId();
            }
            List<AuditLog> auditLogList = new ArrayList<>();
            for (String template : auditLogDataGetter.getTemplateList()) {
                for (AuditTemplate auditTemplate : auditTemplateList) {
                    if (auditTemplate.getName().equals(template)) {
                        AuditLogTemplateData templateData = auditLogDataGetter.getData(clientData);
                        LocalDateTime currentTime = LocalDateTime.now(ZoneOffset.UTC);
                        String message = templateResolver.resolveTemplate(auditTemplate.getTemplate(),
                                templateData);
                        AuditLog auditLog = new AuditLog();
                        auditLog.setAuditType(auditType);
                        auditLog.setActionName(auditLogDataGetter.getActionName());
                        auditLog.setActionType(auditLogDataGetter.getActionType());
                        auditLog.setActorId(auditLogGenericDataGetter.getActorId());
                        auditLog.setActorName(auditLogGenericDataGetter.getActorName());
                        auditLog.setClientIp(auditLogGenericDataGetter.getClientIp());
                        auditLog.setClientLocation(auditLogGenericDataGetter.getClientLocation());
                        auditLog.setUserAgent(auditLogGenericDataGetter.getUserAgent());
                        auditLog.setCreatedAt(currentTime);
                        auditLog.setTemplateId(auditTemplate.getId());
                        auditLog.setMessage(message);
                        auditLog.setData(gson.toJson(templateData));
                        auditLog.setGroupId(groupId);
                        auditLogList.add(auditLog);
                    }
                }
            }
            auditLogRepository.saveAll(auditLogList);
        }
    }
}
