package net.fornwall.androidneovim.ui;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import org.junit.Test;

public class KeyNotationTest {
    @Test public void literalEscapesOnlyLessThan() {
        assertEquals("a<LT>Esc>", KeyNotation.literal("a<Esc>"));
        assertEquals("é世\n", KeyNotation.literal("é世\n"));
    }

    @Test public void modifiersFollowNeovimLetters() {
        int all = KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON | KeyEvent.META_SHIFT_ON;
        assertEquals("C-A-D-S-", KeyNotation.modifiers(all, true));
        assertEquals("C-A-D-", KeyNotation.modifiers(all, false));
        assertEquals("", KeyNotation.modifiers(KeyEvent.META_NUM_LOCK_ON | KeyEvent.META_CAPS_LOCK_ON, true));
    }

    @Test public void specialKeysHaveNeovimNames() {
        assertEquals("Esc", KeyNotation.special(KeyEvent.KEYCODE_ESCAPE));
        assertEquals("PageDown", KeyNotation.special(KeyEvent.KEYCODE_PAGE_DOWN));
        assertEquals("F12", KeyNotation.special(KeyEvent.KEYCODE_F12));
        assertNull(KeyNotation.special(KeyEvent.KEYCODE_A));
    }

    @Test public void modifiedAppliesToExactlyOneKey() {
        assertEquals("<C-Left>", KeyNotation.modified("<Left>", true, false));
        assertEquals("<C-A-:>", KeyNotation.modified(":", true, true));
        assertEquals("<A-S-Tab>", KeyNotation.modified("<S-Tab>", false, true));
        assertEquals("<C-A-a>", KeyNotation.modified("<M-a>", true, false));
        assertEquals("<C-Space>", KeyNotation.modified(" ", true, false));
        assertEquals("<C-LT>", KeyNotation.modified("<LT>", true, false));
        assertEquals("<C-😀>", KeyNotation.modified("😀", true, false));
        assertEquals("abc", KeyNotation.modified("abc", true, false));
        assertEquals("<LT>a", KeyNotation.modified("<LT>a", true, false));
    }
}
