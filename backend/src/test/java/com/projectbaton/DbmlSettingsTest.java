package com.projectbaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DbmlSettingsTest {
    @Test
    void aNoteDoesNotCountAsASetting() {
        assertEquals("null, ", DbmlSettings.keywords("null, note: 'not null after V7, pk of the old table, unique'"));
        assertFalse(DbmlSettings.unique(DbmlSettings.keywords("null, note: 'unique per project'")));
    }

    @Test
    void keywordsOutsideANoteStillCount() {
        assertTrue(DbmlSettings.unique(DbmlSettings.keywords("not null, unique, note: 'x'")));
        assertEquals("not null", DbmlSettings.keywords("not null"));
        assertEquals("", DbmlSettings.keywords(null));
        assertEquals("pk, ", DbmlSettings.keywords("pk, note: 'it\\'s quoted'"));
    }
}
