package io.github.bitaron.auditLog.contract;

import io.github.bitaron.auditLog.dto.ActivityLogDto;

import java.util.List;

public interface TemplateResolver {
    List<String> resolveTemplate(ActivityLogDto dto, List<String> templateNameList);
}
