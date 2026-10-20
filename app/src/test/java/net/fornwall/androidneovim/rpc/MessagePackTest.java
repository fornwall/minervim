package net.fornwall.androidneovim.rpc;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MessagePackTest {
    private Object roundTrip(Object value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MessagePack.write(output, value);
        return MessagePack.read(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test public void numbersCoverEveryWireWidth() throws Exception {
        long[] values = {Long.MIN_VALUE, Integer.MIN_VALUE - 1L, Integer.MIN_VALUE,
                Short.MIN_VALUE - 1L, Short.MIN_VALUE, -129, -128, -33, -32, -1, 0, 127,
                128, 255, 256, 65535, 65536, 0xffffffffL, 0x100000000L, Long.MAX_VALUE};
        for (long value : values) assertEquals(value, roundTrip(value));
        assertEquals(new BigInteger("18446744073709551615"),
                roundTrip(new BigInteger("18446744073709551615")));
        assertEquals(1.25f, roundTrip(1.25f));
        assertEquals(-1.25d, roundTrip(-1.25d));
    }

    @Test public void nestedUiEventAndUnicodeRoundTrip() throws Exception {
        Object value = Arrays.asList(2L, "redraw", List.of(
                List.of("grid_line", List.of(1L, 0L, 0L,
                        List.of(List.of("λ🙂", 3L), List.of(" ", 0L, 5L)), false)),
                List.of("flush", List.of())), Map.of("rgb", true), null);
        assertEquals(value, roundTrip(value));
        for (int size : new int[]{0, 31, 32, 255, 256, 65535, 65536}) {
            String string = "x".repeat(size);
            assertEquals(string, roundTrip(string));
        }
    }

    @Test public void singleAsciiCharactersShareOneString() throws Exception {
        List<?> cells = (List<?>) roundTrip(List.of("a", "a", "", "é", "ab", "\u007f"));
        assertSame(cells.get(0), cells.get(1));
        assertEquals(List.of("a", "a", "", "é", "ab", "\u007f"), cells);
        assertEquals("\ufffd", MessagePack.read(new ByteArrayInputStream(new byte[]{(byte) 0xa1, (byte) 0xff})));
        assertThrows(IOException.class, () -> MessagePack.read(new ByteArrayInputStream(new byte[]{(byte) 0xa1})));
    }

    @Test public void binaryAndNeovimHandlesRoundTrip() throws Exception {
        byte[] bytes = {(byte) 0xff, 0, 42};
        assertArrayEquals(bytes, (byte[]) roundTrip(bytes));
        MessagePack.Extension handle = (MessagePack.Extension)
                roundTrip(new MessagePack.Extension((byte) 1, bytes));
        assertEquals(1, handle.type());
        assertArrayEquals(bytes, handle.data());
        MessagePack.Extension fixed = (MessagePack.Extension) MessagePack.read(
                new ByteArrayInputStream(new byte[]{(byte) 0xd4, 0, 7}));
        assertArrayEquals(new byte[]{7}, fixed.data());
    }

    @Test public void rejectsHostileLengthsDepthAndTruncation() {
        byte[][] invalid = {
                {(byte) 0xdd, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                {(byte) 0xc6, 1, 0, 0, 1},
                {(byte) 0xdc, 0, 2, 1},
                {(byte) 0xd9, 5, 65},
                {(byte) 0xc1}
        };
        for (byte[] bytes : invalid)
            assertThrows(IOException.class, () -> MessagePack.read(new ByteArrayInputStream(bytes)));
        byte[] nested = new byte[100];
        Arrays.fill(nested, (byte) 0x91);
        assertThrows(IOException.class, () -> MessagePack.read(new ByteArrayInputStream(nested)));
    }
}
