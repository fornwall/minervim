package net.fornwall.androidneovim.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** UI-thread-owned ext_linegrid state. Only a flush publishes a complete, immutable frame. */
public final class NeovimGrid {
    private static final int MAX_CELLS = 1_000_000;
    private static final int MAX_HIGHLIGHTS = 65_536;
    private int columns = 1;
    private int rows = 1;
    private String[][] text = {{" "}};
    private int[][] attributes = {{0}};
    private Highlight[] highlights = new Highlight[64];
    private boolean highlightsShared;
    private int foreground;
    private int background;
    private int special;
    private int cursorRow;
    private int cursorColumn;
    private String cursorShape = "block";
    private int cursorPercentage = 25;
    private int cursorAttribute;
    private int mode = -1;
    private List<?> modes = List.of();
    private boolean busy;
    private Frame frame;

    /** Until default_colors_set, frames use Neovim's built-in Normal colors for a dark or light background. */
    public NeovimGrid(boolean dark) {
        foreground = special = dark ? 0xffe0e2ea : 0xff14161b;
        background = dark ? 0xff14161b : 0xffe0e2ea;
        frame = snapshot();
    }
    public NeovimGrid() { this(true); }

    public Frame frame() { return frame; }

    /** Events are read synchronously and never retained. Return true if a frame was published. */
    public boolean handleRedraw(List<?> events) {
        boolean flushed = false;
        for (Object value : events) {
            if (!(value instanceof List<?> event) || event.isEmpty()) continue;
            String name = String.valueOf(event.get(0));
            for (int i = 1; i < event.size(); i++) {
                if (!(event.get(i) instanceof List<?> args)) continue;
                switch (name) {
                    case "grid_resize" -> {
                        if (integer(args, 0) == 1) resize(integer(args, 1), integer(args, 2));
                    }
                    case "grid_clear" -> {
                        if (integer(args, 0) == 1) clear();
                    }
                    case "grid_line" -> {
                        if (integer(args, 0) == 1) line(args);
                    }
                    case "grid_scroll" -> {
                        if (integer(args, 0) == 1) scroll(args);
                    }
                    case "grid_cursor_goto" -> {
                        if (integer(args, 0) == 1) {
                            cursorRow = integer(args, 1);
                            cursorColumn = integer(args, 2);
                        }
                    }
                    case "default_colors_set" -> {
                        foreground = color(integer(args, 0), foreground);
                        background = color(integer(args, 1), background);
                        special = color(integer(args, 2), foreground);
                    }
                    case "hl_attr_define" -> {
                        int id = integer(args, 0);
                        if (id >= 0 && id < MAX_HIGHLIGHTS && args.get(1) instanceof Map<?, ?> style) {
                            // Published frames share the table until the next definition (copy on write).
                            if (highlightsShared || id >= highlights.length) {
                                highlights = Arrays.copyOf(highlights, Math.max(highlights.length, Math.min(MAX_HIGHLIGHTS, 2 * id + 2)));
                                highlightsShared = false;
                            }
                            highlights[id] = new Highlight(style);
                        }
                    }
                    case "mode_info_set" -> {
                        // Copy each map: no decoder-owned event objects escape this call.
                        if (args.get(1) instanceof List<?> list) {
                            modes = list.stream().map(item -> item instanceof Map<?, ?> map
                                    ? Map.copyOf(map) : Map.of()).toList();
                            applyMode();
                        }
                    }
                    case "mode_change" -> {
                        mode = integer(args, 1);
                        applyMode();
                    }
                    case "busy_start" -> busy = true;
                    case "busy_stop" -> busy = false;
                    case "flush" -> {
                        frame = snapshot();
                        flushed = true;
                    }
                    default -> { }
                }
            }
        }
        return flushed;
    }

    private void applyMode() {
        if (mode < 0 || mode >= modes.size() || !(modes.get(mode) instanceof Map<?, ?> info)) return;
        cursorShape = info.get("cursor_shape") instanceof String shape ? shape : "block";
        cursorPercentage = Math.max(1, Math.min(100, mapInteger(info, "cell_percentage", 25)));
        cursorAttribute = Math.max(0, mapInteger(info, "attr_id", 0));
    }

    private void resize(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > MAX_CELLS) {
            throw new IllegalArgumentException("Neovim grid exceeds " + MAX_CELLS + " cells");
        }
        String[][] nextText = new String[height][width];
        int[][] nextAttributes = new int[height][width];
        for (int row = 0; row < height; row++) {
            Arrays.fill(nextText[row], " ");
            if (row < rows) {
                System.arraycopy(text[row], 0, nextText[row], 0, Math.min(width, columns));
                System.arraycopy(attributes[row], 0, nextAttributes[row], 0, Math.min(width, columns));
            }
        }
        columns = width;
        rows = height;
        text = nextText;
        attributes = nextAttributes;
    }

    private void clear() {
        for (int row = 0; row < rows; row++) {
            Arrays.fill(text[row], " ");
            Arrays.fill(attributes[row], 0);
        }
    }

    private void line(List<?> args) {
        int row = integer(args, 1);
        int column = integer(args, 2);
        if (row < 0 || row >= rows || column < 0 || column >= columns) return;
        int attribute = 0;
        if (!(args.get(3) instanceof List<?> cells)) return;
        for (Object value : cells) {
            if (!(value instanceof List<?> cell) || cell.isEmpty()) continue;
            String glyph = String.valueOf(cell.get(0));
            if (cell.size() > 1) attribute = integer(cell, 1);
            int repeat = cell.size() > 2 ? Math.max(0, integer(cell, 2)) : 1;
            int end = (int) Math.min(columns, (long) column + repeat);
            while (column < end) {
                text[row][column] = glyph;
                attributes[row][column++] = attribute;
            }
            if (column == columns) break;
        }
    }

    private void scroll(List<?> args) {
        int top = Math.max(0, integer(args, 1));
        int bottom = Math.min(rows, integer(args, 2));
        int left = Math.max(0, integer(args, 3));
        int right = Math.min(columns, integer(args, 4));
        if (top >= bottom || left >= right) return;
        int rowOffset = integer(args, 5);
        int columnOffset = integer(args, 6);
        int start = rowOffset >= 0 ? top : bottom - 1;
        int end = rowOffset >= 0 ? bottom : top - 1;
        int step = rowOffset >= 0 ? 1 : -1;
        for (int row = start; row != end; row += step) {
            int columnStart = columnOffset >= 0 ? left : right - 1;
            int columnEnd = columnOffset >= 0 ? right : left - 1;
            int columnStep = columnOffset >= 0 ? 1 : -1;
            for (int column = columnStart; column != columnEnd; column += columnStep) {
                long sourceRow = (long) row + rowOffset;
                long sourceColumn = (long) column + columnOffset;
                if (sourceRow >= top && sourceRow < bottom && sourceColumn >= left && sourceColumn < right) {
                    text[row][column] = text[(int) sourceRow][(int) sourceColumn];
                    attributes[row][column] = attributes[(int) sourceRow][(int) sourceColumn];
                } else {
                    text[row][column] = " ";
                    attributes[row][column] = 0;
                }
            }
        }
    }

    private Frame snapshot() {
        String[][] copiedText = new String[rows][];
        int[][] copiedAttributes = new int[rows][];
        for (int row = 0; row < rows; row++) {
            copiedText[row] = text[row].clone();
            copiedAttributes[row] = attributes[row].clone();
        }
        highlightsShared = true;
        return new Frame(columns, rows, copiedText, copiedAttributes, highlights, foreground,
                background, special, cursorRow, cursorColumn, cursorShape, cursorPercentage, cursorAttribute, busy);
    }

    private static int integer(List<?> list, int index) { return ((Number) list.get(index)).intValue(); }
    private static int mapInteger(Map<?, ?> map, String key, int fallback) {
        return map.get(key) instanceof Number number ? number.intValue() : fallback;
    }
    private static int color(int value, int fallback) { return value < 0 ? fallback : 0xff000000 | value; }

    public static final class Highlight {
        final int foreground;
        final int background;
        final int special;
        final boolean reverse;
        final boolean bold;
        final boolean italic;
        final boolean underline;
        final boolean undercurl;
        final boolean strikethrough;
        Highlight(Map<?, ?> style) {
            foreground = mapInteger(style, "foreground", -1);
            background = mapInteger(style, "background", -1);
            special = mapInteger(style, "special", -1);
            reverse = Boolean.TRUE.equals(style.get("reverse"));
            bold = Boolean.TRUE.equals(style.get("bold"));
            italic = Boolean.TRUE.equals(style.get("italic"));
            undercurl = Boolean.TRUE.equals(style.get("undercurl"));
            underline = Boolean.TRUE.equals(style.get("underline")) || Boolean.TRUE.equals(style.get("underdouble")) || Boolean.TRUE.equals(style.get("underdotted"))
                    || Boolean.TRUE.equals(style.get("underdashed"));
            strikethrough = Boolean.TRUE.equals(style.get("strikethrough"));
        }
    }

    public static final class Frame {
        public final int columns, rows, foreground, background, special, cursorRow, cursorColumn, cursorPercentage, cursorAttribute;
        public final String cursorShape;
        public final boolean busy;
        private final String[][] text;
        private final int[][] attributes;
        private final Highlight[] highlights;
        private static final Highlight DEFAULT = new Highlight(Map.of());
        Frame(int columns, int rows, String[][] text, int[][] attributes, Highlight[] highlights,
                int foreground, int background, int special, int cursorRow, int cursorColumn,
                String cursorShape, int cursorPercentage, int cursorAttribute, boolean busy) {
            this.columns = columns; this.rows = rows; this.text = text; this.attributes = attributes;
            this.highlights = highlights; this.foreground = foreground; this.background = background;
            this.special = special; this.cursorRow = cursorRow; this.cursorColumn = cursorColumn;
            this.cursorShape = cursorShape; this.cursorPercentage = cursorPercentage;
            this.cursorAttribute = cursorAttribute; this.busy = busy;
        }
        public String text(int row, int column) { return text[row][column]; }
        public int attribute(int row, int column) { return attributes[row][column]; }
        public Highlight highlight(int row, int column) { return highlight(attributes[row][column]); }
        public Highlight highlight(int id) {
            Highlight highlight = id >= 0 && id < highlights.length ? highlights[id] : null;
            return highlight == null ? DEFAULT : highlight;
        }
        public int foreground(Highlight highlight) {
            return highlight.reverse ? color(highlight.background, background) : color(highlight.foreground, foreground);
        }
        public int background(Highlight highlight) {
            return highlight.reverse ? color(highlight.foreground, foreground) : color(highlight.background, background);
        }
        /** Underlines and strikethrough use special when the highlight sets it, otherwise the text color (like the TUI). */
        public int decoration(Highlight highlight) {
            return highlight.special >= 0 ? color(highlight.special, special) : foreground(highlight);
        }
        /** Undercurl falls back to the default special color, which Neovim makes red unless Normal sets guisp. */
        public int special(Highlight highlight) { return color(highlight.special, special); }
        /** The mode's cursor highlight (mode_info attr_id); attr_id 0 and unset colors swap the cell's colors. */
        public int cursorBackground() {
            Highlight cursor = highlight(cursorAttribute);
            return color(cursorAttribute == 0 ? -1 : cursor.reverse ? cursor.foreground : cursor.background, foreground(cursorCell()));
        }
        public int cursorForeground() {
            Highlight cursor = highlight(cursorAttribute);
            return color(cursorAttribute == 0 ? -1 : cursor.reverse ? cursor.background : cursor.foreground, background(cursorCell()));
        }
        private Highlight cursorCell() {
            return cursorRow >= 0 && cursorRow < rows && cursorColumn >= 0 && cursorColumn < columns ? highlight(cursorRow, cursorColumn) : DEFAULT;
        }
    }
}
