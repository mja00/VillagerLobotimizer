package dev.mja00.villagerLobotomizer.utils;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void userStringsSurviveMergeWithoutChangingTypeOrBreakingYaml() throws IOException {
        String existingYaml = """
                message: "[Shop] <red>No trading</red>"
                legacy-message: "&cNo trading"
                path: "C:\\\\villagers\\\\"
                names:
                  - "yes"
                  - "null"
                  - "~"
                  - "123"
                  - "*star"
                """;

        String defaultYaml = """
                message: "<red>default</red>"
                legacy-message: "default"
                path: "default"
                names:
                  - DefaultName
                check-roof: true
                """;

        String merged = migrator.mergeWithComments(existingYaml, defaultYaml);
        Map<String, Object> reparsed = new Yaml().load(merged);

        assertEquals("[Shop] <red>No trading</red>", reparsed.get("message"), merged);
        assertEquals("&cNo trading", reparsed.get("legacy-message"), merged);
        assertEquals("C:\\villagers\\", reparsed.get("path"), merged);
        assertEquals(List.of("yes", "null", "~", "123", "*star"), reparsed.get("names"), merged);
        assertEquals(true, reparsed.get("check-roof"), merged);
    }
}
