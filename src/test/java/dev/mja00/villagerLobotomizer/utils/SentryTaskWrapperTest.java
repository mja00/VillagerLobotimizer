package dev.mja00.villagerLobotomizer.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.sentry.Sentry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SentryTaskWrapperTest {

    @BeforeEach
    void setUp() {
        // Initialize Sentry in test mode (if not already initialized)
        if (!Sentry.isEnabled()) {
            Sentry.init(options -> {
                options.setDsn("https://fake@test.ingest.sentry.io/1234567");
                options.setEnvironment("test");
            });
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up Sentry after each test
        Sentry.close();
    }

    @Test
    void wrapReThrowsExceptions() {
        // Create a task that throws an exception
        Consumer<ScheduledTask> failingTask = (task) -> {
            throw new RuntimeException("Test exception");
        };

        Consumer<ScheduledTask> wrapped = SentryTaskWrapper.wrap(failingTask);

        // Verify that the exception is re-thrown
        assertThrows(RuntimeException.class, () -> {
            wrapped.accept(null); // Pass null since we don't actually need a real ScheduledTask for this test
        }, "Wrapped task should re-throw exceptions");
    }

    @Test
    void wrapPreservesExceptionMessage() {
        String expectedMessage = "Custom test exception";
        Consumer<ScheduledTask> failingTask = (task) -> {
            throw new IllegalStateException(expectedMessage);
        };

        Consumer<ScheduledTask> wrapped = SentryTaskWrapper.wrap(failingTask);

        // Verify that the exception message is preserved
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            wrapped.accept(null);
        });

        assertEquals(expectedMessage, exception.getMessage(),
            "Exception message should be preserved after wrapping");
    }

    @Test
    void wrapPreservesExceptionType() {
        Consumer<ScheduledTask> failingTask = (task) -> {
            throw new IllegalArgumentException("Test");
        };

        Consumer<ScheduledTask> wrapped = SentryTaskWrapper.wrap(failingTask);

        // Verify that the exception type is preserved
        assertThrows(IllegalArgumentException.class, () -> {
            wrapped.accept(null);
        }, "Exception type should be preserved after wrapping");
    }

    @Test
    void wrapAllowsSuccessfulExecution() {
        // Create a task that doesn't throw
        final boolean[] executed = {false};
        Consumer<ScheduledTask> successfulTask = (task) -> {
            executed[0] = true;
        };

        Consumer<ScheduledTask> wrapped = SentryTaskWrapper.wrap(successfulTask);

        // Should not throw
        assertDoesNotThrow(() -> wrapped.accept(null),
            "Wrapped task should not throw when inner task succeeds");

        assertTrue(executed[0], "Task should have been executed");
    }

    @Test
    void wrapHandlesSentryNotEnabled() {
        // Close Sentry to simulate it being disabled
        Sentry.close();

        Consumer<ScheduledTask> failingTask = (task) -> {
            throw new RuntimeException("Test exception");
        };

        Consumer<ScheduledTask> wrapped = SentryTaskWrapper.wrap(failingTask);

        // Should still re-throw even when Sentry is disabled
        assertThrows(RuntimeException.class, () -> {
            wrapped.accept(null);
        }, "Should re-throw exceptions even when Sentry is not enabled");
    }
}
