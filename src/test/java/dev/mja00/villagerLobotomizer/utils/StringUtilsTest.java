package dev.mja00.villagerLobotomizer.utils;

import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringUtilsTest {

    private static Stream<String> keys() {
        return Stream.of("entity.villager.celebrate", "entity.villager.work_librarian", "block.note_block.pling");
    }

    @Test
    void legacyNamesWithUnderscoresInsideASegmentResolveToTheRealKey() {
        assertEquals("entity.villager.work_librarian",
                StringUtils.convertLegacySoundNameFormat("ENTITY_VILLAGER_WORK_LIBRARIAN", keys()));
        assertEquals("block.note_block.pling",
                StringUtils.convertLegacySoundNameFormat("BLOCK_NOTE_BLOCK_PLING", keys()));
        assertEquals("entity.villager.celebrate",
                StringUtils.convertLegacySoundNameFormat("ENTITY_VILLAGER_CELEBRATE", keys()));
    }

    @Test
    void unmatchedOrModernNamesAreReturnedUnchanged() {
        assertEquals("NOT_A_SOUND", StringUtils.convertLegacySoundNameFormat("NOT_A_SOUND", keys()));
        assertEquals("entity.villager.work_librarian",
                StringUtils.convertLegacySoundNameFormat("entity.villager.work_librarian", keys()));
        assertEquals("", StringUtils.convertLegacySoundNameFormat("", keys()));
    }
}
