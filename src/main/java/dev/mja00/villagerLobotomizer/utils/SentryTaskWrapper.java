package dev.mja00.villagerLobotomizer.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.sentry.Sentry;

import java.util.function.Consumer;

public class SentryTaskWrapper {

    /**
     * Wraps a ScheduledTask consumer to capture exceptions to Sentry while preserving Folia thread safety.
     *
     * <p>This wrapper captures exceptions and sends them to Sentry for error tracking, but critically
     * <strong>re-throws the exception</strong> to maintain Paper/Folia's scheduler behavior. This ensures
     * that failing tasks are properly handled by the scheduler's error handling mechanisms and don't
     * silently swallow exceptions.
     *
     * <p>Thread Safety: This wrapper is safe to use with both {@link io.papermc.paper.threadedregions.scheduler.EntityScheduler}
     * and {@link io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler} in Folia environments.
     * The re-throw behavior ensures that thread ownership violations and other critical errors are not
     * masked by Sentry's exception capture.
     *
     * @param task the task consumer to wrap
     * @return a wrapped consumer that captures exceptions to Sentry before re-throwing them
     */
    public static Consumer<ScheduledTask> wrap(Consumer<ScheduledTask> task) {
        return (scheduledTask) -> {
            try {
                task.accept(scheduledTask);
            } catch (Exception e) {
                // Only capture if Sentry is initialized
                if (Sentry.isEnabled()) {
                    Sentry.captureException(e);
                }
                throw e; // Re-throw to maintain existing behavior
            }
        };
    }
}
