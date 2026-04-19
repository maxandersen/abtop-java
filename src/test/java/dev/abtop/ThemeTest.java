package dev.abtop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {

    @Test
    void allPresetsLoad() {
        for (String name : Theme.THEME_NAMES) {
            assertNotNull(Theme.byName(name), "theme '" + name + "' not found");
        }
    }

    @Test
    void unknownReturnsNull() {
        assertNull(Theme.byName("nonexistent"));
    }

    @Test
    void defaultIsBtop() {
        assertEquals("btop", Theme.defaultTheme().name());
    }
}
