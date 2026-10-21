package com.minervim.ui;

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
    private static List<?> wrappedLine(int row, int column, Object... cells) {
        return event("grid_line", 1, row, column, List.of(cells), true);
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

    @Test public void framesShareUnchangedRowsAndKeepTheirOwnContents() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("grid_resize", 1, 3, 4), line(0, 0, cell("a", 1)), line(1, 0, cell("b", 1)),
                line(2, 0, cell("c", 1)), line(3, 0, cell("d", 1)), flush()));
        NeovimGrid.Frame first = grid.frame();
        grid.handleRedraw(List.of(line(1, 1, cell("x", 2)), flush()));
        NeovimGrid.Frame second = grid.frame();
        assertTrue(second.sharesRow(0, first, 0));
        assertTrue(second.sharesRow(2, first, 2));
        assertFalse(second.sharesRow(1, first, 1));
        assertEquals(" ", first.text(1, 1));
        assertEquals("x", second.text(1, 1));
        // Full-width scrolls move rows; the row scrolled in is cleared without touching published frames.
        grid.handleRedraw(List.of(event("grid_scroll", 1, 0, 4, 0, 3, 1, 0), line(3, 0, cell("e", 1)), flush()));
        NeovimGrid.Frame third = grid.frame();
        for (int row = 0; row < 3; row++) assertTrue(third.sharesRow(row, second, row + 1));
        assertEquals("e", third.text(3, 0));
        assertEquals(" ", third.text(3, 1));
        assertEquals(0, third.attribute(3, 1));
        assertEquals("a", second.text(0, 0));
        assertEquals("d", second.text(3, 0));
        grid.handleRedraw(List.of(event("grid_scroll", 1, 1, 4, 0, 3, -2, 0), flush()));
        NeovimGrid.Frame fourth = grid.frame();
        assertTrue(fourth.sharesRow(0, third, 0));
        assertTrue(fourth.sharesRow(3, third, 1));
        assertEquals(" ", fourth.text(1, 0));
        assertEquals(" ", fourth.text(2, 0));
        assertEquals("c", third.text(1, 0));
        grid.handleRedraw(List.of(event("grid_clear", 1), flush()));
        assertEquals(" ", grid.frame().text(0, 0));
        assertEquals("b", fourth.text(0, 0));
        assertEquals("x", fourth.text(0, 1));
    }

    @Test public void wrapHintsFollowLinesThatReachTheLastColumn() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("grid_resize", 1, 3, 3), wrappedLine(0, 0, List.of("a", 1, 3)),
                wrappedLine(1, 0, cell("b", 1)), flush()));
        assertTrue(grid.frame().wrapped(0));
        assertFalse(grid.frame().wrapped(1));
        grid.handleRedraw(List.of(line(0, 0, cell("x", 1)), flush()));
        assertTrue(grid.frame().wrapped(0));
        grid.handleRedraw(List.of(line(0, 2, cell("y", 1)), wrappedLine(2, 1, cell("c", 1), cell("d", 1)), flush()));
        NeovimGrid.Frame frame = grid.frame();
        assertFalse(frame.wrapped(0));
        assertTrue(frame.wrapped(2));
        grid.handleRedraw(List.of(event("grid_scroll", 1, 0, 3, 0, 3, 1, 0), flush()));
        assertTrue(grid.frame().wrapped(1));
        assertFalse(grid.frame().wrapped(2));
        assertTrue(frame.wrapped(2));
        grid.handleRedraw(List.of(event("grid_clear", 1), flush()));
        assertFalse(grid.frame().wrapped(1));
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

    @Test public void defaultColorsFollowTheBackgroundUntilNeovimSetsThem() {
        NeovimGrid.Frame dark = new NeovimGrid(true).frame(), light = new NeovimGrid(false).frame();
        assertEquals(0xffe0e2ea, dark.foreground);
        assertEquals(0xff14161b, dark.background);
        assertEquals(0xff14161b, light.foreground);
        assertEquals(0xffe0e2ea, light.background);
        NeovimGrid grid = new NeovimGrid(false);
        grid.handleRedraw(List.of(event("default_colors_set", 0x112233, 0x445566, 0x778899, 0, 0), flush()));
        assertEquals(0xff445566, grid.frame().background);
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
        grid.handleRedraw(List.of(event("hl_attr_define", 2, Map.of("standout", true, "overline", true), Map.of(), List.of()), flush()));
        assertEquals(0xff445566, grid.frame().foreground(grid.frame().highlight(2)));
        assertTrue(grid.frame().highlight(2).overline);
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

    @Test public void uiOptionsApplyWithTheNextFlush() {
        NeovimGrid grid = new NeovimGrid();
        grid.handleRedraw(List.of(event("option_set", "linespace", 3), event("option_set", "guifont", "Fira Code:h12.5:b,Mono:h9"),
                event("option_set", "ext_cmdline", false)));
        assertEquals(0, grid.frame().linespace);
        assertEquals(0, grid.frame().guifontSize, 0);
        assertEquals(0, grid.frame().guifontSets);
        grid.handleRedraw(List.of(flush()));
        assertEquals(3, grid.frame().linespace);
        assertEquals(12.5f, grid.frame().guifontSize, 0);
        assertEquals(1, grid.frame().guifontSets);
        // Neovim repeats option_set for an unchanged value, which must still be seen.
        grid.handleRedraw(List.of(event("option_set", "guifont", "Fira Code:h12.5:b,Mono:h9"), flush()));
        assertEquals(2, grid.frame().guifontSets);
        assertEquals(16, NeovimGrid.guifontSize(":h16"), 0);
        assertEquals(0, NeovimGrid.guifontSize("Monospace"), 0);
        assertEquals(0, NeovimGrid.guifontSize(":w8:hx,Mono:h9"), 0);
        assertEquals(0, NeovimGrid.guifontSize(""), 0);
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
