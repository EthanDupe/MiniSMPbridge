package com.minismp.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Lightweight Java Edition 1.12.2 (protocol 340) binary helpers.
 */
public final class JavaBinary {

    private static final JsonParser JSON_PARSER = new JsonParser();

    private JavaBinary() {
    }

    public static int readVarInt(ByteBuf buf) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = buf.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("Java VarInt is too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static long readVarLong(ByteBuf buf) {
        int numRead = 0;
        long result = 0;
        byte read;
        do {
            read = buf.readByte();
            long value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 10) {
                throw new IllegalArgumentException("Java VarLong is too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    public static void writeVarLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    public static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("Invalid Java string length: " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String str) {
        byte[] bytes = (str == null ? "" : str).getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static int[] readPosition(ByteBuf buf) {
        long val = buf.readLong();
        int x = (int) (val >> 38);
        int y = (int) ((val >> 26) & 0xFFF);
        int z = (int) (val << 38 >> 38);
        return new int[]{x, y, z};
    }

    public static void writePosition(ByteBuf buf, int x, int y, int z) {
        long val = (((long) x & 0x3FFFFFFL) << 38) | (((long) y & 0xFFFL) << 26) | ((long) z & 0x3FFFFFFL);
        buf.writeLong(val);
    }

    public static UUID readUUID(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    /**
     * Converts a Java 1.12.2 JSON chat component into legacy section-sign text for MCPE 1.1.5.
     */
    public static String jsonChatToLegacy(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JsonElement root = JSON_PARSER.parse(json);
            StringBuilder sb = new StringBuilder();
            appendComponent(root, sb);
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private static void appendComponent(JsonElement el, StringBuilder sb) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonPrimitive()) {
            sb.append(el.getAsString());
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                appendComponent(child, sb);
            }
            return;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("color")) {
                sb.append(colorCode(obj.get("color").getAsString()));
            }
            if (obj.has("bold") && obj.get("bold").getAsBoolean()) {
                sb.append("\u00a7l");
            }
            if (obj.has("italic") && obj.get("italic").getAsBoolean()) {
                sb.append("\u00a7o");
            }
            if (obj.has("underlined") && obj.get("underlined").getAsBoolean()) {
                sb.append("\u00a7n");
            }
            if (obj.has("text")) {
                sb.append(obj.get("text").getAsString());
            } else if (obj.has("translate")) {
                String key = obj.get("translate").getAsString();
                if ("chat.type.text".equals(key) && obj.has("with")) {
                    JsonArray with = obj.getAsJsonArray("with");
                    if (with.size() >= 2) {
                        sb.append("<");
                        appendComponent(with.get(0), sb);
                        sb.append("> ");
                        appendComponent(with.get(1), sb);
                    }
                } else {
                    sb.append(key);
                    if (obj.has("with")) {
                        for (JsonElement w : obj.getAsJsonArray("with")) {
                            sb.append(" ");
                            appendComponent(w, sb);
                        }
                    }
                }
            }
            if (obj.has("extra")) {
                for (JsonElement extra : obj.getAsJsonArray("extra")) {
                    appendComponent(extra, sb);
                }
            }
        }
    }

    private static String colorCode(String name) {
        switch (name) {
            case "black": return "\u00a70";
            case "dark_blue": return "\u00a71";
            case "dark_green": return "\u00a72";
            case "dark_aqua": return "\u00a73";
            case "dark_red": return "\u00a74";
            case "dark_purple": return "\u00a75";
            case "gold": return "\u00a76";
            case "gray": return "\u00a77";
            case "dark_gray": return "\u00a78";
            case "blue": return "\u00a79";
            case "green": return "\u00a7a";
            case "aqua": return "\u00a7b";
            case "red": return "\u00a7c";
            case "light_purple": return "\u00a7d";
            case "yellow": return "\u00a7e";
            case "white": return "\u00a7f";
            default: return "";
        }
    }
}
