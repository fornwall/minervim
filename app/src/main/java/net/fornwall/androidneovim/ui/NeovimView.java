package net.fornwall.androidneovim.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import java.util.List;

/** A direct Neovim grid UI; there is no terminal emulator or escape-sequence layer. */
public final class NeovimView extends View {
    public interface Listener {
        void input(String keys);
        void paste(String text);
        void mouse(String button, String action, String modifiers, int row, int column);
        void resize(int columns, int rows);
    }

    private final NeovimGrid grid = new NeovimGrid();
    private final Paint[] textPaints = new Paint[4];
    private final Paint fill = new Paint();
    private final float cellWidth;
    private final float cellHeight;
    private final float baseline;
    private Listener listener;
    private int columns = 80;
    private int rows = 24;
    private int connectionGeneration;
    private int pendingAccent;
    private String composition = "";
    private String pressedButton;
    private int pressedRow;
    private int pressedColumn;
    private float wheelRemainder;

    public NeovimView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setContentDescription("Neovim editor");
        float textSize = 14 * getResources().getDisplayMetrics().scaledDensity;
        for (int style = 0; style < textPaints.length; style++) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, style));
            paint.setTextSize(textSize);
            textPaints[style] = paint;
        }
        // Keep fractional advances, matching android-ssh's TerminalMetrics approach.
        cellWidth = textPaints[0].measureText("MMMMMMMMMM") / 10;
        Paint.FontMetrics metrics = textPaints[0].getFontMetrics();
        cellHeight = (float) Math.ceil(metrics.descent - metrics.ascent);
        baseline = cellHeight - metrics.descent;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null && getWidth() > 0 && getHeight() > 0) listener.resize(columns, rows);
    }
    public int columns() { return columns; }
    public int rows() { return rows; }

    public void handleRedraw(List<?> events) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Redraw must run on main thread");
        if (grid.handleRedraw(events)) postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width <= 0 || height <= 0) return;
        int newColumns = Math.max(1, (int) (width / cellWidth));
        int newRows = Math.max(1, (int) (height / cellHeight));
        if (newColumns != columns || newRows != rows) {
            columns = newColumns;
            rows = newRows;
            if (listener != null) listener.resize(columns, rows);
        }
    }

    private float gridTop() { return Math.max(0, (int) ((getHeight() - grid.frame().rows * cellHeight) / 2)); }

    @Override protected void onDraw(Canvas canvas) {
        NeovimGrid.Frame frame = grid.frame();
        canvas.drawColor(frame.background);
        float top = gridTop();
        int visibleRows = Math.min(frame.rows, (int) Math.ceil(getHeight() / cellHeight));
        int visibleColumns = Math.min(frame.columns, (int) Math.ceil(getWidth() / cellWidth));
        // Backgrounds precede all glyphs, so the continuation cell cannot erase a wide glyph.
        for (int row = 0; row < visibleRows; row++) {
            for (int column = 0; column < visibleColumns; column++) {
                NeovimGrid.Highlight highlight = frame.highlight(row, column);
                fill.setColor(frame.background(highlight));
                canvas.drawRect(column * cellWidth, top + row * cellHeight,
                        (column + 1) * cellWidth, top + (row + 1) * cellHeight, fill);
            }
        }
        for (int row = 0; row < visibleRows; row++) {
            for (int column = 0; column < visibleColumns; column++) drawCell(canvas, frame, row, column, top, false);
        }
        if (!frame.busy && frame.cursorRow >= 0 && frame.cursorRow < visibleRows
                && frame.cursorColumn >= 0 && frame.cursorColumn < visibleColumns) {
            float x = frame.cursorColumn * cellWidth;
            float y = top + frame.cursorRow * cellHeight;
            fill.setColor(frame.foreground);
            switch (frame.cursorShape) {
                case "vertical" -> canvas.drawRect(x, y, x + Math.max(1, cellWidth * frame.cursorPercentage / 100), y + cellHeight, fill);
                case "horizontal" -> canvas.drawRect(x, y + cellHeight * (100 - frame.cursorPercentage) / 100, x + cellWidth, y + cellHeight, fill);
                default -> {
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, fill);
                    drawCell(canvas, frame, frame.cursorRow, frame.cursorColumn, top, true);
                }
            }
            if (!composition.isEmpty()) {
                Paint paint = textPaints[0];
                float width = paint.measureText(composition);
                fill.setColor(frame.background);
                canvas.drawRect(x, y, Math.min(getWidth(), x + width), y + cellHeight, fill);
                paint.setColor(frame.foreground);
                canvas.drawText(composition, x, y + baseline, paint);
                fill.setColor(frame.foreground);
                canvas.drawRect(x, y + cellHeight - 1, Math.min(getWidth(), x + width), y + cellHeight, fill);
            }
        }
    }

    private void drawCell(Canvas canvas, NeovimGrid.Frame frame, int row, int column, float top, boolean cursor) {
        String text = frame.text(row, column);
        if (text.isEmpty()) return;
        NeovimGrid.Highlight highlight = frame.highlight(row, column);
        Paint paint = textPaints[(highlight.bold ? Typeface.BOLD : 0) | (highlight.italic ? Typeface.ITALIC : 0)];
        paint.setColor(cursor ? frame.background : frame.foreground(highlight));
        float x = column * cellWidth;
        float y = top + row * cellHeight;
        int width = column + 1 < frame.columns && frame.text(row, column + 1).isEmpty() ? 2 : 1;
        int saved = canvas.save();
        canvas.clipRect(x, y, x + width * cellWidth, y + cellHeight);
        // Explicit LTR shaping follows TerminalRowPainter; Neovim owns cell order.
        canvas.drawTextRun(text, 0, text.length(), 0, text.length(), x, y + baseline, false, paint);
        fill.setColor(frame.special(highlight));
        if (highlight.underline) canvas.drawRect(x, y + cellHeight - 1, x + width * cellWidth, y + cellHeight, fill);
        if (highlight.strikethrough) canvas.drawRect(x, y + cellHeight / 2, x + width * cellWidth, y + cellHeight / 2 + 1, fill);
        canvas.restoreToCount(saved);
    }

    public void sendKey(String keys) {
        cancelComposition();
        if (listener != null) listener.input(keys);
    }

    public void pasteClipboard() {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        if (text != null && text.length() > 64 * 1024) {
            Toast.makeText(getContext(), "Paste is limited to 64 KiB of text in this build.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text != null && listener != null) {
            cancelComposition();
            listener.paste(text.toString());
        }
    }

    public void showKeyboard() {
        requestFocus();
        InputMethodManager manager = getContext().getSystemService(InputMethodManager.class);
        if (manager != null) manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.TextView");
        info.setAccessibilityDataSensitive(true);
        info.setMultiLine(true);
        info.setText(screenText());
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE);
    }

    /** Text from the last complete frame, bounded for platform accessibility consumers. */
    public String screenText() {
        NeovimGrid.Frame frame = grid.frame();
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < frame.rows && text.length() < 32768; row++) {
            int end = frame.columns;
            while (end > 0 && frame.text(row, end - 1).isBlank()) end--;
            if (end == 0) continue;
            if (text.length() > 0) text.append('\n');
            for (int column = 0; column < end && text.length() < 32768; column++) {
                text.append(frame.text(row, column));
            }
        }
        return text.toString();
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_PASTE) { pasteClipboard(); return true; }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        info.initialSelStart = info.initialSelEnd = 0;
        composition = "";
        final int generation = ++connectionGeneration;
        return new BaseInputConnection(this, false) {
            private boolean closed;
            private boolean active() { return !closed && generation == connectionGeneration && isAttachedToWindow(); }
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                if (!active()) return false;
                composition = "";
                if (text != null && listener != null) listener.input(literal(text.toString()));
                invalidate();
                return true;
            }
            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (!active()) return false;
                composition = text == null ? "" : text.subSequence(0, Math.min(4096, text.length())).toString();
                invalidate();
                return true;
            }
            @Override public boolean finishComposingText() {
                if (!active()) return false;
                return commitText(composition, 1);
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (!active()) return false;
                if (!composition.isEmpty()) {
                    int count = composition.codePointCount(0, composition.length());
                    composition = composition.substring(0, composition.offsetByCodePoints(0, Math.max(0, count - Math.max(0, before))));
                    invalidate();
                } else if (listener != null) {
                    listener.input("<BS>".repeat(Math.max(0, Math.min(256, before))) + "<Del>".repeat(Math.max(0, Math.min(256, after))));
                }
                return true;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) { return deleteSurroundingText(before, after); }
            @Override public CharSequence getTextBeforeCursor(int length, int flags) {
                return composition.substring(Math.max(0, composition.length() - Math.max(0, length)));
            }
            @Override public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
            @Override public CharSequence getSelectedText(int flags) { return ""; }
            @Override public boolean sendKeyEvent(KeyEvent event) { return active() && dispatchKeyEvent(event); }
            @Override public boolean performEditorAction(int action) {
                if (!active()) return false;
                if (listener != null) listener.input("<CR>");
                return true;
            }
            @Override public void closeConnection() {
                closed = true;
                if (generation == connectionGeneration) { composition = ""; invalidate(); }
                super.closeConnection();
            }
        };
    }

    static String literal(String text) { return text.replace("<", "<LT>"); }

    private void cancelComposition() {
        if (composition.isEmpty()) return;
        composition = "";
        connectionGeneration++;
        InputMethodManager manager = getContext().getSystemService(InputMethodManager.class);
        if (manager != null) manager.restartInput(this);
        invalidate();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed() && event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_V) {
            pasteClipboard();
            return true;
        }
        String special = switch (keyCode) {
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
        if (special != null) {
            sendKey("<" + modifiers(event, true) + special + ">");
            return true;
        }
        if (KeyEvent.isModifierKey(keyCode)) return true;
        // AltGr is text input; Ctrl/left Alt/Super encode Neovim modifiers.
        boolean altGr = (event.getMetaState() & KeyEvent.META_ALT_RIGHT_ON) != 0;
        boolean modified = !altGr && (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed());
        int meta = event.getMetaState();
        if (modified) meta &= ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK);
        int unicode = event.getUnicodeChar(meta);
        if ((unicode & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            pendingAccent = unicode & KeyCharacterMap.COMBINING_ACCENT_MASK;
            return true;
        }
        if (unicode == 0) return super.onKeyDown(keyCode, event);
        if (pendingAccent != 0) {
            int combined = KeyCharacterMap.getDeadChar(pendingAccent, unicode);
            if (combined != 0) unicode = combined;
            else sendKey(literal(new String(Character.toChars(pendingAccent))));
            pendingAccent = 0;
        }
        String text = new String(Character.toChars(unicode));
        if (modified) {
            String key = unicode == ' ' ? "Space" : unicode == '<' ? "LT" : text;
            sendKey("<" + modifiers(event, false) + key + ">");
        } else sendKey(literal(text));
        return true;
    }

    private static String modifiers(KeyEvent event, boolean shift) {
        return (event.isCtrlPressed() ? "C-" : "") + (event.isAltPressed() ? "A-" : "")
                + (event.isMetaPressed() ? "D-" : "") + (shift && event.isShiftPressed() ? "S-" : "");
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyUp(keyCode, event);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int row = Math.max(0, Math.min(grid.frame().rows - 1, (int) ((event.getY() - gridTop()) / cellHeight)));
        int column = Math.max(0, Math.min(grid.frame().columns - 1, (int) (event.getX() / cellWidth)));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                requestFocus();
                cancelComposition();
                pressedButton = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 ? "right"
                        : (event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0 ? "middle" : "left";
                pressedRow = row;
                pressedColumn = column;
                if (listener != null) listener.mouse(pressedButton, "press", "", row, column);
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (pressedButton != null && (row != pressedRow || column != pressedColumn)) {
                    pressedRow = row;
                    pressedColumn = column;
                    if (listener != null) listener.mouse(pressedButton, "drag", "", row, column);
                }
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedButton != null && listener != null) listener.mouse(pressedButton, "release", "", row, column);
                pressedButton = null;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { performClick(); showKeyboard(); }
                return true;
            }
            default -> { return true; }
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL || listener == null) return super.onGenericMotionEvent(event);
        wheelRemainder += event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        int ticks = Math.max(-32, Math.min(32, (int) wheelRemainder));
        wheelRemainder -= ticks;
        int row = Math.max(0, Math.min(grid.frame().rows - 1, (int) ((event.getY() - gridTop()) / cellHeight)));
        int column = Math.max(0, Math.min(grid.frame().columns - 1, (int) (event.getX() / cellWidth)));
        for (int i = 0; i < Math.abs(ticks); i++) listener.mouse("wheel", ticks > 0 ? "up" : "down", "", row, column);
        return true;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (!focused) { releaseMouse(); pendingAccent = 0; composition = ""; connectionGeneration++; invalidate(); }
    }
    private void releaseMouse() {
        if (pressedButton != null && listener != null) listener.mouse(pressedButton, "release", "", pressedRow, pressedColumn);
        pressedButton = null;
    }

    @Override protected void onDetachedFromWindow() {
        releaseMouse();
        connectionGeneration++;
        composition = "";
        pendingAccent = 0;
        pressedButton = null;
        super.onDetachedFromWindow();
    }
}
