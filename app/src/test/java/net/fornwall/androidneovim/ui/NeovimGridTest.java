package net.fornwall.androidneovim.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class NeovimGridTest {
    private static List<?> event(String name, Object... args) { return List.of(name, List.of(args)); }
    private static List<?> flush() { return event("flush"); }
    private static List<?> line(int row, int column, Object... cells) {
        return event("grid_line", 1, row, column, List.of(cells), false);
    }
    private static List<?> cell(String text, int attribute) { return List.of(text, attribute); }

    @Test public void flushIsTransactionBoundaryAcrossNotifications() {
        NeovimGrid grid = new NeovimGrid();
        assertFalse(grid.handleRedraw(List.of(event("grid_resize", 1, 5, 2), line(0, 0, cell("A", 1)))));
        assertEquals(1, grid.frame().columns);
        assertEquals(" ", grid.frame().text(0, 0));
        assertTrue(grid.handleRedraw(List.of(flush())));
        NeovimGrid.Frame oldFrame = grid.frame();
        assertEquals("A", oldFrame.text(0, 0));
        grid.handleRedraw(List.of(line(0, 0, cell("B", 2)), flush()));
        assertEquals("A", oldFrame.text(0, 0));
        assertEquals("B", grid.frame().text(0, 0));
    }

    @Test public void repeatedCellsInheritHighlightAndKeepWideContinuation() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("grid_resize", 1, 8, 1),
                line(0, 0, cell("界", 5), List.of(""), List.of(" ", 6, 3), List.of("x")), flush()));
        assertEquals("界", grid.frame().text(0, 0));
        assertEquals("", grid.frame().text(0, 1));
        assertEquals(5, grid.frame().attribute(0, 1));
        assertEquals(6, grid.frame().attribute(0, 4));
        assertEquals("x", grid.frame().text(0, 5));
        assertEquals(6, grid.frame().attribute(0, 5));
    }

    @Test public void scrollMovesOnlyTheSpecifiedRegionInBothDirections() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("grid_resize", 1, 4, 4),
                line(0, 0, List.of("A", 1, 4)), line(1, 0, List.of("B", 2, 4)),
                line(2, 0, List.of("C", 3, 4)), line(3, 0, List.of("D", 4, 4)),
                event("grid_scroll", 1, 0, 4, 1, 3, 1, 0), flush()));
        assertEquals("A", grid.frame().text(0, 0));
        assertEquals("B", grid.frame().text(0, 1));
        assertEquals(2, grid.frame().attribute(0, 1));
        assertEquals("C", grid.frame().text(1, 2));
        assertEquals(" ", grid.frame().text(3, 1));
        assertEquals("D", grid.frame().text(3, 3));
        grid.handleRedraw(List.of(event("grid_scroll", 1, 0, 4, 1, 3, -1, 0), flush()));
        assertEquals(" ", grid.frame().text(0, 1));
        assertEquals("B", grid.frame().text(1, 1));
        assertEquals("D", grid.frame().text(3, 1));
    }

    @Test public void scrollColumnsDoesNotOverwriteSources() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("grid_resize", 1, 4, 1),
                line(0, 0, cell("a", 1), cell("b", 2), cell("c", 3), cell("d", 4)),
                event("grid_scroll", 1, 0, 1, 0, 4, 0, -1), flush()));
        assertEquals(" ", grid.frame().text(0, 0));
        assertEquals("a", grid.frame().text(0, 1));
        assertEquals("b", grid.frame().text(0, 2));
        assertEquals("c", grid.frame().text(0, 3));
    }

    @Test public void colorsAreRgbAndReverseUsesDefaults() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("default_colors_set", 0x112233, 0x445566, 0x778899, 0, 0),
                event("hl_attr_define", 1, Map.of("reverse", true, "bold", true), Map.of(), List.of()),
                line(0, 0, cell("x", 1)), flush()));
        NeovimGrid.Frame frame = grid.frame();
        assertEquals(0xff445566, frame.foreground(frame.highlight(0, 0)));
        assertEquals(0xff112233, frame.background(frame.highlight(0, 0)));
        assertTrue(frame.highlight(0, 0).bold);
    }

    @Test public void decorationsUseTextColorUnlessSpecialIsSet() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("default_colors_set", 0x111111, 0x222222, 0xff0000, 0, 0),
                event("hl_attr_define", 1, Map.of("underline", true, "foreground", 0x333333), Map.of(), List.of()),
                event("hl_attr_define", 2, Map.of("underdashed", true, "special", 0x444444), Map.of(), List.of()),
                event("hl_attr_define", 3, Map.of("strikethrough", true, "reverse", true), Map.of(), List.of()),
                event("hl_attr_define", 4, Map.of("undercurl", true), Map.of(), List.of()), flush()));
        NeovimGrid.Frame frame = grid.frame();
        assertEquals(0xff333333, frame.decoration(frame.highlight(1)));
        assertEquals(0xff444444, frame.decoration(frame.highlight(2)));
        assertEquals(0xff222222, frame.decoration(frame.highlight(3)));
        assertEquals(0xffff0000, frame.special(frame.highlight(4)));
        assertEquals(0xff444444, frame.special(frame.highlight(2)));
    }

    @Test public void modeInfoDoesNotRetainMutableEvents() {
        NeovimGrid grid = new NeovimGrid();
        var mode = new java.util.HashMap<String, Object>();
        mode.put("cursor_shape", "vertical");
        mode.put("cell_percentage", 20);
        var modes = new ArrayList<>(List.of(mode));
        grid.handleRedraw(List.of(event("mode_info_set", true, modes)));
        mode.put("cursor_shape", "horizontal");
        modes.clear();
        grid.handleRedraw(List.of(event("mode_change", "insert", 0), flush()));
        assertEquals("vertical", grid.frame().cursorShape);
        assertEquals(20, grid.frame().cursorPercentage);
    }

    @Test public void cursorUsesModeHighlightOrSwapsCellColors() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("default_colors_set", 0x111111, 0x222222, -1, 0, 0),
                event("hl_attr_define", 7, Map.of("foreground", 0x333333, "background", 0x444444), Map.of(), List.of()),
                event("hl_attr_define", 8, Map.of("reverse", true), Map.of(), List.of()),
                event("hl_attr_define", 9, Map.of("foreground", 0x555555, "background", 0x666666), Map.of(), List.of()),
                event("mode_info_set", true, List.of(Map.of("cursor_shape", "block"), Map.of("cursor_shape", "block", "attr_id", 7),
                        Map.of("cursor_shape", "block", "attr_id", 8))),
                event("mode_change", "normal", 0), flush()));
        assertEquals(0, grid.frame().cursorAttribute);
        assertEquals(0xff111111, grid.frame().cursorBackground());
        assertEquals(0xff222222, grid.frame().cursorForeground());
        // attr_id 0 swaps the colors of the cell under the cursor, such as a Search match.
        grid.handleRedraw(List.of(line(0, 0, cell("x", 9)), flush()));
        assertEquals(0xff555555, grid.frame().cursorBackground());
        assertEquals(0xff666666, grid.frame().cursorForeground());
        // So does a cursor highlight without colors (TermCursor is gui=reverse).
        grid.handleRedraw(List.of(event("mode_change", "terminal", 2), flush()));
        assertEquals(0xff555555, grid.frame().cursorBackground());
        assertEquals(0xff666666, grid.frame().cursorForeground());
        grid.handleRedraw(List.of(event("mode_change", "visual", 1), flush()));
        assertEquals(0xff444444, grid.frame().cursorBackground());
        assertEquals(0xff333333, grid.frame().cursorForeground());
        grid.handleRedraw(List.of(event("mode_info_set", true, List.of(Map.of(), Map.of("cursor_shape", "vertical"))), flush()));
        assertEquals("vertical", grid.frame().cursorShape);
        assertEquals(0xff555555, grid.frame().cursorBackground());
    }

    @Test public void publishedHighlightsAreCopiedOnWrite() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("hl_attr_define", 1, Map.of("undercurl", true), Map.of(), List.of()),
                line(0, 0, cell("x", 1)), flush()));
        NeovimGrid.Frame first = grid.frame();
        assertTrue(first.highlight(0, 0).undercurl);
        assertFalse(first.highlight(0, 0).underline);
        grid.handleRedraw(List.of(event("hl_attr_define", 1, Map.of("underdouble", true), Map.of(), List.of()),
                event("hl_attr_define", 5000, Map.of("bold", true), Map.of(), List.of()), flush()));
        assertTrue(first.highlight(0, 0).undercurl);
        assertFalse(first.highlight(5000).bold);
        assertTrue(grid.frame().highlight(0, 0).underline);
        assertTrue(grid.frame().highlight(5000).bold);
        assertFalse(grid.frame().highlight(-1).bold);
    }

    @Test public void reversedOrOutsideScrollRegionsAreIgnored() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(line(0, 0, cell("x", 1)),
                event("grid_scroll", 1, 5, 2, 0, 1, 1, 0),
                event("grid_scroll", 1, 0, 1, 6, 2, 1, 0), flush()));
        assertEquals("x", grid.frame().text(0, 0));
    }

    @Test(expected = IllegalArgumentException.class) public void unreasonableDimensionsAreRejected() {
        new NeovimGrid().handleRedraw(List.of(event("grid_resize", 1, Integer.MAX_VALUE, Integer.MAX_VALUE)));
    }
}
