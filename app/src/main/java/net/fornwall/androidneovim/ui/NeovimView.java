package net.fornwall.androidneovim.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;
import android.widget.Toast;
import java.util.Arrays;
import java.util.List;

/** A direct Neovim grid UI; there is no terminal emulator or escape-sequence layer. */
public final class NeovimView extends View {
    public interface Listener {
        void input(String keys);
        void paste(String text);
        void mouse(String button, String action, String modifiers, int row, int column);
        void resize(int columns, int rows);
    }
    public interface ModifierListener { void onModifiersChanged(boolean ctrl, boolean alt); }

    private static final String ASCII = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";
    private static final int TOUCH_IDLE = 0, TOUCH_PENDING = 1, TOUCH_SCROLL = 2, TOUCH_SELECT = 3, TOUCH_PINCH = 4;
    // Neovim's default 'mousescroll' moves 3 lines or 6 columns per wheel event.
    private static final int WHEEL_LINES = 3, WHEEL_COLUMNS = 6;
    private static final int ADVANCE_BITS = 9;
    private final NeovimGrid grid = new NeovimGrid();
    private final Paint[] textPaints = new Paint[4];
    private final int[] maximumRun = new int[4];
    private final float[] textScales = new float[4];
    private final float[] advanceErrors = new float[4];
    // Per-style advances of drawn clusters, direct-mapped by text and cleared when the font changes.
    private final String[][] advanceKeys = new String[4][1 << ADVANCE_BITS];
    private final float[][] advanceValues = new float[4][1 << ADVANCE_BITS];
    private final Paint fill = new Paint();
    private final Paint curl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curlPath = new Path();
    private char[] run = new char[256];
    private float fontSize;
    private float cellWidth;
    private float cellHeight;
    private float baseline;
    private Listener listener;
    private ModifierListener modifierListener;
    private boolean stickyCtrl;
    private boolean stickyAlt;
    private int columns = 80;
    private int rows = 24;
    private int connectionGeneration;
    private int pendingAccent;
    private String composition = "";
    private String pressedButton;
    private int pressedRow;
    private int pressedColumn;
    private float wheelRemainder;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private final int touchSlop;
    private final int minimumFling;
    private final int maximumFling;
    private final Runnable longPress = this::startSelection;
    private final Runnable flingStep = this::flingStep;
    private VelocityTracker velocity;
    private int touchState;
    private boolean horizontalScroll;
    private float downX, downY, lastX, lastY, scrollX, scrollY;
    private int touchRow, touchColumn, flingX, flingY;

    public NeovimView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setContentDescription("Neovim editor");
        for (int style = 0; style < textPaints.length; style++) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, style));
            // Neovim owns cell placement, so batched runs must not kern or ligate.
            paint.setFontFeatureSettings("'kern' 0, 'liga' 0, 'clig' 0, 'calt' 0");
            textPaints[style] = paint;
        }
        curl.setStyle(Paint.Style.STROKE);
        curl.setStrokeWidth(Math.max(1, getResources().getDisplayMetrics().density));
        fontSize = clampFontSize(context.getSharedPreferences("editor", Context.MODE_PRIVATE).getFloat("font_size_sp", 14));
        applyFontSize();
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFling = configuration.getScaledMinimumFlingVelocity();
        maximumFling = configuration.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                setFontSize(fontSize * detector.getScaleFactor());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                getContext().getSharedPreferences("editor", Context.MODE_PRIVATE).edit().putFloat("font_size_sp", fontSize).apply();
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
    }

    private static float clampFontSize(float size) { return Float.isNaN(size) ? 14 : Math.max(8, Math.min(32, size)); }

    /** Sets the font size in sp, clamped to 8–32, and resizes the Neovim grid to fit. */
    public void setFontSize(float size) {
        size = clampFontSize(size);
        if (size == fontSize) return;
        fontSize = size;
        applyFontSize();
        updateGridSize();
        invalidate();
    }
    public float fontSize() { return fontSize; }

    private void applyFontSize() {
        float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, getResources().getDisplayMetrics());
        for (Paint paint : textPaints) { paint.setTextSize(textSize); paint.setTextScaleX(1); }
        // Keep fractional advances, matching android-ssh's TerminalMetrics approach.
        cellWidth = textPaints[0].measureText("MMMMMMMMMM") / 10;
        Paint.FontMetrics metrics = textPaints[0].getFontMetrics();
        cellHeight = (float) Math.ceil(metrics.descent - metrics.ascent);
        baseline = cellHeight - metrics.descent;
        // Styles may use other faces (italic is narrower) and advances are quantized, so each style is scaled
        // to the cell; ASCII runs are then bounded so that accumulated advance error stays below 1/4 pixel.
        float[] advances = new float[ASCII.length()];
        for (int style = 0; style < textPaints.length; style++) {
            Paint paint = textPaints[style];
            paint.getTextWidths(ASCII, advances);
            textScales[style] = cellWidth / advances['M' - ' '];
            paint.setTextScaleX(textScales[style]);
            paint.getTextWidths(ASCII, advances);
            float error = 0;
            for (float advance : advances) error = Math.max(error, Math.abs(advance - cellWidth));
            maximumRun[style] = error < 1e-4f ? Integer.MAX_VALUE : (int) (0.25f / error);
            advanceErrors[style] = Math.max(error, 1e-4f);
            Arrays.fill(advanceKeys[style], null);
        }
    }

    float cellWidth() { return cellWidth; }
    float cellHeight() { return cellHeight; }
    int maximumRun(int style) { return maximumRun[style]; }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null && getWidth() > 0 && getHeight() > 0) listener.resize(columns, rows);
    }
    public int columns() { return columns; }
    public int rows() { return rows; }

    public void handleRedraw(List<?> events) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Redraw must run on main thread");
        if (!grid.handleRedraw(events)) return;
        if (run.length < grid.frame().columns) run = new char[grid.frame().columns];
        postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { updateGridSize(); }

    private void updateGridSize() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int newColumns = Math.max(1, (int) (getWidth() / cellWidth));
        int newRows = Math.max(1, (int) (getHeight() / cellHeight));
        if (newColumns != columns || newRows != rows) {
            columns = newColumns;
            rows = newRows;
            if (listener != null) listener.resize(columns, rows);
        }
    }

    private float gridTop() { return Math.max(0, (int) ((getHeight() - grid.frame().rows * cellHeight) / 2)); }
    private int row(float y) { return Math.max(0, Math.min(grid.frame().rows - 1, (int) ((y - gridTop()) / cellHeight))); }
    private int column(float x) { return Math.max(0, Math.min(grid.frame().columns - 1, (int) (x / cellWidth))); }

    @Override protected void onDraw(Canvas canvas) {
        NeovimGrid.Frame frame = grid.frame();
        canvas.drawColor(frame.background);
        float top = gridTop();
        int visibleRows = Math.min(frame.rows, (int) Math.ceil(getHeight() / cellHeight));
        int visibleColumns = Math.min(frame.columns, (int) Math.ceil(getWidth() / cellWidth));
        // Backgrounds precede all glyphs, so the continuation cell cannot erase a wide glyph.
        for (int row = 0; row < visibleRows; row++) {
            float y = top + row * cellHeight;
            for (int start = 0, end; start < visibleColumns; start = end) {
                int color = frame.background(frame.highlight(row, start));
                for (end = start + 1; end < visibleColumns && frame.background(frame.highlight(row, end)) == color; end++) { }
                if (color == frame.background) continue;
                fill.setColor(color);
                canvas.drawRect(start * cellWidth, y, end * cellWidth, y + cellHeight, fill);
            }
        }
        for (int row = 0; row < visibleRows; row++) drawRow(canvas, frame, row, top + row * cellHeight, visibleColumns);
        if (!frame.busy && frame.cursorRow >= 0 && frame.cursorRow < visibleRows
                && frame.cursorColumn >= 0 && frame.cursorColumn < visibleColumns) {
            float x = frame.cursorColumn * cellWidth;
            float y = top + frame.cursorRow * cellHeight;
            fill.setColor(frame.cursorBackground());
            switch (frame.cursorShape) {
                case "vertical" -> canvas.drawRect(x, y, x + Math.max(1, cellWidth * frame.cursorPercentage / 100), y + cellHeight, fill);
                case "horizontal" -> canvas.drawRect(x, y + cellHeight * (100 - frame.cursorPercentage) / 100, x + cellWidth, y + cellHeight, fill);
                default -> {
                    float right = x + cellSpan(frame, frame.cursorRow, frame.cursorColumn) * cellWidth;
                    canvas.drawRect(x, y, right, y + cellHeight, fill);
                    int style = style(frame.highlight(frame.cursorRow, frame.cursorColumn));
                    textPaints[style].setColor(frame.cursorForeground());
                    // Only the block shows the cursor colors; overhanging ink keeps the cell's own color.
                    int saved = canvas.save();
                    canvas.clipRect(x, y, right, y + cellHeight);
                    drawCell(canvas, frame, frame.cursorRow, frame.cursorColumn, y, style);
                    canvas.restoreToCount(saved);
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

    private static int style(NeovimGrid.Highlight highlight) { return (highlight.bold ? Typeface.BOLD : 0) | (highlight.italic ? Typeface.ITALIC : 0); }
    private static int cellSpan(NeovimGrid.Frame frame, int row, int column) {
        return column + 1 < frame.columns && frame.text(row, column + 1).isEmpty() ? 2 : 1;
    }

    /**
     * Draws each equally highlighted span: printable ASCII, and single characters before U+0590 that the
     * font draws at the cell width, as text runs; other clusters are fitted to their cells one by one.
     */
    private void drawRow(Canvas canvas, NeovimGrid.Frame frame, int row, float y, int visibleColumns) {
        for (int start = 0, end; start < visibleColumns; start = end) {
            int attribute = frame.attribute(row, start);
            for (end = start + 1; end < visibleColumns && frame.attribute(row, end) == attribute; end++) { }
            NeovimGrid.Highlight highlight = frame.highlight(attribute);
            drawDecorations(canvas, frame, highlight, start * cellWidth, end * cellWidth, y);
            int style = style(highlight);
            Paint paint = textPaints[style];
            paint.setColor(frame.foreground(highlight));
            int limit = maximumRun[style];
            int runStart = -1, length = 0, trimmed = 0;
            for (int column = start; column < end; column++) {
                String text = frame.text(row, column);
                char c = text.length() == 1 ? text.charAt(0) : 0;
                if (limit > 1 && c >= ' ' && (c < 0x7f || (c >= 0xa0 && c < 0x590
                        && Math.abs(advance(style, text) - cellWidth) <= advanceErrors[style]))) {
                    if (length == limit) {
                        drawRun(canvas, runStart, trimmed, y, paint);
                        runStart = -1;
                        length = trimmed = 0;
                    }
                    if (runStart < 0) {
                        if (c == ' ') continue;
                        runStart = column;
                    }
                    run[length++] = c;
                    if (c != ' ') trimmed = length;
                    continue;
                }
                drawRun(canvas, runStart, trimmed, y, paint);
                runStart = -1;
                length = trimmed = 0;
                drawCell(canvas, frame, row, column, y, style);
            }
            drawRun(canvas, runStart, trimmed, y, paint);
        }
    }

    private void drawRun(Canvas canvas, int column, int length, float y, Paint paint) {
        if (length > 0) canvas.drawTextRun(run, 0, length, 0, length, column * cellWidth, y + baseline, false, paint);
    }

    private void drawDecorations(Canvas canvas, NeovimGrid.Frame frame, NeovimGrid.Highlight highlight, float left, float right, float y) {
        if (!highlight.underline && !highlight.undercurl && !highlight.strikethrough) return;
        fill.setColor(frame.decoration(highlight));
        if (highlight.underline) canvas.drawRect(left, y + cellHeight - 1, right, y + cellHeight, fill);
        if (highlight.strikethrough) canvas.drawRect(left, y + cellHeight / 2, right, y + cellHeight / 2 + 1, fill);
        if (highlight.undercurl) {
            float amplitude = Math.max(1, cellHeight / 16), half = cellWidth / 2;
            float middle = y + cellHeight - amplitude - curl.getStrokeWidth() / 2;
            curlPath.rewind();
            curlPath.moveTo(left, middle);
            for (float x = left, sign = -1; x < right - 0.5f; x += half, sign = -sign) {
                curlPath.quadTo(x + half / 2, middle + 2 * sign * amplitude, x + half, middle);
            }
            curl.setColor(frame.special(highlight));
            canvas.drawPath(curlPath, curl);
        }
    }

    /**
     * Draws a cluster centred in its cell span, or narrowed to fit it: Neovim counts fallback symbols
     * (arrows, box drawing, dingbats) as one cell although they are wider than the monospace advance.
     */
    private void drawCell(Canvas canvas, NeovimGrid.Frame frame, int row, int column, float y, int style) {
        String text = frame.text(row, column);
        if (text.isBlank()) return;
        Paint paint = textPaints[style];
        float x = column * cellWidth, span = cellSpan(frame, row, column) * cellWidth, advance = advance(style, text);
        if (advance > span) paint.setTextScaleX(textScales[style] * span / advance);
        else x += (span - advance) / 2;
        // Explicit LTR shaping follows TerminalRowPainter; Neovim owns cell order.
        canvas.drawTextRun(text, 0, text.length(), 0, text.length(), x, y + baseline, false, paint);
        paint.setTextScaleX(textScales[style]);
    }

    private float advance(int style, String text) {
        int slot = (text.hashCode() * 0x9e3779b9) >>> (32 - ADVANCE_BITS);
        if (!text.equals(advanceKeys[style][slot])) {
            advanceKeys[style][slot] = text;
            advanceValues[style][slot] = textPaints[style].getRunAdvance(text, 0, text.length(), 0, text.length(), false, text.length());
        }
        return advanceValues[style][slot];
    }

    public void setModifierListener(ModifierListener listener) { modifierListener = listener; }
    public void toggleCtrl() { stickyCtrl = !stickyCtrl; notifyModifiers(); }
    public void toggleAlt() { stickyAlt = !stickyAlt; notifyModifiers(); }
    private void notifyModifiers() { if (modifierListener != null) modifierListener.onModifiersChanged(stickyCtrl, stickyAlt); }
    private void clearModifiers() {
        if (!stickyCtrl && !stickyAlt) return;
        stickyCtrl = stickyAlt = false;
        notifyModifiers();
    }

    /** Sends Neovim key notation. Active sticky modifiers apply to a single key, then clear. */
    public void sendKey(String keys) {
        cancelComposition();
        input(keys);
    }

    private void input(String keys) {
        if (stickyCtrl || stickyAlt) {
            keys = modified(keys, stickyCtrl, stickyAlt);
            clearModifiers();
        }
        if (listener != null) listener.input(keys);
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
                if (text != null && text.length() > 0) input(literal(text.toString()));
                invalidate();
                return true;
            }
            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (!active()) return false;
                // Soft keyboards often compose single letters; a sticky modifier makes one a key now.
                if ((stickyCtrl || stickyAlt) && composition.isEmpty() && text != null && text.length() > 0
                        && Character.codePointCount(text, 0, text.length()) == 1) {
                    input(literal(text.toString()));
                    resetIme();
                    return true;
                }
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
                input("<CR>");
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
        if (!composition.isEmpty()) resetIme();
    }

    private void resetIme() {
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
        cancelComposition();
        if (pendingAccent != 0) {
            int combined = KeyCharacterMap.getDeadChar(pendingAccent, unicode);
            if (combined != 0) unicode = combined;
            else if (listener != null) listener.input(literal(new String(Character.toChars(pendingAccent))));
            pendingAccent = 0;
        }
        String text = new String(Character.toChars(unicode));
        if (modified) {
            String key = unicode == ' ' ? "Space" : unicode == '<' ? "LT" : text;
            input("<" + modifiers(event, false) + key + ">");
        } else input(literal(text));
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
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouseEvent(event);
        scaleDetector.onTouchEvent(event);
        if (velocity != null) velocity.addMovement(event);
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                requestFocus();
                cancelComposition();
                stopFling();
                touchState = TOUCH_PENDING;
                downX = lastX = x;
                downY = lastY = y;
                scrollX = scrollY = 0;
                touchRow = row(y);
                touchColumn = column(x);
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(event);
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            }
            case MotionEvent.ACTION_POINTER_DOWN -> {
                // Pinch owns the rest of the gesture: no tap, scroll or selection.
                removeCallbacks(longPress);
                releaseMouse();
                touchState = TOUCH_PINCH;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (touchState == TOUCH_PENDING && Math.hypot(x - downX, y - downY) > touchSlop) {
                    removeCallbacks(longPress);
                    touchState = TOUCH_SCROLL;
                    horizontalScroll = Math.abs(x - downX) > Math.abs(y - downY);
                }
                if (touchState == TOUCH_SCROLL) {
                    scroll(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                } else if (touchState == TOUCH_SELECT) {
                    int row = row(y), column = column(x);
                    if (row != pressedRow || column != pressedColumn) {
                        pressedRow = row;
                        pressedColumn = column;
                        if (listener != null) listener.mouse("left", "drag", "", row, column);
                    }
                }
            }
            case MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress);
                if (touchState == TOUCH_PENDING) {
                    if (listener != null) {
                        listener.mouse("left", "press", "", touchRow, touchColumn);
                        listener.mouse("left", "release", "", touchRow, touchColumn);
                    }
                    performClick();
                    showKeyboard();
                } else if (touchState == TOUCH_SCROLL) {
                    velocity.computeCurrentVelocity(1000, maximumFling);
                    int vx = horizontalScroll ? (int) velocity.getXVelocity() : 0, vy = horizontalScroll ? 0 : (int) velocity.getYVelocity();
                    if (Math.abs(vx) + Math.abs(vy) > minimumFling) {
                        flingX = flingY = 0;
                        scroller.fling(0, 0, vx, vy, -1_000_000, 1_000_000, -1_000_000, 1_000_000);
                        postOnAnimation(flingStep);
                    }
                }
                releaseMouse();
                endTouch();
            }
            case MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress);
                releaseMouse();
                endTouch();
            }
            default -> { }
        }
        return true;
    }

    private void endTouch() {
        touchState = TOUCH_IDLE;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    private void startSelection() {
        if (touchState != TOUCH_PENDING) return;
        touchState = TOUCH_SELECT;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pressedButton = "left";
        pressedRow = touchRow;
        pressedColumn = touchColumn;
        if (listener != null) listener.mouse("left", "press", "", touchRow, touchColumn);
    }

    /** Converts finger movement to wheel events so content roughly follows the finger. */
    private void scroll(float dx, float dy) {
        if (horizontalScroll) {
            float step = WHEEL_COLUMNS * cellWidth;
            int ticks = (int) ((scrollX += dx) / step);
            scrollX -= ticks * step;
            wheel(ticks > 0 ? "left" : "right", Math.abs(ticks));
        } else {
            float step = WHEEL_LINES * cellHeight;
            int ticks = (int) ((scrollY += dy) / step);
            scrollY -= ticks * step;
            wheel(ticks > 0 ? "up" : "down", Math.abs(ticks));
        }
    }

    private void wheel(String direction, int ticks) {
        if (listener == null) return;
        for (int i = 0; i < Math.min(32, ticks); i++) listener.mouse("wheel", direction, "", touchRow, touchColumn);
    }

    private void flingStep() {
        if (!scroller.computeScrollOffset()) return;
        scroll(scroller.getCurrX() - flingX, scroller.getCurrY() - flingY);
        flingX = scroller.getCurrX();
        flingY = scroller.getCurrY();
        postOnAnimation(flingStep);
    }

    private void stopFling() {
        scroller.forceFinished(true);
        removeCallbacks(flingStep);
    }

    private boolean mouseEvent(MotionEvent event) {
        int row = row(event.getY()), column = column(event.getX());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                requestFocus();
                cancelComposition();
                stopFling();
                pressedButton = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 ? "right"
                        : (event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0 ? "middle" : "left";
                pressedRow = row;
                pressedColumn = column;
                if (listener != null) listener.mouse(pressedButton, "press", "", row, column);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (pressedButton != null && (row != pressedRow || column != pressedColumn)) {
                    pressedRow = row;
                    pressedColumn = column;
                    if (listener != null) listener.mouse(pressedButton, "drag", "", row, column);
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedButton != null && listener != null) listener.mouse(pressedButton, "release", "", row, column);
                pressedButton = null;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { performClick(); showKeyboard(); }
            }
            default -> { }
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_SCROLL || listener == null) return super.onGenericMotionEvent(event);
        wheelRemainder += event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        int ticks = Math.max(-32, Math.min(32, (int) wheelRemainder));
        wheelRemainder -= ticks;
        int row = row(event.getY()), column = column(event.getX());
        for (int i = 0; i < Math.abs(ticks); i++) listener.mouse("wheel", ticks > 0 ? "up" : "down", "", row, column);
        return true;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (!focused) { resetInput(); invalidate(); }
    }

    private void releaseMouse() {
        if (pressedButton != null && listener != null) listener.mouse(pressedButton, "release", "", pressedRow, pressedColumn);
        pressedButton = null;
    }

    private void resetInput() {
        removeCallbacks(longPress);
        stopFling();
        releaseMouse();
        endTouch();
        clearModifiers();
        pendingAccent = 0;
        composition = "";
        connectionGeneration++;
    }

    @Override protected void onDetachedFromWindow() {
        resetInput();
        super.onDetachedFromWindow();
    }
}
