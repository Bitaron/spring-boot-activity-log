package io.github.bitaron.core;

import java.util.List;

public interface TemplateResolver {
    List<String> resolveTemplate(ActivityLogDto dto, List<String> templateNameList);
}
