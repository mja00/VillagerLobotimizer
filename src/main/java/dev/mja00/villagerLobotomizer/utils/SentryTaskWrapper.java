package dev.mja00.villagerLobotomizer.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.sentry.Sentry;

import java.util.function.Consumer;

public class SentryTaskWrapper {

    /**
     * Wraps a ScheduledTask consumer to capture exceptions
     * Used for both EntityScheduler and GlobalRegionScheduler tasks
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
