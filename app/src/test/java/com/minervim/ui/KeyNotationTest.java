package com.minervim.ui;

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
        assertEquals("kEnter", KeyNotation.special(KeyEvent.KEYCODE_NUMPAD_ENTER));
    }

    @Test public void keypadKeysAreNamedByTheCharacterTheyType() {
        assertEquals("k0", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_0, '0'));
        assertEquals("k9", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_9, '9'));
        assertEquals("kPlus", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_ADD, '+'));
        assertEquals("kMinus", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, '-'));
        assertEquals("kMultiply", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, '*'));
        assertEquals("kDivide", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_DIVIDE, '/'));
        assertEquals("kPoint", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_DOT, '.'));
        // Layouts with a decimal comma type "," on the keypad's decimal key.
        assertEquals("kComma", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_DOT, ','));
        assertEquals("kEqual", KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_EQUALS, '='));
        assertNull(KeyNotation.keypad(KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN, '('));
        assertNull(KeyNotation.keypad(KeyEvent.KEYCODE_5, '5'));
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
        assertEquals("<C-A->>", KeyNotation.modified("<C->>", false, true));
        assertEquals("<C->>", KeyNotation.modified(">", true, false));
        assertEquals("<LT>a>", KeyNotation.modified("<LT>a>", true, false));
    }

    @Test public void ctrlKeepsShiftOnlyWithLetters() {
        int ctrlShift = KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON;
        assertEquals("<C-S-j>", KeyNotation.modifiedKey('J', 'j', ctrlShift));
        assertEquals("<C-S-j>", KeyNotation.modifiedKey('j', 'J', ctrlShift | KeyEvent.META_CAPS_LOCK_ON));
        assertEquals("<C-A-S-j>", KeyNotation.modifiedKey('J', 'j', ctrlShift | KeyEvent.META_ALT_ON));
        assertEquals("<C-j>", KeyNotation.modifiedKey('j', 'j', KeyEvent.META_CTRL_ON));
        assertEquals("<A-J>", KeyNotation.modifiedKey('J', 'j', KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON));
        assertEquals("<C-@>", KeyNotation.modifiedKey('@', '2', ctrlShift));
        assertEquals("<C-Ж>", KeyNotation.modifiedKey('Ж', 'ж', ctrlShift));
        assertEquals("<A-Space>", KeyNotation.modifiedKey(' ', ' ', KeyEvent.META_ALT_ON));
        assertEquals("<D-LT>", KeyNotation.modifiedKey('<', ',', KeyEvent.META_META_ON | KeyEvent.META_SHIFT_ON));
    }
}
