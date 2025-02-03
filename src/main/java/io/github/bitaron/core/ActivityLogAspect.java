package io.github.bitaron.core;


import io.github.bitaron.core.entity.ActivityLogEntity;
import io.github.bitaron.core.repository.ActivityLogRepository;
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
public class ActivityLogAspect {
    ActivityLogMain activityLogMain;
    ActivityLogRepository activityLogRepository;

    public ActivityLogAspect(ActivityLogMain activityLogMain, ActivityLogRepository repository) {
        this.activityLogMain = activityLogMain;
        this.activityLogRepository = repository;
    }

    @AfterReturning(pointcut = "@annotation(actLog)", returning = "response")
    public void logMethodActionSuccess(JoinPoint joinPoint, ActivityLog actLog, Object response) {
        logActivity(actLog, joinPoint, response, false);
    }

    @AfterThrowing(pointcut = "@annotation(actLog)", throwing = "response")
    public void logMethodActionException(JoinPoint joinPoint, ActivityLog actLog, Object response) {
        logActivity(actLog, joinPoint, response, true);
    }

    private void logActivity(ActivityLog actLog, JoinPoint joinPoint, Object response, boolean exceptionThrown) {
        log.info("IN aspect");
        for (ActivityLogType logType : activityLogMain.getActivityLogTypeList()) {
            if (logType.getType().equals(actLog.type())) {
                ActivityLogDto activityLogDto = new ActivityLogDto(activityLogMain.getCallerId(), joinPoint.getArgs(), response,
                        exceptionThrown);
                List<String> templateList = logType.getTemplateResolver().resolveTemplate(activityLogDto,
                        logType.getTemplateNameList());
                ActivityLogEntity entity = new ActivityLogEntity();
                entity.setTemplate(logType.getTemplateNameList().get(0));
                entity.setTemplate(templateList.get(0));
                entity.setUserId(activityLogDto.getRequestedUserId());
                activityLogRepository.save(entity);
            }
        }
    }
}
