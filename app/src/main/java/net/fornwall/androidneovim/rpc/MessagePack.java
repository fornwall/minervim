package net.fornwall.androidneovim.rpc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The MessagePack types used by Neovim's RPC protocol, without a runtime dependency. */
public final class MessagePack {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ITEMS = 262_144;
    private static final int MAX_DEPTH = 64;
    /** Most grid_line cells hold one ASCII character; decode them without allocating. */
    private static final String[] ASCII = new String[128];

    static {
        for (int i = 0; i < ASCII.length; i++) ASCII[i] = String.valueOf((char) i);
    }

    private MessagePack() {}

    /** Neovim encodes Buffer, Window, and Tabpage handles as extension values. */
    public record Extension(byte type, byte[] data) {
        public Extension {
            data = data.clone();
        }

        @Override public byte[] data() { return data.clone(); }
    }

    public static Object read(InputStream input) throws IOException {
        return read(input, new Budget(), 0);
    }

    public static void write(OutputStream output, Object value) throws IOException {
        write(output, value, new Budget(), 0);
    }

    private static Object read(InputStream input, Budget budget, int depth) throws IOException {
        budget.item(depth);
        int tag = octet(input);
        if (tag < 0x80) return (long) tag;
        if (tag >= 0xe0) return (long) (byte) tag;
        if ((tag & 0xe0) == 0xa0) return string(input, tag & 31, budget);
        if ((tag & 0xf0) == 0x90) return array(input, tag & 15, budget, depth);
        if ((tag & 0xf0) == 0x80) return map(input, tag & 15, budget, depth);
        return switch (tag) {
            case 0xc0 -> null;
            case 0xc2 -> false;
            case 0xc3 -> true;
            case 0xc4 -> bytes(input, octet(input), budget);
            case 0xc5 -> bytes(input, length(input, 2), budget);
            case 0xc6 -> bytes(input, length(input, 4), budget);
            case 0xc7 -> extension(input, octet(input), budget);
            case 0xc8 -> extension(input, length(input, 2), budget);
            case 0xc9 -> extension(input, length(input, 4), budget);
            case 0xca -> Float.intBitsToFloat((int) integer(input, 4));
            case 0xcb -> Double.longBitsToDouble(integer(input, 8));
            case 0xcc -> (long) octet(input);
            case 0xcd -> integer(input, 2);
            case 0xce -> integer(input, 4);
            case 0xcf -> unsigned64(input);
            case 0xd0 -> (long) (byte) octet(input);
            case 0xd1 -> (long) (short) integer(input, 2);
            case 0xd2 -> (long) (int) integer(input, 4);
            case 0xd3 -> integer(input, 8);
            case 0xd4 -> extension(input, 1, budget);
            case 0xd5 -> extension(input, 2, budget);
            case 0xd6 -> extension(input, 4, budget);
            case 0xd7 -> extension(input, 8, budget);
            case 0xd8 -> extension(input, 16, budget);
            case 0xd9 -> string(input, octet(input), budget);
            case 0xda -> string(input, length(input, 2), budget);
            case 0xdb -> string(input, length(input, 4), budget);
            case 0xdc -> array(input, length(input, 2), budget, depth);
            case 0xdd -> array(input, length(input, 4), budget, depth);
            case 0xde -> map(input, length(input, 2), budget, depth);
            case 0xdf -> map(input, length(input, 4), budget, depth);
            default -> throw new IOException("Invalid MessagePack tag: " + tag);
        };
    }

    private static Object unsigned64(InputStream input) throws IOException {
        long value = integer(input, 8);
        return value >= 0 ? value : BigInteger.valueOf(value & Long.MAX_VALUE).setBit(63);
    }

    private static int octet(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("MessagePack stream ended");
        return value;
    }

    private static long integer(InputStream input, int size) throws IOException {
        long result = 0;
        for (int i = 0; i < size; i++) result = (result << 8) | octet(input);
        return result;
    }

    private static int length(InputStream input, int size) throws IOException {
        long length = integer(input, size);
        if (length > MAX_BYTES) throw new IOException("MessagePack length exceeds limit");
        return (int) length;
    }

    private static byte[] bytes(InputStream input, int length, Budget budget) throws IOException {
        budget.bytes(length);
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated MessagePack value");
            if (count == 0) result[offset++] = (byte) octet(input);
            else offset += count;
        }
        return result;
    }

    private static String string(InputStream input, int length, Budget budget) throws IOException {
        if (length == 0) return "";
        if (length == 1) {
            budget.bytes(1);
            int value = octet(input);
            if (value < ASCII.length) return ASCII[value];
            return new String(new byte[]{(byte) value}, StandardCharsets.UTF_8);
        }
        return new String(bytes(input, length, budget), StandardCharsets.UTF_8);
    }

    private static Extension extension(InputStream input, int length, Budget budget) throws IOException {
        byte type = (byte) octet(input);
        return new Extension(type, bytes(input, length, budget));
    }

    private static List<Object> array(InputStream input, int length, Budget budget, int depth)
            throws IOException {
        budget.collection(length);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) result.add(read(input, budget, depth + 1));
        return result;
    }

    private static Map<Object, Object> map(InputStream input, int length, Budget budget, int depth)
            throws IOException {
        budget.collection((long) length * 2);
        Map<Object, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            Object key = read(input, budget, depth + 1);
            result.put(key, read(input, budget, depth + 1));
        }
        return result;
    }

    private static void write(OutputStream output, Object value, Budget budget, int depth)
            throws IOException {
        budget.item(depth);
        if (value == null) output.write(0xc0);
        else if (value instanceof Boolean bool) output.write(bool ? 0xc3 : 0xc2);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) writeInteger(output, ((Number) value).longValue());
        else if (value instanceof BigInteger integer) {
            if (integer.signum() < 0 || integer.bitLength() > 64)
                throw new IOException("Integer outside MessagePack uint64 range");
            output.write(0xcf);
            integer(output, integer.longValue(), 8);
        } else if (value instanceof Float number) {
            output.write(0xca);
            integer(output, Float.floatToRawIntBits(number), 4);
        } else if (value instanceof Double number) {
            output.write(0xcb);
            integer(output, Double.doubleToRawLongBits(number), 8);
        } else if (value instanceof String string) {
            if (string.length() > MAX_BYTES) throw new IOException("String exceeds MessagePack limit");
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            budget.bytes(bytes.length);
            if (bytes.length < 32) output.write(0xa0 | bytes.length);
            else length(output, bytes.length, 0xd9, 0xda, 0xdb);
            output.write(bytes);
        } else if (value instanceof byte[] bytes) {
            budget.bytes(bytes.length);
            length(output, bytes.length, 0xc4, 0xc5, 0xc6);
            output.write(bytes);
        } else if (value instanceof Extension extension) {
            byte[] bytes = extension.data;
            budget.bytes(bytes.length);
            length(output, bytes.length, 0xc7, 0xc8, 0xc9);
            output.write(extension.type);
            output.write(bytes);
        } else if (value instanceof List<?> list) {
            budget.collection(list.size());
            if (list.size() < 16) output.write(0x90 | list.size());
            else length(output, list.size(), -1, 0xdc, 0xdd);
            for (Object item : list) write(output, item, budget, depth + 1);
        } else if (value instanceof Map<?, ?> map) {
            budget.collection((long) map.size() * 2);
            if (map.size() < 16) output.write(0x80 | map.size());
            else length(output, map.size(), -1, 0xde, 0xdf);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                write(output, entry.getKey(), budget, depth + 1);
                write(output, entry.getValue(), budget, depth + 1);
            }
        } else throw new IOException("Unsupported MessagePack type: " + value.getClass().getName());
    }

    private static void writeInteger(OutputStream output, long value) throws IOException {
        if (value >= -32 && value <= 127) output.write((int) value & 255);
        else if (value >= 0) {
            if (value <= 255) { output.write(0xcc); integer(output, value, 1); }
            else if (value <= 65535) { output.write(0xcd); integer(output, value, 2); }
            else if (value <= 0xffffffffL) { output.write(0xce); integer(output, value, 4); }
            else { output.write(0xcf); integer(output, value, 8); }
        } else if (value >= Byte.MIN_VALUE) { output.write(0xd0); integer(output, value, 1); }
        else if (value >= Short.MIN_VALUE) { output.write(0xd1); integer(output, value, 2); }
        else if (value >= Integer.MIN_VALUE) { output.write(0xd2); integer(output, value, 4); }
        else { output.write(0xd3); integer(output, value, 8); }
    }

    private static void length(OutputStream output, int length, int tag8, int tag16, int tag32)
            throws IOException {
        if (tag8 >= 0 && length <= 255) { output.write(tag8); integer(output, length, 1); }
        else if (length <= 65535) { output.write(tag16); integer(output, length, 2); }
        else { output.write(tag32); integer(output, length, 4); }
    }

    private static void integer(OutputStream output, long value, int size) throws IOException {
        for (int shift = (size - 1) * 8; shift >= 0; shift -= 8)
            output.write((int) (value >>> shift) & 255);
    }

    private static final class Budget {
        private int items;
        private int bytes;

        void item(int depth) throws IOException {
            if (depth > MAX_DEPTH || ++items > MAX_ITEMS)
                throw new IOException("MessagePack nesting or item limit exceeded");
        }

        void collection(long size) throws IOException {
            if (size > MAX_ITEMS - items)
                throw new IOException("MessagePack collection exceeds item limit");
        }

        void bytes(int size) throws IOException {
            if (size < 0 || size > MAX_BYTES - bytes)
                throw new IOException("MessagePack payload exceeds byte limit");
            bytes += size;
        }
    }
}
