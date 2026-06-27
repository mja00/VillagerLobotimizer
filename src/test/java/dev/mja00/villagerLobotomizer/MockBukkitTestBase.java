package dev.mja00.villagerLobotomizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Base for tests that need a live (mocked) Bukkit server. Sets up and tears down a fresh
 * {@link ServerMock} per test so registries and entities are available without a real server.
 */
public abstract class MockBukkitTestBase {

    protected ServerMock server;

    @BeforeEach
    void mockServer() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void unmockServer() {
        MockBukkit.unmock();
    }
}
