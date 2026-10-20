package net.fornwall.androidneovim.ui;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.fornwall.androidneovim.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NeovimViewTest {
    private static final class InputLog implements NeovimView.Listener {
        final List<String> keys = new ArrayList<>();
        final List<String> mouse = new ArrayList<>();
        final List<String> pastes = new ArrayList<>();
        /** Keys, pastes and mouse events in order. */
        final List<String> events = new ArrayList<>();
        @Override public void input(String input) { keys.add(input); events.add(input); }
        @Override public void paste(String text) { pastes.add(text); events.add("paste " + text); }
        @Override public void mouse(String button, String action, String modifiers, int row, int column) {
            String event = button + " " + action + " " + row + "," + column;
            mouse.add(event);
            events.add(event);
        }
        @Override public void resize(int columns, int rows) { }
    }

    private static final class ResizeLog implements NeovimView.Listener {
        final List<String> sizes = new ArrayList<>();
        @Override public void input(String input) { }
        @Override public void paste(String text) { }
        @Override public void mouse(String button, String action, String modifiers, int row, int column) { }
        @Override public void resize(int columns, int rows) { sizes.add(columns + "x" + rows); }
    }

    private interface AttachedTest { void run(Activity activity, NeovimView view, InputLog input); }

    /** Runs {@code test} on the main thread with a focused view in a started activity, then detaches it. */
    private static void attached(AttachedTest test) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.runOnMainSync(() -> {
                NeovimView view = new NeovimView(activity);
                InputLog input = new InputLog();
                activity.addContentView(view, new ViewGroup.LayoutParams(200, 200));
                view.setListener(input);
                view.requestFocus();
                test.run(activity, view, input);
                if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void hardwareKeysUseNeovimNotationAndLiteralLessThan() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = new NeovimView(instrumentation.getTargetContext());
            InputLog input = new InputLog();
            view.setListener(input);
            view.onKeyDown(KeyEvent.KEYCODE_A, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
            view.onKeyDown(KeyEvent.KEYCODE_TAB, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON));
            view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
            assertEquals(List.of("<C-a>", "<S-Tab>", "<Esc>"), input.keys);
        });
    }

    @Test public void stickyModifiersApplyToOneKeyThenClear() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = new NeovimView(instrumentation.getTargetContext());
            InputLog input = new InputLog();
            List<String> changes = new ArrayList<>();
            view.setListener(input);
            view.setModifierListener((ctrl, alt) -> changes.add(ctrl + "," + alt));
            view.sendKey("i");
            view.toggleCtrl();
            view.sendKey("<Left>");
            view.sendKey("<Left>");
            view.toggleCtrl();
            view.toggleCtrl();
            view.toggleAlt();
            view.toggleCtrl();
            view.sendKey(":");
            view.toggleAlt();
            view.sendKey("<S-Tab>");
            view.toggleCtrl();
            view.sendKey(" ");
            view.toggleCtrl();
            view.sendKey("<LT>");
            view.toggleCtrl();
            view.sendKey("abc");
            view.toggleAlt();
            view.onKeyDown(KeyEvent.KEYCODE_A, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
            view.toggleCtrl();
            view.onKeyDown(KeyEvent.KEYCODE_W, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W));
            assertEquals(List.of("i", "<C-Left>", "<Left>", "<C-A-:>", "<A-S-Tab>", "<C-Space>", "<C-LT>", "abc", "<C-A-a>", "<C-w>"), input.keys);
            assertEquals(List.of("true,false", "false,false", "true,false", "false,false", "false,true", "true,true", "false,false",
                    "false,true", "false,false", "true,false", "false,false", "true,false", "false,false", "true,false", "false,false",
                    "false,true", "false,false", "true,false", "false,false"), changes);
        });
    }

    private static MotionEvent touch(long downTime, long time, int action, float x, float y) {
        return MotionEvent.obtain(downTime, time, action, x, y, 0);
    }

    private static NeovimView sizedView(android.content.Context context) {
        NeovimView view = new NeovimView(context);
        view.handleRedraw(List.of(List.of("grid_resize", List.of(1, 40, 20)), List.of("flush", List.of())));
        return view;
    }

    @Test public void tapClicksAndVerticalDragScrollsWithoutDragging() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = sizedView(instrumentation.getTargetContext());
            InputLog input = new InputLog();
            view.setListener(input);
            for (int style = 0; style < 4; style++) assertTrue("style " + style, view.maximumRun(style) >= 40);
            float w = view.cellWidth(), h = view.cellHeight();
            view.onTouchEvent(touch(0, 0, MotionEvent.ACTION_DOWN, 3.5f * w, 2.5f * h));
            view.onTouchEvent(touch(0, 50, MotionEvent.ACTION_UP, 3.5f * w, 2.5f * h));
            assertEquals(List.of("left press 2,3", "left release 2,3"), input.mouse);
            input.mouse.clear();
            view.onTouchEvent(touch(1000, 1000, MotionEvent.ACTION_DOWN, 5.5f * w, 15.5f * h));
            for (int i = 1; i <= 13; i++) view.onTouchEvent(touch(1000, 1000 + 200 * i, MotionEvent.ACTION_MOVE, 5.5f * w, (15.5f - i) * h));
            view.onTouchEvent(touch(1000, 5000, MotionEvent.ACTION_UP, 5.5f * w, 2.5f * h));
            assertEquals(List.of("wheel down 15,5", "wheel down 15,5", "wheel down 15,5", "wheel down 15,5"), input.mouse);
            input.mouse.clear();
            view.onTouchEvent(touch(6000, 6000, MotionEvent.ACTION_DOWN, 20.5f * w, 5.5f * h));
            for (int i = 1; i <= 13; i++) view.onTouchEvent(touch(6000, 6000 + 200 * i, MotionEvent.ACTION_MOVE, (20.5f + i) * w, 5.5f * h));
            view.onTouchEvent(touch(6000, 10000, MotionEvent.ACTION_UP, 33.5f * w, 5.5f * h));
            assertEquals(List.of("wheel left 5,20", "wheel left 5,20"), input.mouse);
        });
    }

    @Test public void mouseKeepsButtonsAndPinchZoomsWithoutMouseInput() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var preferences = instrumentation.getTargetContext().getSharedPreferences("editor", android.content.Context.MODE_PRIVATE);
        boolean hadSize = preferences.contains("font_size_sp");
        float savedSize = preferences.getFloat("font_size_sp", 14);
        try {
            instrumentation.runOnMainSync(() -> {
                NeovimView view = sizedView(instrumentation.getTargetContext());
                InputLog input = new InputLog();
                view.setListener(input);
                float w = view.cellWidth(), h = view.cellHeight();
                for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                    var properties = new MotionEvent.PointerProperties();
                    properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
                    var coordinates = new MotionEvent.PointerCoords();
                    coordinates.x = (action == MotionEvent.ACTION_DOWN ? 1.5f : 4.5f) * w;
                    coordinates.y = 1.5f * h;
                    MotionEvent event = MotionEvent.obtain(0, 10, action, 1, new MotionEvent.PointerProperties[] {properties},
                            new MotionEvent.PointerCoords[] {coordinates}, 0, MotionEvent.BUTTON_SECONDARY, 1, 1, 0, 0,
                            InputDevice.SOURCE_MOUSE, 0);
                    view.onTouchEvent(event);
                    event.recycle();
                }
                assertEquals(List.of("right press 1,1", "right drag 1,4", "right release 1,4"), input.mouse);
                input.mouse.clear();
                float before = view.fontSize();
                long t = 0;
                view.onTouchEvent(pinch(t, MotionEvent.ACTION_DOWN, 1, 300));
                view.onTouchEvent(pinch(t += 10, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 300));
                for (int i = 1; i <= 10; i++) view.onTouchEvent(pinch(t += 20, MotionEvent.ACTION_MOVE, 2, 300 + 40 * i));
                view.onTouchEvent(pinch(t += 10, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 700));
                view.onTouchEvent(pinch(t + 10, MotionEvent.ACTION_UP, 1, 700));
                assertTrue(view.fontSize() > before);
                assertTrue(view.fontSize() <= 32);
                assertTrue(input.mouse.isEmpty());
            });
        } finally {
            if (hadSize) preferences.edit().putFloat("font_size_sp", savedSize).commit();
            else preferences.edit().remove("font_size_sp").commit();
        }
    }

    /** Two fingers {@code span} pixels apart, centred at (500, 500). */
    private static MotionEvent pinch(long time, int action, int pointers, float span) {
        var properties = new MotionEvent.PointerProperties[pointers];
        var coordinates = new MotionEvent.PointerCoords[pointers];
        for (int i = 0; i < pointers; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = 500 + (i == 0 ? -span : span) / 2;
            coordinates[i].y = 500;
        }
        return MotionEvent.obtain(0, time, action, pointers, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    @Test public void longPressDragSelectsAndCancelReleases() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            InputLog input = new InputLog();
            NeovimView[] holder = new NeovimView[1];
            instrumentation.runOnMainSync(() -> {
                NeovimView view = sizedView(activity);
                holder[0] = view;
                activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            });
            instrumentation.waitForIdleSync();
            NeovimView view = holder[0];
            float w = view.cellWidth(), h = view.cellHeight();
            instrumentation.runOnMainSync(() -> {
                view.setListener(input);
                view.handleRedraw(List.of(List.of("grid_resize", List.of(1, 40, 20)), List.of("flush", List.of())));
                float top = Math.max(0, (int) ((view.getHeight() - 20 * h) / 2));
                long now = android.os.SystemClock.uptimeMillis();
                view.onTouchEvent(touch(now, now, MotionEvent.ACTION_DOWN, 2.5f * w, top + 1.5f * h));
            });
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 300);
            instrumentation.runOnMainSync(() -> {
                float top = Math.max(0, (int) ((view.getHeight() - 20 * h) / 2));
                long now = android.os.SystemClock.uptimeMillis();
                view.onTouchEvent(touch(now, now, MotionEvent.ACTION_MOVE, 6.5f * w, top + 3.5f * h));
                view.onTouchEvent(touch(now, now + 10, MotionEvent.ACTION_CANCEL, 6.5f * w, top + 3.5f * h));
                assertEquals(List.of("left press 1,2", "left drag 3,6", "left release 3,6"), input.mouse);
                ((ViewGroup) view.getParent()).removeView(view);
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void stickyModifiersApplyToSingleImeCommits() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.runOnMainSync(() -> {
                NeovimView view = new NeovimView(activity);
                InputLog input = new InputLog();
                List<String> changes = new ArrayList<>();
                activity.addContentView(view, new ViewGroup.LayoutParams(200, 200));
                view.setListener(input);
                view.setModifierListener((ctrl, alt) -> changes.add(ctrl + "," + alt));
                view.requestFocus();
                InputConnection connection = view.onCreateInputConnection(new EditorInfo());
                view.toggleCtrl();
                assertTrue(connection.finishComposingText());
                assertTrue(connection.commitText("w", 1));
                assertTrue(connection.commitText("w", 1));
                view.toggleCtrl();
                assertTrue(connection.commitText("hi", 1));
                view.toggleAlt();
                assertTrue(connection.commitText("<", 1));
                view.toggleCtrl();
                // A composed letter becomes the modified key immediately, and the stale connection closes.
                assertTrue(connection.setComposingText("d", 1));
                assertFalse(connection.commitText("d", 1));
                connection = view.onCreateInputConnection(new EditorInfo());
                view.toggleCtrl();
                ((ViewGroup) view.getParent()).removeView(view);
                assertEquals(List.of("<C-w>", "w", "hi", "<A-LT>", "<C-d>"), input.keys);
                assertEquals(List.of("true,false", "false,false", "true,false", "false,false", "false,true", "false,false",
                        "true,false", "false,false", "true,false", "false,false"), changes);
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void screenTextAndAccessibilityObserveOnlyFlushedFrames() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = new NeovimView(instrumentation.getTargetContext());
            view.handleRedraw(List.of(List.of("grid_resize", List.of(1, 6, 2)),
                    List.of("grid_line", List.of(1, 0, 0,
                            List.of(List.of("界", 0), List.of(""), List.of("!")), false))));
            assertEquals("", view.screenText());
            view.handleRedraw(List.of(List.of("flush", List.of())));
            assertEquals("界!", view.screenText());
            AccessibilityNodeInfo info = new AccessibilityNodeInfo();
            view.onInitializeAccessibilityNodeInfo(info);
            assertEquals("界!", info.getText().toString());
            assertTrue(info.isAccessibilityDataSensitive());
        });
    }

    @Test public void imeCompositionStaysLocalAndOldConnectionsCannotSend() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.runOnMainSync(() -> {
                NeovimView view = new NeovimView(activity);
                InputLog input = new InputLog();
                activity.addContentView(view, new ViewGroup.LayoutParams(200, 200));
                view.setListener(input);
                view.requestFocus();
                InputConnection first = view.onCreateInputConnection(new EditorInfo());
                assertTrue(first.setComposingText("draft", 1));
                assertTrue(input.keys.isEmpty());
                assertTrue(first.commitText("a<b", 1));
                assertEquals(List.of("a<LT>b"), input.keys);
                InputConnection second = view.onCreateInputConnection(new EditorInfo());
                assertFalse(first.commitText("stale", 1));
                assertTrue(second.setComposingText("文", 1));
                assertTrue(second.finishComposingText());
                assertEquals(List.of("a<LT>b", "文"), input.keys);
                ((ViewGroup) view.getParent()).removeView(view);
                assertFalse(second.commitText("detached", 1));
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    /** Draws one flushed white-on-black row, after the given events, into a bitmap; busy hides the cursor. */
    private static Bitmap render(NeovimView view, int columns, List<?> cells, List<?>... events) {
        List<Object> redraw = new ArrayList<>(List.of(events));
        redraw.addAll(List.of(List.of("default_colors_set", List.of(0xffffff, 0, 0xff0000, 0, 0)),
                List.of("grid_resize", List.of(1, columns, 1)), List.of("grid_line", List.of(1, 0, 0, cells, false)),
                List.of("busy_start", List.of()), List.of("flush", List.of())));
        view.handleRedraw(redraw);
        int width = (int) Math.ceil(columns * view.cellWidth()), height = (int) view.cellHeight();
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static boolean ink(Bitmap bitmap, int x, int y) { return Color.green(bitmap.getPixel(x, y)) > 96; }

    private static boolean ink(Bitmap bitmap, int y, int left, int right) {
        for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) if (ink(bitmap, x, y)) return true;
        return false;
    }

    /** The number of pixel rows with ink in columns [left, right). */
    private static int inkRows(Bitmap bitmap, int left, int right) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) if (ink(bitmap, y, left, right)) count++;
        return count;
    }

    /** The leftmost and rightmost columns with ink. */
    private static int[] inkColumns(Bitmap bitmap) {
        int first = bitmap.getWidth(), last = -1;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            if (inkRows(bitmap, x, x + 1) > 0) { first = Math.min(first, x); last = x; }
        }
        return new int[] {first, last};
    }

    /** Rows [top, bottom) of each band of pixel rows with ink in columns [left, right). */
    private static List<List<Integer>> bands(Bitmap bitmap, int left, int right) {
        List<List<Integer>> bands = new ArrayList<>();
        for (int y = 0, top = -1; y <= bitmap.getHeight(); y++) {
            boolean ink = y < bitmap.getHeight() && ink(bitmap, y, left, right);
            if (ink && top < 0) top = y;
            if (!ink && top >= 0) { bands.add(List.of(top, y)); top = -1; }
        }
        return bands;
    }

    /** The number of separate ink runs on pixel row y in columns [left, right). */
    private static int runs(Bitmap bitmap, int y, int left, int right) {
        int count = 0;
        for (int x = left; x < right; x++) if (ink(bitmap, x, y) && (x == left || !ink(bitmap, x - 1, y))) count++;
        return count;
    }

    /** The bitmap as text, for assertion messages. */
    private static String art(Bitmap bitmap) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < bitmap.getHeight(); y++) {
            text.append('\n');
            for (int x = 0; x < bitmap.getWidth(); x++) text.append(ink(bitmap, x, y) ? '#' : '.');
        }
        return text.toString();
    }

    @Test public void wideFallbackGlyphsFitTheirCellsAndNarrowOnesAreCentred() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = new NeovimView(instrumentation.getTargetContext());
            float w = view.cellWidth();
            // The fallback arrow is wider than a cell: its head must be narrowed into the cell, not cut off.
            Bitmap arrow = render(view, 3, List.of(List.of(" ", 0), List.of("→"), List.of(" ")));
            int left = (int) Math.ceil(w), right = (int) Math.floor(2 * w), middle = (left + right) / 2;
            int[] arrowInk = inkColumns(arrow);
            assertTrue(art(arrow), inkRows(arrow, middle, right) > 2 * inkRows(arrow, left, middle));
            assertTrue(art(arrow), arrowInk[0] >= left - 1 && arrowInk[1] >= right - w / 4 && arrowInk[1] <= right);
            Bitmap bar = render(view, 3, List.of(List.of(" ", 0), List.of("│"), List.of(" ")));
            int[] barInk = inkColumns(bar);
            assertEquals(art(bar), 1.5f * w, (barInk[0] + barInk[1] + 1) / 2f, 1.5f);
            Bitmap wide = render(view, 4, List.of(List.of(" ", 0), List.of("界"), List.of(""), List.of(" ")));
            int[] wideInk = inkColumns(wide);
            assertEquals(art(wide), wideInk[0] - w, 3 * w - wideInk[1] - 1, 2.5f);
        });
    }

    @Test public void framesBeforeNeovimColorsFollowTheSystemTheme() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                var context = instrumentation.getTargetContext();
                Configuration configuration = new Configuration(context.getResources().getConfiguration());
                configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                NeovimView view = new NeovimView(context.createConfigurationContext(configuration));
                view.layout(0, 0, 200, 200);
                Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(bitmap));
                assertEquals(night == Configuration.UI_MODE_NIGHT_NO ? 0xffe0e2ea : 0xff14161b, bitmap.getPixel(190, 190));
            }
        });
    }

    private static List<?> highlight(int id, String attribute) {
        return List.of("hl_attr_define", List.of(id, Map.of(attribute, true), Map.of(), List.of()));
    }

    @Test public void decorationsUseFontMetricsAndTheTextColor() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = new NeovimView(instrumentation.getTargetContext());
            Bitmap bitmap = render(view, 10, List.of(List.of(" ", 1, 2), List.of(" ", 2, 2), List.of(" ", 3, 2),
                    List.of(" ", 4, 2), List.of(" ", 5, 2)), highlight(1, "underline"), highlight(2, "strikethrough"),
                    highlight(3, "underdouble"), highlight(4, "underdotted"), highlight(5, "underdashed"));
            int w = (int) view.cellWidth(), h = (int) view.cellHeight();
            // Without guisp the lines are white like the text, not Neovim's red default special color.
            List<List<Integer>> underline = bands(bitmap, 1, 2 * w - 1), strike = bands(bitmap, 2 * w + 1, 4 * w - 1);
            assertEquals(art(bitmap), 1, underline.size());
            assertEquals(art(bitmap), 1, strike.size());
            assertTrue(art(bitmap), underline.get(0).get(0) >= h / 2 && underline.get(0).get(1) <= h);
            assertTrue(art(bitmap), strike.get(0).get(1) <= underline.get(0).get(0) - 2 && strike.get(0).get(0) >= h / 4);
            assertEquals(art(bitmap), 2, bands(bitmap, 4 * w + 1, 6 * w - 1).size());
            int dotted = bands(bitmap, 6 * w + 1, 8 * w - 1).get(0).get(0), dashed = bands(bitmap, 8 * w, 10 * w).get(0).get(0);
            assertTrue(art(bitmap), runs(bitmap, dotted, 6 * w, 8 * w) > 4);
            assertEquals(art(bitmap), 2, runs(bitmap, dashed, 8 * w, 10 * w));
        });
    }

    @Test public void linespaceAndGuifontSizeApplyWithTheFlushedFrame() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = sizedView(instrumentation.getTargetContext());
            ResizeLog log = new ResizeLog();
            view.setListener(log);
            view.layout(0, 0, 1000, 1000);
            float height = view.cellHeight(), size = view.fontSize();
            int rows = view.rows();
            assertEquals(List.of(view.columns() + "x" + rows), log.sizes);
            view.handleRedraw(List.of(List.of("option_set", List.of("linespace", 4))));
            assertEquals(height, view.cellHeight(), 0);
            view.handleRedraw(List.of(List.of("flush", List.of())));
            float density = instrumentation.getTargetContext().getResources().getDisplayMetrics().density;
            assertEquals(height + Math.round(4 * density), view.cellHeight(), 0);
            assertTrue(view.rows() < rows);
            assertEquals(List.of(view.columns() + "x" + rows, view.columns() + "x" + view.rows()), log.sizes);
            view.handleRedraw(List.of(List.of("option_set", List.of("guifont", "Monospace:h20")), List.of("flush", List.of())));
            assertEquals(20, view.fontSize(), 0);
            view.handleRedraw(List.of(List.of("option_set", List.of("guifont", "")), List.of("flush", List.of())));
            assertEquals(size, view.fontSize(), 0);
            assertEquals(4, log.sizes.size());
        });
    }

    @Test public void pinchScalesTheFrameAndResizesOnceAtTheEnd() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = sizedView(instrumentation.getTargetContext());
            ResizeLog log = new ResizeLog();
            view.setListener(log);
            view.layout(0, 0, 1000, 1000);
            log.sizes.clear();
            float before = view.fontSize();
            long t = 0;
            view.onTouchEvent(pinch(t, MotionEvent.ACTION_DOWN, 1, 300));
            view.onTouchEvent(pinch(t += 10, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 300));
            for (int i = 1; i <= 10; i++) view.onTouchEvent(pinch(t += 20, MotionEvent.ACTION_MOVE, 2, 300 + 20 * i));
            // During the gesture the last frame is drawn scaled; the font and grid size change once, at its end.
            assertEquals(before, view.fontSize(), 0);
            assertTrue(log.sizes.isEmpty());
            view.draw(new Canvas(Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)));
            view.onTouchEvent(pinch(t += 10, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 500));
            view.onTouchEvent(pinch(t + 10, MotionEvent.ACTION_UP, 1, 500));
            assertTrue(view.fontSize() > before);
            assertEquals(List.of(view.columns() + "x" + view.rows()), log.sizes);
        });
    }

    @Test public void screenTextJoinsWrappedRows() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            NeovimView view = new NeovimView(instrumentation.getTargetContext());
            view.handleRedraw(List.of(List.of("grid_resize", List.of(1, 4, 4)),
                    List.of("grid_line", List.of(1, 0, 0, List.of(List.of("a", 0), List.of("b"), List.of(" ", 0, 2)), true)),
                    List.of("grid_line", List.of(1, 1, 0, List.of(List.of("c", 0, 4)), true)),
                    List.of("grid_line", List.of(1, 2, 0, List.of(List.of("d", 0), List.of(" ", 0, 3)), false)),
                    List.of("grid_line", List.of(1, 3, 0, List.of(List.of("e", 0), List.of(" ", 0, 3)), false)),
                    List.of("flush", List.of())));
            assertEquals("ab  ccccd\ne", view.screenText());
            view.handleRedraw(List.of(List.of("grid_line", List.of(1, 0, 3, List.of(List.of("!", 0)), false)),
                    List.of("flush", List.of())));
            assertEquals("ab !\nccccd\ne", view.screenText());
        });
    }

    @Test public void flushesNotifyAccessibilityAtMostOncePerInterval() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        // A UiAutomation connection enables accessibility for the app.
        var automation = instrumentation.getUiAutomation();
        var manager = instrumentation.getTargetContext().getSystemService(AccessibilityManager.class);
        for (int i = 0; i < 100 && !manager.isEnabled(); i++) Thread.sleep(50);
        assertTrue(manager.isEnabled());
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        // Each batch of flushes within one interval must send exactly one event.
        List<Integer> events = Collections.synchronizedList(new ArrayList<>());
        int[] batch = {0};
        try {
            NeovimView[] holder = new NeovimView[1];
            instrumentation.runOnMainSync(() -> {
                holder[0] = new NeovimView(activity);
                holder[0].setContentDescription("rendering test");
                activity.addContentView(holder[0], new ViewGroup.LayoutParams(200, 200));
            });
            instrumentation.waitForIdleSync();
            automation.setOnAccessibilityEventListener(event -> {
                if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                        && "rendering test".equals(String.valueOf(event.getContentDescription()))) events.add(batch[0]);
            });
            for (batch[0] = 1; batch[0] <= 2; batch[0]++) {
                instrumentation.runOnMainSync(() -> {
                    for (int i = 0; i < 5; i++) {
                        holder[0].handleRedraw(List.of(List.of("grid_line", List.of(1, 0, 0, List.of(List.of("x" + i, 0)), false)),
                                List.of("flush", List.of())));
                    }
                });
                Thread.sleep(600);
                assertEquals(batch[0] == 1 ? List.of(1) : List.of(1, 2), events);
            }
        } finally {
            automation.setOnAccessibilityEventListener(null);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void blockCursorIsHollowWithoutFocus() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.runOnMainSync(() -> {
                NeovimView view = new NeovimView(activity);
                activity.addContentView(view, new ViewGroup.LayoutParams(200, 200));
                view.handleRedraw(List.of(List.of("default_colors_set", List.of(0xffffff, 0, 0xff0000, 0, 0)),
                        List.of("grid_resize", List.of(1, 3, 1)), List.of("grid_cursor_goto", List.of(1, 0, 1)),
                        List.of("flush", List.of())));
                int w = (int) view.cellWidth(), h = (int) view.cellHeight();
                view.layout(0, 0, 3 * w, h);
                for (boolean focused : new boolean[] {false, true}) {
                    if (focused) assertTrue(view.requestFocus());
                    else view.clearFocus();
                    assertEquals(focused, view.isFocused());
                    Bitmap bitmap = Bitmap.createBitmap(3 * w, h, Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(bitmap));
                    assertEquals(art(bitmap), focused, ink(bitmap, w + w / 2, h / 2));
                    assertTrue(art(bitmap), ink(bitmap, w + w / 2, 0) && ink(bitmap, w + 1, h / 2));
                }
                ((ViewGroup) view.getParent()).removeView(view);
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void imeLineBreaksAreEnterAndMultiLineTextIsPasted() {
        attached((activity, view, input) -> {
            InputConnection connection = view.onCreateInputConnection(new EditorInfo());
            assertTrue(connection.commitText("\n", 1));
            assertTrue(connection.commitText("\r\n", 1));
            view.toggleCtrl();
            assertTrue(connection.commitText("\n", 1));
            assertTrue(connection.commitText("a\n  b", 1));
            assertTrue(connection.commitText("<x>", 1));
            assertEquals(List.of("<CR>", "<CR>", "<C-CR>", "<LT>x>"), input.keys);
            assertEquals(List.of("a\n  b"), input.pastes);
        });
    }

    @Test public void composingTextIsCommittedBeforeKeysTapsAndModifiers() {
        attached((activity, view, input) -> {
            view.handleRedraw(List.of(List.of("grid_resize", List.of(1, 40, 20)), List.of("flush", List.of())));
            float w = view.cellWidth(), h = view.cellHeight();
            // Each commit restarts the IME, which then composes through a new connection.
            InputConnection connection = view.onCreateInputConnection(new EditorInfo());
            assertTrue(connection.setComposingText("hello", 1));
            view.sendKey("<Esc>");
            connection = view.onCreateInputConnection(new EditorInfo());
            assertTrue(connection.setComposingText("hi", 1));
            view.onTouchEvent(touch(0, 0, MotionEvent.ACTION_DOWN, 1.5f * w, 0.5f * h));
            view.onTouchEvent(touch(0, 50, MotionEvent.ACTION_UP, 1.5f * w, 0.5f * h));
            connection = view.onCreateInputConnection(new EditorInfo());
            assertTrue(connection.setComposingText("x", 1));
            view.toggleCtrl();
            connection = view.onCreateInputConnection(new EditorInfo());
            assertTrue(connection.setComposingText("w", 1));
            assertEquals(List.of("hello", "<Esc>", "hi", "left press 0,1", "left release 0,1", "x", "<C-w>"), input.events);
        });
    }
}
