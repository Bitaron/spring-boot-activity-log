package io.github.bitaron.auditLog.dto;

import lombok.Data;

@Data
public class ActivityLogDto {
    private String requestedUserId;
    private Object args;
    private Object response;
    private boolean exceptionThrown;
    private Object exception;


    public ActivityLogDto(String requestedUserId, Object args, Object response, boolean exceptionThrown) {
        this.requestedUserId = requestedUserId;
        this.args = args;
        this.exceptionThrown = exceptionThrown;
        if (!exceptionThrown) {
            this.response = response;
        } else {
            this.exception = response;
        }
    }
}
