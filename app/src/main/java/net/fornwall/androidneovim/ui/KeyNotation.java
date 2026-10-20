package net.fornwall.androidneovim.ui;

import android.view.KeyEvent;

/** Neovim key notation for Android input. Uses only key codes and meta-state constants, so JVM tests run it. */
final class KeyNotation {
    private KeyNotation() { }

    /** Text typed as itself: in nvim_input only {@code <} is special. */
    static String literal(String text) { return text.replace("<", "<LT>"); }

    /** Modifier prefixes such as {@code C-A-} for a meta state; Shift only when {@code shift} is set. */
    static String modifiers(int meta, boolean shift) {
        return ((meta & KeyEvent.META_CTRL_ON) != 0 ? "C-" : "") + ((meta & KeyEvent.META_ALT_ON) != 0 ? "A-" : "")
                + ((meta & KeyEvent.META_META_ON) != 0 ? "D-" : "") + (shift && (meta & KeyEvent.META_SHIFT_ON) != 0 ? "S-" : "");
    }

    /** The Neovim name of a key that never types text, such as {@code Esc}, or null. */
    static String special(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE -> "Esc";
            case KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "CR";
            case KeyEvent.KEYCODE_DEL -> "BS";
            case KeyEvent.KEYCODE_FORWARD_DEL -> "Del";
            case KeyEvent.KEYCODE_TAB -> "Tab";
            case KeyEvent.KEYCODE_DPAD_UP -> "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "Right";
            case KeyEvent.KEYCODE_MOVE_HOME -> "Home";
            case KeyEvent.KEYCODE_MOVE_END -> "End";
            case KeyEvent.KEYCODE_PAGE_UP -> "PageUp";
            case KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown";
            case KeyEvent.KEYCODE_INSERT -> "Insert";
            default -> keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12 ? "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1) : null;
        };
    }

    /** Adds modifiers to one key such as {@code <S-Tab>} or {@code :}; other input is returned unchanged. */
    static String modified(String keys, boolean ctrl, boolean alt) {
        String key, existing = "";
        int last = keys.length() - 1;
        if (last > 1 && keys.charAt(0) == '<' && keys.indexOf('<', 1) < 0 && keys.indexOf('>') == last) {
            key = keys.substring(1, last);
            while (key.length() > 2 && key.charAt(1) == '-' && "CAMDS".indexOf(Character.toUpperCase(key.charAt(0))) >= 0) {
                existing += Character.toUpperCase(key.charAt(0));
                key = key.substring(2);
            }
        } else if (!keys.isEmpty() && keys.codePointCount(0, keys.length()) == 1) {
            key = keys.equals(" ") ? "Space" : keys;
        } else return keys;
        return "<" + (ctrl || existing.contains("C") ? "C-" : "")
                + (alt || existing.contains("A") || existing.contains("M") ? "A-" : "")
                + (existing.contains("D") ? "D-" : "") + (existing.contains("S") ? "S-" : "") + key + ">";
    }
}
