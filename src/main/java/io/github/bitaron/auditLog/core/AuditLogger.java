package io.github.bitaron.auditLog.core;

import io.github.bitaron.auditLog.contract.AuditLogDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.TemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.entity.AuditTemplate;
import io.github.bitaron.auditLog.repository.AuditGroupRepository;
import io.github.bitaron.auditLog.repository.AuditLogRepository;
import io.github.bitaron.auditLog.repository.AuditTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

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
        for (AuditLogDataGetter auditLogDataGetter : auditLogDataGetterList) {
            if (auditLogDataGetter.getActionType() != null
                    && !auditLogDataGetter.getActionType().isEmpty()) {
                this.auditTypeToDataGetterMap.put(auditLogDataGetter.getActionType(), auditLogDataGetter);
            }
            {
            }
            this.templateResolver = templateResolver;
        }

        @Async
        public void log (String auditType, AuditLogClientData clientData){
            clientData.setActorId(auditLogGenericDataGetter.getActorId());
            AuditLogDataGetter auditLogDataGetter = auditTypeToDataGetterMap.get(auditType);
            if (auditLogDataGetter == null) {
                log.error(STR."No data getter found for audit type: \{auditType}");
            } else {
                List<AuditTemplate> auditTemplateList = auditTemplateRepository.findAllByName(auditLogDataGetter.getTemplateList());

            }
        }
    }
