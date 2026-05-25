package com.jts.gjcxfzksh.api.common;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.pt.PTLink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

public final class TileBinaryEncoder {

    private static final int HEADER_BYTES = 64;
    private static final int VERSION = 1;
    private static final int LAYOUT_COLUMNAR_FLOAT32 = 1;

    private TileBinaryEncoder() {
    }

    public static byte[] encodeLinks(List<?> links) {
        int count = 0;
        double originX = 0d;
        double originY = 0d;
        boolean hasOrigin = false;
        if (links != null) {
            for (Object item : links) {
                LinkValue link = readLink(item);
                if (link == null) continue;
                if (!hasOrigin) {
                    originX = link.fromX();
                    originY = link.fromY();
                    hasOrigin = true;
                }
                count++;
            }
        }

        int hashOffset = HEADER_BYTES;
        int hash2Offset = hashOffset + count * Integer.BYTES;
        int sourceOffset = hash2Offset + count * Integer.BYTES;
        int targetOffset = sourceOffset + count * 2 * Float.BYTES;
        int flowOffset = targetOffset + count * 2 * Float.BYTES;
        int lengthOffset = flowOffset + count * Float.BYTES;
        int lanesOffset = lengthOffset + count * Float.BYTES;
        int totalBytes = lanesOffset + count * Float.BYTES;

        ByteBuffer buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(0, (byte) 'G');
        buffer.put(1, (byte) 'J');
        buffer.put(2, (byte) 'N');
        buffer.put(3, (byte) 'B');
        buffer.putShort(4, (short) VERSION);
        buffer.putShort(6, (short) HEADER_BYTES);
        buffer.putInt(8, count);
        buffer.putInt(12, LAYOUT_COLUMNAR_FLOAT32);
        buffer.putDouble(16, originX);
        buffer.putDouble(24, originY);
        buffer.putInt(32, hashOffset);
        buffer.putInt(36, hash2Offset);
        buffer.putInt(40, sourceOffset);
        buffer.putInt(44, targetOffset);
        buffer.putInt(48, flowOffset);
        buffer.putInt(52, lengthOffset);
        buffer.putInt(56, lanesOffset);

        if (links == null || count == 0) {
            return buffer.array();
        }

        int index = 0;
        for (Object item : links) {
            LinkValue link = readLink(item);
            if (link == null) continue;
            buffer.putInt(hashOffset + index * Integer.BYTES, stableHash(link));
            buffer.putInt(hash2Offset + index * Integer.BYTES, secondaryHash(link));
            buffer.putFloat(sourceOffset + index * 2 * Float.BYTES, link.fromX() - (float) originX);
            buffer.putFloat(sourceOffset + (index * 2 + 1) * Float.BYTES, link.fromY() - (float) originY);
            buffer.putFloat(targetOffset + index * 2 * Float.BYTES, link.toX() - (float) originX);
            buffer.putFloat(targetOffset + (index * 2 + 1) * Float.BYTES, link.toY() - (float) originY);
            buffer.putFloat(flowOffset + index * Float.BYTES, link.flow());
            buffer.putFloat(lengthOffset + index * Float.BYTES, link.length());
            buffer.putFloat(lanesOffset + index * Float.BYTES, link.lanes());
            index++;
        }
        return buffer.array();
    }

    private static LinkValue readLink(Object item) {
        if (item instanceof PTLink link) {
            if (link.getFrom() == null || link.getTo() == null) return null;
            Float fromX = link.getFrom().getX();
            Float fromY = link.getFrom().getY();
            Float toX = link.getTo().getX();
            Float toY = link.getTo().getY();
            if (fromX == null || fromY == null || toX == null || toY == null) return null;
            return new LinkValue(
                    link.getLinkId(),
                    fromX,
                    fromY,
                    toX,
                    toY,
                    finite(link.getFlow(), 0f),
                    finite(link.getLength(), 0f),
                    finite(link.getLanes(), 1f)
            );
        }
        if (item instanceof Map<?, ?> map) {
            Object from = map.get("from");
            Object to = map.get("to");
            Float fromX = readCoord(from, "x");
            Float fromY = readCoord(from, "y");
            Float toX = readCoord(to, "x");
            Float toY = readCoord(to, "y");
            if (fromX == null || fromY == null || toX == null || toY == null) return null;
            return new LinkValue(
                    asString(map.get("linkId")),
                    fromX,
                    fromY,
                    toX,
                    toY,
                    finite(map.get("flow"), 0f),
                    finite(map.get("length"), 0f),
                    finite(map.get("lanes"), 1f)
            );
        }
        return null;
    }

    private static float finite(Double value, float fallback) {
        if (value == null || !Double.isFinite(value)) return fallback;
        return value.floatValue();
    }

    private static float finite(Object value, float fallback) {
        if (!(value instanceof Number number)) return fallback;
        double next = number.doubleValue();
        if (!Double.isFinite(next)) return fallback;
        return (float) next;
    }

    private static Float readCoord(Object coord, String key) {
        if (coord instanceof PTCoord ptCoord) {
            return switch (key) {
                case "x" -> ptCoord.getX();
                case "y" -> ptCoord.getY();
                default -> null;
            };
        }
        if (coord instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                return number.floatValue();
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int stableHash(LinkValue link) {
        String id = link.linkId();
        if (id != null && !id.isBlank()) return id.hashCode();
        int result = Float.hashCode(link.fromX());
        result = 31 * result + Float.hashCode(link.fromY());
        result = 31 * result + Float.hashCode(link.toX());
        result = 31 * result + Float.hashCode(link.toY());
        return result;
    }

    private static int secondaryHash(LinkValue link) {
        String id = link.linkId();
        if (id == null || id.isBlank()) {
            id = link.fromX() + "," + link.fromY() + "," + link.toX() + "," + link.toY();
        }
        int hash = 0x811c9dc5;
        for (int i = 0; i < id.length(); i++) {
            hash ^= id.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private record LinkValue(
            String linkId,
            float fromX,
            float fromY,
            float toX,
            float toY,
            float flow,
            float length,
            float lanes
    ) {
    }
}
