package net.fornwall.androidneovim.ui;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.view.KeyEvent;
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
        @Override public void input(String input) { keys.add(input); }
        @Override public void paste(String text) { }
        @Override public void mouse(String button, String action, String modifiers, int row, int column) { }
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
}
