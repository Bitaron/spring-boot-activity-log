package io.github.bitaron.auditLog.core;


import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.contract.ActivityLogMain;
import io.github.bitaron.auditLog.contract.ActivityLogType;
import io.github.bitaron.auditLog.dto.ActivityLogDto;
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
    ActivityLogMain activityLogMain;
    AuditLogRepository auditLogRepository;

    public AuditLogAspect(ActivityLogMain activityLogMain, AuditLogRepository repository) {
        this.activityLogMain = activityLogMain;
        this.auditLogRepository = repository;
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
        log.info("IN aspect");
        for (ActivityLogType logType : activityLogMain.getActivityLogTypeList()) {
            if (logType.getType().equals(actLog.type())) {
                ActivityLogDto activityLogDto = new ActivityLogDto(activityLogMain.getCallerId(), joinPoint.getArgs(), response,
                        exceptionThrown);
                List<String> templateList = logType.getTemplateResolver().resolveTemplate(activityLogDto,
                        logType.getTemplateNameList());
                AuditLog entity = new AuditLog();
                entity.setTemplate(logType.getTemplateNameList().get(0));
                entity.setTemplate(templateList.get(0));
                entity.setUserId(activityLogDto.getRequestedUserId());
                auditLogRepository.save(entity);
            }
        }
    }
}
