package com.jts.gjcxfzksh.data.cache;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对现存 v14 真实容器做只读迁移采样。默认跳过；验收时通过
 * -Dtrajectory.benchmark.bin=/.../spatial-067530.bin 显式启用。
 */
class TrajectoryV15BenchmarkTest {

    private static final int HEADER_BYTES = 64;
    private static final int OLD_ROW_BYTES = 36;
    private static final int COMPACT_ROW_BYTES = 16;

    @TempDir
    Path tempDir;

    @Test
    void benchmarkRealV14Container() throws Exception {
        String configured = System.getProperty("trajectory.benchmark.bin", "");
        Assumptions.assumeTrue(!configured.isBlank(), "未指定真实 v14 容器");
        Path bin = Path.of(configured);
        Path idx = bin.resolveSibling(bin.getFileName().toString().replace(".bin", ".idx"));
        Assumptions.assumeTrue(Files.isRegularFile(bin) && Files.isRegularFile(idx));

        LegacyIndex legacy = readLegacyIndex(idx);
        byte[] source = Files.readAllBytes(bin);
        Map<GeometryKey, Integer> linkIndexes = new LinkedHashMap<>();
        Map<Integer, Integer> vehicleModes = new HashMap<>();
        List<EncodedTile> tiles = new ArrayList<>(legacy.entries.size());
        long expandedFrameBytes = HEADER_BYTES;
        long compactFrameBytes = HEADER_BYTES;

        for (LegacyEntry entry : legacy.entries) {
            int begin = Math.addExact(HEADER_BYTES, Math.multiplyExact(entry.offset, OLD_ROW_BYTES));
            int bytes = Math.multiplyExact(entry.count, OLD_ROW_BYTES);
            byte[] expanded = Arrays.copyOfRange(source, begin, begin + bytes);
            byte[] expandedEncoded = Zstd.compress(expanded, 1);
            byte[] compact = compact(expanded, entry.count, linkIndexes, vehicleModes);
            byte[] compactEncoded = Zstd.compress(compact, 1);
            tiles.add(new EncodedTile(entry, compactEncoded));
            expandedFrameBytes += expandedEncoded.length;
            compactFrameBytes += compactEncoded.length;
        }

        long v3IndexBytes = HEADER_BYTES + (long) legacy.entries.size() * 40L;
        long dictionaryBytes = compressedDictionaries(linkIndexes, vehicleModes);
        long oldBytes = Files.size(bin) + Files.size(idx);
        long expandedZstdBytes = expandedFrameBytes + v3IndexBytes;
        long compactZstdBytes = compactFrameBytes + v3IndexBytes + dictionaryBytes;

        Path compactContainer = tempDir.resolve("spatial.zst");
        long position = HEADER_BYTES;
        try (FileChannel out = FileChannel.open(compactContainer,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write(ByteBuffer.allocate(HEADER_BYTES));
            for (EncodedTile tile : tiles) {
                tile.compressedOffset = position;
                out.write(ByteBuffer.wrap(tile.encoded));
                position += tile.encoded.length;
            }
        }

        Latency oldLatency = benchmarkOld(bin, legacy.entries);
        Latency newLatency = benchmarkCompact(compactContainer, tiles, linkIndexes.size());
        double oldMb = oldBytes / 1048576.0;
        double compactMb = compactZstdBytes / 1048576.0;
        System.out.printf("TRAJECTORY_V15_BENCHMARK file=%s rows=%d tiles=%d links=%d vehicles=%d "
                        + "oldMiB=%.3f expandedZstdMiB=%.3f compactZstdMiB=%.3f savedPct=%.2f "
                        + "oldQueryP50Ms=%.3f oldQueryP95Ms=%.3f newQueryP50Ms=%.3f newQueryP95Ms=%.3f%n",
                bin, legacy.totalRows, legacy.entries.size(), linkIndexes.size(), vehicleModes.size(),
                oldMb, expandedZstdBytes / 1048576.0, compactMb,
                100.0 * (oldBytes - compactZstdBytes) / oldBytes,
                oldLatency.p50Ms, oldLatency.p95Ms, newLatency.p50Ms, newLatency.p95Ms);
    }

    private static byte[] compact(
            byte[] expanded,
            int count,
            Map<GeometryKey, Integer> linkIndexes,
            Map<Integer, Integer> vehicleModes
    ) {
        ByteBuffer input = ByteBuffer.wrap(expanded).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(count * COMPACT_ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int start = Math.round(input.getFloat());
            int end = Math.round(input.getFloat());
            int x1 = input.getInt();
            int y1 = input.getInt();
            int x2 = input.getInt();
            int y2 = input.getInt();
            int mode = Math.round(input.getFloat());
            int vehicle = input.getInt();
            int distance = input.getInt();
            GeometryKey geometry = new GeometryKey(x1, y1, x2, y2, distance);
            int link = linkIndexes.computeIfAbsent(geometry, ignored -> linkIndexes.size());
            vehicleModes.putIfAbsent(vehicle, mode);
            output.putInt(start).putInt(end).putInt(vehicle).putInt(link);
        }
        return output.array();
    }

    private static long compressedDictionaries(
            Map<GeometryKey, Integer> linkIndexes,
            Map<Integer, Integer> vehicleModes
    ) throws Exception {
        ByteBuffer links = ByteBuffer.allocate(32 + linkIndexes.size() * 20).order(ByteOrder.LITTLE_ENDIAN);
        links.position(32);
        for (GeometryKey geometry : linkIndexes.keySet()) {
            links.putInt(geometry.x1).putInt(geometry.y1).putInt(geometry.x2).putInt(geometry.y2)
                    .putInt(geometry.distance);
        }
        ByteArrayOutputStream vehicles = new ByteArrayOutputStream();
        vehicles.write(new byte[16]);
        for (Map.Entry<Integer, Integer> entry : vehicleModes.entrySet()) {
            vehicles.write(entry.getValue());
            vehicles.write(new byte[3]);
            byte[] id = ("vehicle-" + entry.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            vehicles.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id.length).array());
            vehicles.write(id);
        }
        return (long) Zstd.compress(links.array(), 1).length
                + Zstd.compress(vehicles.toByteArray(), 1).length;
    }

    private static Latency benchmarkOld(Path bin, List<LegacyEntry> entries) throws Exception {
        try (FileChannel channel = FileChannel.open(bin, StandardOpenOption.READ)) {
            return benchmark(entries.size(), index -> {
                LegacyEntry entry = entries.get(index % entries.size());
                ByteBuffer raw = ByteBuffer.allocate(entry.count * OLD_ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, raw, HEADER_BYTES + (long) entry.offset * OLD_ROW_BYTES);
                raw.flip();
                ByteBuffer response = ByteBuffer.allocate(entry.count * OLD_ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int row = 0; row < entry.count; row++) {
                    int position = raw.position();
                    float start = raw.getFloat(position);
                    float end = raw.getFloat(position + 4);
                    if (start < entry.windowStart + 10 && end > entry.windowStart) {
                        response.put(raw.array(), position, OLD_ROW_BYTES);
                    }
                    raw.position(position + OLD_ROW_BYTES);
                }
                return response.position();
            });
        }
    }

    private static Latency benchmarkCompact(Path container, List<EncodedTile> tiles, int linkCount) throws Exception {
        try (FileChannel channel = FileChannel.open(container, StandardOpenOption.READ)) {
            return benchmark(tiles.size(), index -> {
                EncodedTile tile = tiles.get(index % tiles.size());
                ByteBuffer encoded = ByteBuffer.allocate(tile.encoded.length);
                readFully(channel, encoded, tile.compressedOffset);
                byte[] raw = Zstd.decompress(encoded.array(), tile.entry.count * COMPACT_ROW_BYTES);
                ByteBuffer compact = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer response = ByteBuffer.allocate(tile.entry.count * OLD_ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int row = 0; row < tile.entry.count; row++) {
                    int start = compact.getInt();
                    int end = compact.getInt();
                    int vehicle = compact.getInt();
                    int link = compact.getInt();
                    if (start < tile.entry.windowStart + 10 && end > tile.entry.windowStart) {
                        response.putFloat(start).putFloat(end);
                        response.putFloat(link % Math.max(1, linkCount)).putFloat(0).putFloat(0).putFloat(0);
                        response.putFloat(0).putInt(vehicle).putFloat(0);
                    }
                }
                return response.position();
            });
        }
    }

    private static Latency benchmark(int entryCount, ThrowingIntFunction operation) throws Exception {
        int iterations = 600;
        long checksum = 0;
        for (int i = 0; i < 40; i++) checksum += operation.apply((i * 97) % entryCount);
        long[] elapsed = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            int entry = (i * 97) % entryCount;
            long started = System.nanoTime();
            checksum += operation.apply(entry);
            elapsed[i] = System.nanoTime() - started;
        }
        if (checksum == Long.MIN_VALUE) throw new IllegalStateException("unreachable");
        Arrays.sort(elapsed);
        return new Latency(elapsed[iterations / 2] / 1_000_000.0,
                elapsed[(int) Math.floor(iterations * 0.95)] / 1_000_000.0);
    }

    private static LegacyIndex readLegacyIndex(Path idx) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(idx)).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != 0x49544a47 || Short.toUnsignedInt(buffer.getShort()) != 2
                || Short.toUnsignedInt(buffer.getShort()) != HEADER_BYTES) {
            throw new IllegalArgumentException("不是 v14 GJTI v2 索引: " + idx);
        }
        int chunkStart = buffer.getInt();
        buffer.getInt();
        int tiles = buffer.getInt();
        buffer.getInt();
        int rows = buffer.getInt();
        buffer.position(HEADER_BYTES);
        List<LegacyEntry> entries = new ArrayList<>(tiles);
        for (int i = 0; i < tiles; i++) {
            buffer.getInt();
            buffer.getInt();
            int offset = buffer.getInt();
            int count = buffer.getInt();
            buffer.position(buffer.position() + 16);
            entries.add(new LegacyEntry(offset, count, chunkStart));
        }
        entries.sort(Comparator.comparingInt(entry -> entry.offset));
        return new LegacyIndex(rows, entries);
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position) throws Exception {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) throw new IllegalStateException("unexpected eof");
            position += read;
        }
    }

    private record GeometryKey(int x1, int y1, int x2, int y2, int distance) { }
    private record LegacyEntry(int offset, int count, int windowStart) { }
    private record LegacyIndex(int totalRows, List<LegacyEntry> entries) { }
    private record Latency(double p50Ms, double p95Ms) { }
    private interface ThrowingIntFunction { int apply(int value) throws Exception; }

    private static final class EncodedTile {
        private final LegacyEntry entry;
        private final byte[] encoded;
        private long compressedOffset;

        private EncodedTile(LegacyEntry entry, byte[] encoded) {
            this.entry = entry;
            this.encoded = encoded;
        }
    }
}
