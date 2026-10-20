package net.fornwall.androidneovim.ui;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import net.fornwall.androidneovim.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NeovimViewTest {
    private static final class InputLog implements NeovimView.Listener {
        final List<String> keys = new ArrayList<>();
        final List<String> mouse = new ArrayList<>();
        @Override public void input(String input) { keys.add(input); }
        @Override public void paste(String text) { }
        @Override public void mouse(String button, String action, String modifiers, int row, int column) {
            mouse.add(button + " " + action + " " + row + "," + column);
        }
        @Override public void resize(int columns, int rows) { }
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
            assertEquals("a<LT>Esc>", NeovimView.literal("a<Esc>"));
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

    /** Draws one flushed white-on-black row into a bitmap; busy hides the cursor. */
    private static Bitmap render(NeovimView view, int columns, List<?> cells) {
        view.handleRedraw(List.of(List.of("default_colors_set", List.of(0xffffff, 0, 0xff0000, 0, 0)),
                List.of("grid_resize", List.of(1, columns, 1)), List.of("grid_line", List.of(1, 0, 0, cells, false)),
                List.of("busy_start", List.of()), List.of("flush", List.of())));
        int width = (int) Math.ceil(columns * view.cellWidth()), height = (int) view.cellHeight();
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static boolean ink(Bitmap bitmap, int x, int y) { return Color.green(bitmap.getPixel(x, y)) > 96; }

    /** The number of pixel rows with ink in columns [left, right). */
    private static int inkRows(Bitmap bitmap, int left, int right) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) {
                if (ink(bitmap, x, y)) { count++; break; }
            }
        }
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
}
