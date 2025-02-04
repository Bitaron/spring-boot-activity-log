package io.github.bitaron.auditLog.core;


import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogDataGetter;
import io.github.bitaron.auditLog.contract.TemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.entity.AuditLog;
import io.github.bitaron.auditLog.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private AuditLogger auditLogger;

    public AuditLogAspect(AuditLogGenericDataGetter auditLogGenericDataGetter,
                          List<AuditLogDataGetter> auditLogDataGetterList,
                          AuditLogRepository auditLogRepository,
                          TemplateResolver templateResolver) {
        this.auditLogger = new AuditLogger(auditLogGenericDataGetter,
                auditLogDataGetterList, auditLogRepository, templateResolver);
    }

    @AfterReturning(pointcut = "@annotation(actLog)", returning = "response")
    public void logMethodActionSuccess(JoinPoint joinPoint, Audit actLog, Object response) {
        logActivity(actLog, joinPoint, response, false);
    }

    @AfterThrowing(pointcut = "@annotation(actLog)", throwing = "response")
    public void logMethodActionException(JoinPoint joinPoint, Audit actLog, Object response) {
        logActivity(actLog, joinPoint, response, true);
    }

    private void logActivity(Audit actLog, JoinPoint joinPoint, Object response, boolean exceptionThrown) {
        AuditLogClientData auditLogClientData = new AuditLogClientData(joinPoint.getArgs(), response,
                exceptionThrown);
        auditLogger.log(actLog.type(),auditLogClientData);
    }
}
