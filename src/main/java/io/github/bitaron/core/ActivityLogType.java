package io.github.bitaron.core;

import lombok.Data;

import java.util.List;

@Data
public class ActivityLogType {
    private String type;
    private List<String> templateNameList;
    private TemplateResolver templateResolver;

    public ActivityLogType(String type, List<String> templateNameList, TemplateResolver templateResolver) {
        this.type = type;
        this.templateNameList = templateNameList;
        this.templateResolver = templateResolver;
    }
}
