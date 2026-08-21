package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the lightweight real-passenger CSV set once from the auditable raw inputs.
 * The Python builder publishes a complete directory atomically; consumers never see
 * a partially generated set and keep using the existing cache pipeline afterwards.
 */
@Log4j2
@Component
public class RealPassengerAggregateBuilder {

    static final String PASSENGER_FOLDER = "客流数据";
    static final String CARD_FOLDER = "刷卡数据";
    static final String RUN_FOLDER = "运行数据";
    static final String GPS_FOLDER = "GPS数据";
    static final String AGGREGATE_FOLDER = "聚合数据";
    static final String READY_MARKER = ".aggregate-ready.json";
    private static final String SCRIPT_RESOURCE = "/real-passenger/build_real_passenger_flow_data.py";
    private static final String DEPARTURE_SCRIPT_RESOURCE = "/real-passenger/build_real_departure_cache.py";
    static final String DEPARTURE_OUTPUT = "班次客流明细.csv";
    private static final List<String> REQUIRED_OUTPUTS = List.of(
            "乘客行程明细.csv", "车辆到离站明细.csv", "线路小时客流.csv", "站点小时客流.csv",
            "断面小时客流.csv", "线路OD日统计.csv", "换乘明细.csv", "客群小时统计.csv",
            "车辆日运营统计.csv", "线路日运营统计.csv", "线路映射.csv", "数据字典.csv",
            "数据质量报告.csv", "区间运行时间统计.csv", "总体小时客流.csv",
            "线路组未定位小时客流.csv", "模块可用性说明.csv", DEPARTURE_OUTPUT);
    private static final List<String> LEGACY_REQUIRED_OUTPUTS = REQUIRED_OUTPUTS.stream()
            .filter(name -> !DEPARTURE_OUTPUT.equals(name)).toList();

    @Resource
    private MatsimConfig matsimConfig;

    @Value("${matsim.real-passenger-aggregate-builder-enabled:true}")
    private boolean enabled;

    @Value("${matsim.real-passenger-python-command:python3}")
    private String pythonCommand;

    @Value("${matsim.real-passenger-service-date-start:}")
    private String serviceDateStart;

    @Value("${matsim.real-passenger-service-date-end:}")
    private String serviceDateEnd;

    private final Map<String, BuildStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public Path passengerBase(String area) {
        return matsimConfig.realDataPath(area).resolve(PASSENGER_FOLDER);
    }

    public Path activeRoot(String area) {
        Path base = passengerBase(area);
        Path aggregate = base.resolve(AGGREGATE_FOLDER);
        // Once the raw-input layout is adopted, never silently fall back to stale
        // legacy aggregates in the parent directory while a new build is pending.
        return isComplete(aggregate) || hasRawInputs(base) ? aggregate : base;
    }

    public BuildStatus status(String area) {
        Path base = passengerBase(area);
        Path aggregate = base.resolve(AGGREGATE_FOLDER);
        if (isComplete(aggregate) && (!hasRawInputs(base) || markerMatchesConfiguredRange(aggregate))) {
            return new BuildStatus("ready", "原始数据已生成轻量化聚合 CSV", 100);
        }
        if (legacyOutputsExist(aggregate) && !Files.isRegularFile(aggregate.resolve(DEPARTURE_OUTPUT))) {
            return statuses.getOrDefault(area,
                    new BuildStatus("pending", "实际班次客流缓存待补建", 0));
        }
        if (!hasRawInputs(base)) {
            return new BuildStatus("not_configured", "未配置三类原始 CSV", 0);
        }
        return statuses.getOrDefault(area,
                new BuildStatus("pending", "原始数据待聚合", 0));
    }

    public BuildStatus startIfNeeded(String area) {
        BuildStatus current = status(area);
        if (!enabled || !"pending".equals(current.status())) return current;
        synchronized (locks.computeIfAbsent(area, ignored -> new Object())) {
            current = status(area);
            if (!"pending".equals(current.status())) return current;
            statuses.put(area, new BuildStatus("building", "正在清洗原始刷卡与车辆运行数据", 5));
            Thread.ofPlatform().daemon(true).name("real-passenger-aggregate-" + area).start(() -> {
                try {
                    ensureBuilt(area);
                } catch (RuntimeException error) {
                    log.error("真实客流原始数据聚合失败 area={} error={}", area, error.getMessage(), error);
                }
            });
            return statuses.get(area);
        }
    }

    public Path ensureBuilt(String area) {
        Path base = passengerBase(area);
        Path aggregate = base.resolve(AGGREGATE_FOLDER);
        if (isComplete(aggregate) && (!hasRawInputs(base) || markerMatchesConfiguredRange(aggregate))) return aggregate;
        if (!enabled) return activeRoot(area);
        synchronized (locks.computeIfAbsent(area, ignored -> new Object())) {
            if (isComplete(aggregate) && (!hasRawInputs(base) || markerMatchesConfiguredRange(aggregate))) return aggregate;
            if (legacyOutputsExist(aggregate) && !Files.isRegularFile(aggregate.resolve(DEPARTURE_OUTPUT))) {
                statuses.put(area, new BuildStatus("building", "正在匹配实际车辆班次与刷卡记录", 70));
                try {
                    runDepartureBuilder(area, aggregate);
                    requireComplete(aggregate);
                    statuses.put(area, new BuildStatus("ready", "实际班次客流缓存已就绪", 100));
                    return aggregate;
                } catch (Exception error) {
                    statuses.put(area, new BuildStatus("failed", error.getMessage(), 100));
                    if (error instanceof RuntimeException runtime) throw runtime;
                    throw new BusinessException("真实班次客流缓存补建失败", error);
                }
            }
            if (!hasRawInputs(base)) return activeRoot(area);
            statuses.put(area, new BuildStatus("building", "正在生成轻量化聚合 CSV", 10));
            try {
                Files.createDirectories(base);
                if (Files.exists(aggregate)) {
                    Path invalid = base.resolve("." + AGGREGATE_FOLDER + ".invalid-" + System.currentTimeMillis());
                    Files.move(aggregate, invalid, StandardCopyOption.ATOMIC_MOVE);
                }
                RawInputs inputs = locateInputs(base);
                runBuilder(area, inputs, aggregate);
                runDepartureBuilder(area, aggregate);
                requireComplete(aggregate);
                String fingerprint = fingerprint(inputs, matsimConfig.realDataPath(area).resolve("公交线路站点"));
                Files.writeString(aggregate.resolve(READY_MARKER), "{\n"
                        + "  \"status\": \"ready\",\n"
                        + "  \"generatedAt\": \"" + Instant.now() + "\",\n"
                        + "  \"serviceDateStart\": \"" + configuredDate(serviceDateStart) + "\",\n"
                        + "  \"serviceDateEnd\": \"" + configuredDate(serviceDateEnd) + "\",\n"
                        + "  \"sourceFingerprint\": \"" + fingerprint + "\"\n"
                        + "}\n", StandardCharsets.UTF_8);
                statuses.put(area, new BuildStatus("ready", "轻量化聚合 CSV 已就绪", 100));
                return aggregate;
            } catch (Exception error) {
                statuses.put(area, new BuildStatus("failed", error.getMessage(), 100));
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new BusinessException("真实客流原始数据聚合失败", error);
            }
        }
    }

    private void runBuilder(String area, RawInputs inputs, Path output) throws Exception {
        Path script = extractScript(SCRIPT_RESOURCE, "real-passenger-builder-");
        try {
            Path authority = matsimConfig.realDataPath(area).resolve("公交线路站点");
            List<String> command = new java.util.ArrayList<>(List.of(
                    pythonCommand, script.toString(),
                    "--card", inputs.card().toString(),
                    "--run", inputs.run().toString(),
                    "--gps", inputs.gps().toString(),
                    "--authority", authority.toString(),
                    "--output", output.toString()));
            if (serviceDateStart != null && !serviceDateStart.isBlank()) {
                command.addAll(List.of("--service-date-start", serviceDateStart.trim()));
            }
            if (serviceDateEnd != null && !serviceDateEnd.isBlank()) {
                command.addAll(List.of("--service-date-end", serviceDateEnd.trim()));
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            log.info("开始生成真实客流聚合数据 area={} output={}", area, output);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("真实客流聚合 area={} {}", area, line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) throw new BusinessException("真实客流聚合脚本退出码: " + exit);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private void runDepartureBuilder(String area, Path aggregate) throws Exception {
        Path script = extractScript(DEPARTURE_SCRIPT_RESOURCE, "real-departure-builder-");
        try {
            Path authority = matsimConfig.realDataPath(area).resolve("公交线路站点");
            ProcessBuilder builder = new ProcessBuilder(
                    pythonCommand, script.toString(),
                    "--aggregate", aggregate.toString(),
                    "--authority", authority.toString());
            builder.redirectErrorStream(true);
            log.info("开始生成真实实际班次客流缓存 area={} aggregate={}", area, aggregate);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("真实班次缓存 area={} {}", area, line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) throw new BusinessException("真实班次缓存脚本退出码: " + exit);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private Path extractScript(String resource, String prefix) throws IOException {
        try (InputStream input = RealPassengerAggregateBuilder.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("缺少内置真实客流聚合脚本: " + resource);
            Path script = Files.createTempFile(prefix, ".py");
            Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            return script;
        }
    }

    private RawInputs locateInputs(Path base) {
        return new RawInputs(singleCsv(base.resolve(CARD_FOLDER), "刷卡"),
                singleCsv(base.resolve(RUN_FOLDER), "运行"),
                singleCsv(base.resolve(GPS_FOLDER), "GPS"));
    }

    private Path singleCsv(Path folder, String label) {
        if (!Files.isDirectory(folder)) throw new BusinessException(label + "数据目录不存在: " + folder);
        try (var stream = Files.list(folder)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .filter(path -> !path.getFileName().toString().startsWith("._"))
                    .sorted().toList();
            if (files.size() != 1) {
                throw new BusinessException(label + "数据目录必须且只能放置一个 CSV: " + folder
                        + "，当前为 " + files.size() + " 个");
            }
            return files.getFirst().toAbsolutePath().normalize();
        } catch (IOException error) {
            throw new BusinessException("扫描" + label + "数据目录失败: " + folder, error);
        }
    }

    private boolean hasRawInputs(Path base) {
        return Files.isDirectory(base.resolve(CARD_FOLDER))
                || Files.isDirectory(base.resolve(RUN_FOLDER))
                || Files.isDirectory(base.resolve(GPS_FOLDER));
    }

    private boolean isComplete(Path folder) {
        // The Python builder publishes the directory with os.replace only after its own
        // validation succeeds, so a complete required set is already a valid legacy/manual
        // publication. Platform-built sets additionally carry READY_MARKER provenance.
        return requiredOutputsExist(folder);
    }

    private boolean markerMatchesConfiguredRange(Path folder) {
        Path marker = folder.resolve(READY_MARKER);
        if (!Files.isRegularFile(marker)) return false;
        try {
            String content = Files.readString(marker, StandardCharsets.UTF_8);
            return content.contains("\"serviceDateStart\": \"" + configuredDate(serviceDateStart) + "\"")
                    && content.contains("\"serviceDateEnd\": \"" + configuredDate(serviceDateEnd) + "\"");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String configuredDate(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean requiredOutputsExist(Path folder) {
        return Files.isDirectory(folder)
                && REQUIRED_OUTPUTS.stream().allMatch(name -> Files.isRegularFile(folder.resolve(name)));
    }

    private boolean legacyOutputsExist(Path folder) {
        return Files.isDirectory(folder)
                && LEGACY_REQUIRED_OUTPUTS.stream().allMatch(name -> Files.isRegularFile(folder.resolve(name)));
    }

    private void requireComplete(Path folder) {
        List<String> missing = REQUIRED_OUTPUTS.stream()
                .filter(name -> !Files.isRegularFile(folder.resolve(name))).toList();
        if (!missing.isEmpty()) throw new BusinessException("聚合脚本输出不完整: " + missing);
    }

    private String fingerprint(RawInputs inputs, Path authority) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path path : List.of(inputs.card(), inputs.run(), inputs.gps(),
                authority.resolve("线路/routes.shp"), authority.resolve("线路/routes.dbf"),
                authority.resolve("站点/stops.shp"), authority.resolve("站点/stops.dbf"),
                authority.resolve("站点/line_stop_sequence.csv"))) {
            String value = path.toAbsolutePath().normalize() + ":" + Files.size(path) + ":"
                    + Files.getLastModifiedTime(path).toMillis() + "\n";
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public Map<String, Object> statusPayload(String area) {
        BuildStatus status = status(area);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aggregationStatus", status.status());
        result.put("aggregationMessage", status.message());
        result.put("aggregationProgressPercent", status.progressPercent());
        return result;
    }

    public record BuildStatus(String status, String message, int progressPercent) {
    }

    private record RawInputs(Path card, Path run, Path gps) {
    }
}
