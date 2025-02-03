package io.github.bitaron.core;

import java.util.List;

public abstract class ActivityLogMain {
    private List<ActivityLogType> activityLogTypeList;

    public ActivityLogMain(List<ActivityLogType> activityLogTypeList) {
        this.activityLogTypeList = activityLogTypeList;
    }

    public List<ActivityLogType> getActivityLogTypeList() {
        return activityLogTypeList;
    }

    public abstract String getCallerId();

}
