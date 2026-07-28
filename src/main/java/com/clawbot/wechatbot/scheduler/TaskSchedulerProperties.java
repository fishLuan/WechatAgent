package com.clawbot.wechatbot.scheduler;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
@ConfigurationProperties(prefix = "clawbot.scheduler")
public class TaskSchedulerProperties {

    private boolean enabled = true;
    private int poolSize = 5;
    private int awaitTerminationSeconds = 30;
    private boolean persistenceEnabled = true;
    private String defaultTimezone = "Asia/Shanghai";
    private long oneshotRestoreToleranceMs = 10 * 60 * 1000L;

    @PostConstruct
    void validate() {
        if (poolSize < 1) {
            throw new IllegalStateException("clawbot.scheduler.pool-size must be >= 1");
        }
        if (awaitTerminationSeconds < 1) {
            throw new IllegalStateException(
                "clawbot.scheduler.await-termination-seconds must be >= 1");
        }
        try {
            ZoneId.of(defaultTimezone);
        } catch (Exception e) {
            throw new IllegalStateException(
                "clawbot.scheduler.default-timezone is invalid: " + defaultTimezone, e);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

    public int getAwaitTerminationSeconds() { return awaitTerminationSeconds; }
    public void setAwaitTerminationSeconds(int s) { this.awaitTerminationSeconds = s; }

    public boolean isPersistenceEnabled() { return persistenceEnabled; }
    public void setPersistenceEnabled(boolean enabled) { this.persistenceEnabled = enabled; }

    public String getDefaultTimezone() { return defaultTimezone; }
    public void setDefaultTimezone(String tz) { this.defaultTimezone = tz; }

    public long getOneshotRestoreToleranceMs() { return oneshotRestoreToleranceMs; }
    public void setOneshotRestoreToleranceMs(long ms) { this.oneshotRestoreToleranceMs = Math.max(0, ms); }
}