package dev.mja00.villagerLobotomizer.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentPreservingYamlMigratorTest {
    private final CommentPreservingYamlMigrator migrator =
            new CommentPreservingYamlMigrator(Logger.getLogger("CommentPreservingYamlMigratorTest"));

    @Test
    void preservesNestedUserCommentsDuringMerge() throws IOException {
        String existingYaml = """
                # Existing Villager Lobotomizer config
                behavior:
                  #Keep user comment for nested value
                  always-active-names:
                    - Alice
                """;

        String defaultYaml = """
                behavior:
                  always-active-names:
                    - DefaultName
                  cooldown-seconds: 30
                """;

        String merged = migrator.mergeWithComments(existingYaml, defaultYaml);

        assertTrue(merged.contains("#Keep user comment for nested value"),
                () -> "Expected merged YAML to contain nested user comment:\n" + merged);
    }

    @Test
    void appliesDefaultCommentsToNewNestedFields() throws IOException {
        String existingYaml = """
                behavior:
                  always-active-names:
                    - Alice
                """;

        String defaultYaml = """
                behavior:
                  always-active-names:
                    - DefaultName
                  #Default cooldown comment
                  cooldown-seconds: 30
                """;

        String merged = migrator.mergeWithComments(existingYaml, defaultYaml);

        assertTrue(merged.contains("#Default cooldown comment"),
                () -> "Expected merged YAML to contain default nested comment:\n" + merged);
    }
}
