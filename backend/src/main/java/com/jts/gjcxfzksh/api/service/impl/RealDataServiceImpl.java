package com.jts.gjcxfzksh.api.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.jts.gjcxfzksh.api.model.params.RealDataCommitParam;
import com.jts.gjcxfzksh.api.model.params.RealDataParam;
import com.jts.gjcxfzksh.api.model.vo.RealDataExportVO;
import com.jts.gjcxfzksh.api.service.RealDataService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import jakarta.annotation.Resource;
import org.geotools.api.feature.Property;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.filter.Filter;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class RealDataServiceImpl implements RealDataService {

    private static final String BUS_LINE_FOLDER = "公交线路站点/线路";
    private static final String BUS_STATION_FOLDER = "公交线路站点/站点";
    private static final String BUS_DEPOT_FOLDER = "公交场站";
    private static final String ADMIN_AREA_FOLDER = "行政区范围";
    private static final String EDIT_STATE_FOLDER = "_edit_state";
    private static final String VERSION_FOLDER = "_versions";
    private static final DateTimeFormatter VERSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final List<String> STANDARD_ROUTE_FIELDS = List.of(
            "line_id", "dir", "route_id", "first", "last", "interval", "mode", "name", "price", "company"
    );
    private static final List<String> STANDARD_STOP_FIELDS = List.of(
            "line_id", "dir", "stop_id", "stop_name", "seq", "lon", "lat"
    );
    // 物理唯一站台格式：站点 SHP 每个站台一个点，线路经停关系存于同目录 CSV。
    private static final List<String> UNIQUE_STOP_FIELDS = List.of(
            "stop_id", "stop_name", "lon", "lat"
    );
    private static final List<String> DERIVED_ROUTE_FIELDS = List.of(
            "len_km", "directness", "stop_count", "avg_stop_m"
    );
    private static final List<String> DERIVED_STOP_FIELDS = List.of("route_cnt");
    private static final Set<String> DERIVED_FIELDS = Set.of(
            "len_km", "directness", "stop_count", "avg_stop_m", "route_cnt"
    );
    private static final Set<String> ROUTE_STOP_RELATION_FIELDS = Set.of("line_id", "dir", "seq");
    private static final String STATION_SEQUENCE_CSV = "line_stop_sequence.csv";
    private static final String GEOMETRY_FIELD = "geometry";
    private static final String EXISTENCE_FIELD = "__existence__";
    private static final String DELETION_FIELD = "__deletion__";
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;
    private static final double COVERAGE_300_METERS = 300.0;
    private static final double COVERAGE_500_METERS = 500.0;
    private static final int MAX_EVIDENCE_IMAGES = 6;
    private static final int MAX_EVIDENCE_DATA_URL_LENGTH = 2_000_000;
    private static final int MAX_REAL_DATA_CACHE_ENTRIES = 6;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Resource
    MatsimConfig matsimConfig;

    private final Map<String, CachedOverview> overviewCache = new ConcurrentHashMap<>();
    private final Map<String, CachedRealData> realDataCache = new ConcurrentHashMap<>();
    private final Map<String, PendingShpComparison> pendingShpComparisons = new ConcurrentHashMap<>();

    @Override
    public List<String> areaList() {
        return matsimConfig.areaNames();
    }

    @Override
    public Map<String, Object> adminDistricts(String areaName) {
        String safeAreaName = safeText(areaName);
        if (safeAreaName.isBlank()) {
            throw new BusinessException("区域名称不能为空");
        }
        Path root = matsimConfig.realDataPath(safeAreaName);
        Map<String, Object> collection = readFirstShp(root.resolve(ADMIN_AREA_FOLDER));
        List<Map<String, Object>> features = mutableMapList(collection.get("features"));
        List<String> districts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> feature : features) {
            Map<String, Object> properties = featureProperties(feature);
            String name = adminDistrictName(properties);
            if (name.isBlank()) {
                continue;
            }
            properties.put("_districtName", name);
            if (seen.add(name)) {
                districts.add(name);
            }
        }
        collection.put("features", features);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("areaName", safeAreaName);
        result.put("districts", districts);
        result.put("collection", collection);
        return result;
    }

    @Override
    public Map<String, Object> busLineStation(String areaName, String versionId) {
        Path root = matsimConfig.realDataPath(areaName);
        Map<String, Object> state = readEditState(root);
        TargetVersion target = safeText(versionId).isBlank() ? activeTargetVersion(state) : targetVersion(versionId, mutableMapList(state.get("versions")));
        Path dataRoot = dataRootForVersion(root, target);
        String cacheKey = realDataCacheKey(areaName, target.id());
        String signature = realDataSignature(root, dataRoot);
        CachedRealData cached = realDataCache.get(cacheKey);
        if (cached != null && cached.signature().equals(signature)) {
            return cached.data();
        }
        Map<String, Object> lines = readStandardShp(dataRoot.resolve(BUS_LINE_FOLDER), STANDARD_ROUTE_FIELDS, LineString.class, "线路");
        Map<String, Object> routeStops = readStationData(dataRoot.resolve(BUS_STATION_FOLDER));
        Map<String, Object> depots = readFirstShp(dataRoot.resolve(BUS_DEPOT_FOLDER));
        if (!target.materializedData()) {
            applyCurrentEdits(target.operations(), lines, routeStops, depots);
        }
        enrichDerivedAttributes(lines, routeStops);
        Map<String, Object> stations = uniqueStationsFromRouteStops(routeStops);
        int lineCount = numberValue(lines.get("featureCount")).intValue();
        int stationCount = numberValue(stations.get("featureCount")).intValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("areaName", areaName);
        result.put("versionId", target.id());
        result.put("versionLabel", target.label());
        result.put("lines", lines);
        result.put("stations", stations);
        result.put("routeStops", routeStops);
        result.put("depots", depots);
        result.put("bounds", mergeBounds(mergeBounds(boundsOf(lines), boundsOf(stations)), boundsOf(depots)));
        result.put("overview", overview(areaName, dataRoot, stateFile(root), lineCount, stationCount, stations));
        result.put("history", historySummary(root));
        realDataCache.put(cacheKey, new CachedRealData(signature, result, System.currentTimeMillis()));
        trimRealDataCache();
        return result;
    }

    @Override
    public Map<String, Object> history(String areaName) {
        String safeAreaName = safeText(areaName);
        if (safeAreaName.isBlank()) {
            throw new BusinessException("区域名称不能为空");
        }
        Path root = matsimConfig.realDataPath(safeAreaName);
        Map<String, Object> result = historySummary(root);
        result.put("areaName", safeAreaName);
        result.put("versions", historyVersions(readEditState(root)));
        return result;
    }

    @Override
    public RealDataExportVO exportVersion(String areaName, String versionId, String datasetType, String format) {
        String safeAreaName = safeText(areaName);
        String safeDatasetType = normalizeDatasetType(datasetType);
        String safeFormat = safeText(format).toLowerCase(Locale.ROOT);
        if (safeAreaName.isBlank()) {
            throw new BusinessException("区域名称不能为空");
        }
        if (!"line".equals(safeDatasetType) && !"station".equals(safeDatasetType) && !"depot".equals(safeDatasetType)) {
            throw new BusinessException("仅支持导出线路、站点或场站数据");
        }
        if (!"csv".equals(safeFormat) && !"shp".equals(safeFormat)) {
            throw new BusinessException("仅支持导出 CSV 或 SHP");
        }

        Path root = matsimConfig.realDataPath(safeAreaName);
        Map<String, Object> state = readEditState(root);
        TargetVersion target = safeText(versionId).isBlank()
                ? activeTargetVersion(state)
                : targetVersion(versionId, mutableMapList(state.get("versions")));
        VersionCollections collections = versionCollections(root, target);
        String versionLabel = "__base__".equals(target.id()) ? "原始数据" : target.id();
        String datasetLabel = datasetTypeLabel(safeDatasetType);
        String filePrefix = exportFileNamePart(safeAreaName) + "_" + exportFileNamePart(versionLabel) + "_" + datasetLabel;

        ExportDataset exportDataset = exportDataset(collections, safeDatasetType);
        if ("csv".equals(safeFormat)) {
            byte[] content = exportCsv(exportDataset);
            return new RealDataExportVO(filePrefix + "_属性表.csv", "text/csv;charset=UTF-8", content);
        }

        byte[] content = exportShpArchive(safeDatasetType, collections, exportDataset);
        return new RealDataExportVO(filePrefix + "_SHP.zip", "application/zip", content);
    }

    private VersionCollections versionCollections(Path root, TargetVersion target) {
        Path dataRoot = dataRootForVersion(root, target);
        Map<String, Object> lines = readStandardShp(dataRoot.resolve(BUS_LINE_FOLDER), STANDARD_ROUTE_FIELDS, LineString.class, "线路");
        Map<String, Object> routeStops = readStationData(dataRoot.resolve(BUS_STATION_FOLDER));
        Map<String, Object> depots = readFirstShp(dataRoot.resolve(BUS_DEPOT_FOLDER));
        if (!target.materializedData()) {
            applyCurrentEdits(target.operations(), lines, routeStops, depots);
        }
        enrichDerivedAttributes(lines, routeStops);
        return new VersionCollections(dataRoot, lines, routeStops, depots);
    }

    private byte[] exportCsv(ExportDataset dataset) {
        List<Map<String, Object>> features = mutableMapList(dataset.collection().get("features"));
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(dataset.fields().stream().map(this::csvCell).collect(java.util.stream.Collectors.joining(","))).append('\n');
        for (Map<String, Object> feature : features) {
            Map<String, Object> properties = featureProperties(feature);
            if ("station".equals(dataset.datasetType())) {
                properties.putIfAbsent("lon", firstCoordinateValue(feature, 0));
                properties.putIfAbsent("lat", firstCoordinateValue(feature, 1));
            }
            boolean first = true;
            for (String field : dataset.fields()) {
                if (!first) csv.append(',');
                csv.append(csvCell(safeText(properties.get(field))));
                first = false;
            }
            csv.append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ExportDataset exportDataset(VersionCollections collections, String datasetType) {
        Map<String, Object> collection = switch (datasetType) {
            case "line" -> collections.lines();
            case "station" -> uniqueStationsFromRouteStops(collections.routeStops());
            default -> collections.depots();
        };
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if ("line".equals(datasetType)) {
            fields.addAll(STANDARD_ROUTE_FIELDS);
        } else if ("station".equals(datasetType)) {
            fields.addAll(UNIQUE_STOP_FIELDS);
        }
        fields.addAll(stringList(collection.get("attributeFields")));
        for (Map<String, Object> feature : mutableMapList(collection.get("features"))) {
            featureProperties(feature).keySet().stream()
                    .filter(key -> !key.startsWith("_"))
                    .filter(key -> !"station".equals(datasetType) || !ROUTE_STOP_RELATION_FIELDS.contains(key))
                    .forEach(fields::add);
        }
        if ("line".equals(datasetType)) {
            fields.addAll(DERIVED_ROUTE_FIELDS);
        } else if ("station".equals(datasetType)) {
            fields.addAll(DERIVED_STOP_FIELDS);
        }
        return new ExportDataset(datasetType, collection, List.copyOf(fields));
    }

    private byte[] exportShpArchive(String datasetType, VersionCollections collections, ExportDataset dataset) {
        Path sourceFolder = collections.dataRoot().resolve(datasetFolder(datasetType));
        Path exportRoot = null;
        try {
            File sourceShp = findFirstShp(sourceFolder);
            if (sourceShp == null) {
                throw new BusinessException("缺少" + datasetTypeLabel(datasetType) + "SHP: " + sourceFolder);
            }
            exportRoot = Files.createTempDirectory("real-data-version-export-");
            Path folderToZip = exportRoot.resolve(datasetTypeLabel(datasetType));
            Files.createDirectories(folderToZip);
            Path outputShp = folderToZip.resolve(sourceShp.getName());
            Class<? extends Geometry> geometryType = switch (datasetType) {
                case "line" -> LineString.class;
                case "station" -> Point.class;
                default -> null;
            };
            writeNewShapefile(sourceShp, outputShp, dataset.fields(), geometryType, datasetTypeLabel(datasetType), dataset.collection());
            if ("station".equals(datasetType)) {
                writeSequenceCsv(folderToZip.resolve(STATION_SEQUENCE_CSV), collections.routeStops());
            }
            return zipDatasetFolder(folderToZip, datasetTypeLabel(datasetType));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("导出历史版本 SHP 失败", error);
        } finally {
            deleteRecursively(exportRoot);
        }
    }

    private String datasetFolder(String datasetType) {
        return switch (datasetType) {
            case "line" -> BUS_LINE_FOLDER;
            case "station" -> BUS_STATION_FOLDER;
            case "depot" -> BUS_DEPOT_FOLDER;
            default -> throw new BusinessException("数据类型无效");
        };
    }

    private byte[] zipDatasetFolder(Path folder, String rootName) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new BusinessException("导出目录不存在: " + folder);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
             Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("._") || ".DS_Store".equals(name)) continue;
                String entryName = rootName + "/" + folder.relativize(path).toString().replace(File.separatorChar, '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private String exportFileNamePart(String value) {
        String safe = safeText(value).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return safe.isBlank() ? "数据" : safe;
    }

    @Override
    public synchronized Map<String, Object> commitEdits(String username, RealDataCommitParam param) {
        String areaName = safeText(param == null ? null : param.getAreaName());
        String datasetType = normalizeDatasetType(param == null ? null : param.getDatasetType());
        List<Map<String, Object>> operations = param == null || param.getOperations() == null ? List.of() : param.getOperations();
        if (areaName.isBlank()) {
            throw new BusinessException("区域名称不能为空");
        }
        if (datasetType.isBlank()) {
            throw new BusinessException("数据类型无效");
        }
        if (operations.isEmpty()) {
            throw new BusinessException("没有需要提交的修改");
        }
        if ("all".equals(datasetType) && operations.stream()
                .anyMatch(operation -> normalizeDatasetType(textValue(operation.get("datasetType"))).isBlank()
                        || "all".equals(normalizeDatasetType(textValue(operation.get("datasetType")))))) {
            throw new BusinessException("综合提交中的每条修改都必须标明线路、站点或场站类型");
        }
        if (operations.stream().anyMatch(this::isUnconfirmedShpDeletion)) {
            throw new BusinessException("上传 SHP 识别出的疑似删除项必须逐项确认后才能提交");
        }
        if (param.getBaseRevision() == null || safeText(param.getBaseVersionId()).isBlank()) {
            throw new BusinessException("缺少数据版本信息，请刷新当前区域后再提交");
        }
        if (safeText(param == null ? null : param.getMessage()).isBlank()) {
            throw new BusinessException("请填写本次修改信息");
        }
        List<Map<String, Object>> evidenceImages = normalizeEvidenceImages(param == null ? null : param.getEvidenceImages());

        Path root = matsimConfig.realDataPath(areaName);
        assertFreshRevision(root, param == null ? null : param.getBaseRevision());
        validatePendingShpOperations(username, areaName, operations, root);
        String versionId = LocalDateTime.now().format(VERSION_TIME_FORMAT) + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path versionDir = root.resolve(VERSION_FOLDER).resolve(versionId);
        boolean stateSaved = false;
        try {
            Map<String, Object> state = readEditState(root);
            assertFreshBaseVersion(state, param == null ? null : param.getBaseVersionId());
            TargetVersion active = activeTargetVersion(state);
            Files.createDirectories(versionDir);
            snapshotCurrentData(dataRootForVersion(root, active), versionDir);
            List<Map<String, Object>> operationsToMaterialize = operationsForMaterialization(active, datasetType, operations);
            materializeVersionShp(versionDir, operationsToMaterialize);
            Map<String, Object> manifest = buildManifest(versionId, areaName, datasetType, username, param.getMessage(), operations, active.id(), evidenceImages);
            manifest.put("materializedData", true);
            Map<String, Object> savedManifest = appendCurrentEdits(root, manifest);
            stateSaved = true;
            Files.writeString(versionDir.resolve("manifest.json"), JSON.toJSONString(savedManifest), StandardCharsets.UTF_8);
            invalidateAreaRealDataCaches(areaName);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("versionId", versionId);
            result.put("areaName", areaName);
            result.put("datasetType", datasetType);
            result.put("operationCount", operations.size());
            result.put("revision", savedManifest.get("revision"));
            result.put("history", historySummary(root));
            result.put("versionPath", versionDir.toString());
            removeCommittedShpComparisons(operations);
            return result;
        } catch (BusinessException error) {
            if (!stateSaved) {
                deleteRecursively(versionDir);
            }
            throw error;
        } catch (Exception error) {
            if (!stateSaved) {
                deleteRecursively(versionDir);
            }
            throw new BusinessException("提交真实数据修改失败", error);
        }
    }

    @Override
    public synchronized Map<String, Object> revertEdits(String username, RealDataParam param) {
        String areaName = safeText(param == null ? null : param.getAreaName());
        String targetVersionId = safeText(param == null ? null : param.getVersionId());
        if (areaName.isBlank()) {
            throw new BusinessException("区域名称不能为空");
        }
        if (targetVersionId.isBlank()) {
            throw new BusinessException("切换版本不能为空");
        }
        Path root = matsimConfig.realDataPath(areaName);
        assertFreshRevision(root, param == null ? null : param.getBaseRevision());

        Map<String, Object> state = readEditState(root);
        List<Map<String, Object>> versions = mutableMapList(state.get("versions"));
        TargetVersion target = targetVersion(targetVersionId, versions);
        List<String> activeOperationIds = new ArrayList<>(target.operationIds());
        Map<String, Map<String, Object>> operationCatalog = operationCatalog(versions);
        List<Map<String, Object>> activeOperations = operationsByIds(activeOperationIds, operationCatalog);

        try {
            Map<String, Object> savedState = switchActiveVersion(root, state, target, activeOperations, username);
            invalidateAreaRealDataCaches(areaName);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("versionId", target.id());
            result.put("areaName", areaName);
            result.put("activeVersionId", target.id());
            result.put("activeDataVersionId", target.dataVersionId());
            result.put("revision", savedState.get("revision"));
            result.put("history", historySummary(root));
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("切换真实数据版本失败", error);
        }
    }

    @Override
    public Map<String, Object> compareUpload(String username, String areaName, String datasetType, List<MultipartFile> files) {
        String safeAreaName = safeText(areaName);
        String safeDatasetType = normalizeDatasetType(datasetType);
        if (safeAreaName.isBlank()) {
            throw new BusinessException("区域名称不能为空");
        }
        if (!"line".equals(safeDatasetType) && !"station".equals(safeDatasetType) && !"depot".equals(safeDatasetType)) {
            throw new BusinessException("仅支持上传标准线路、站点或场站 SHP");
        }
        if (files == null || files.isEmpty()) {
            throw new BusinessException("请上传 SHP 压缩包或完整配套文件");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("real-data-shp-upload-");
            saveUploadedShpFiles(files, tempDir);
            File uploadedShp = findFirstShpRecursively(tempDir);
            if (uploadedShp == null) {
                throw new BusinessException("上传文件中未找到 .shp 文件");
            }
            assertShpCompanionFiles(uploadedShp);

            List<String> expectedFields = switch (safeDatasetType) {
                case "line" -> STANDARD_ROUTE_FIELDS;
                case "station" -> STANDARD_STOP_FIELDS;
                default -> List.of();
            };
            // 场站字段不固定，不做严格字段/几何校验；线路、站点沿用标准模板校验。
            Class<? extends Geometry> expectedGeometry = switch (safeDatasetType) {
                case "line" -> LineString.class;
                case "station" -> Point.class;
                default -> null;
            };
            List<String> expectedFieldsForRead = expectedFields.isEmpty() ? null : expectedFields;
            Map<String, Object> uploaded;
            if ("station".equals(safeDatasetType) && isUniqueStationShp(uploadedShp)) {
                // 物理唯一站台格式上传：站点 SHP + 同包内 line_stop_sequence.csv
                Map<String, Object> uniqueStops = readShp(uploadedShp, UNIQUE_STOP_FIELDS, Point.class, "站点");
                uploaded = routeStopsFromSequenceCsv(uploadedShp.toPath().getParent(), uniqueStops);
            } else {
                uploaded = readShp(uploadedShp, expectedFieldsForRead, expectedGeometry, datasetTypeLabel(safeDatasetType));
            }
            stripDerivedProperties(uploaded);

            Path root = matsimConfig.realDataPath(safeAreaName);
            Map<String, Object> state = readEditState(root);
            TargetVersion target = activeTargetVersion(state);
            long comparisonRevision = stateRevision(state);
            Path dataRoot = dataRootForVersion(root, target);
            Map<String, Object> current = switch (safeDatasetType) {
                case "line" -> readStandardShp(dataRoot.resolve(BUS_LINE_FOLDER), STANDARD_ROUTE_FIELDS, LineString.class, "线路");
                case "station" -> readStationData(dataRoot.resolve(BUS_STATION_FOLDER));
                default -> readFirstShp(dataRoot.resolve(BUS_DEPOT_FOLDER));
            };
            if (!target.materializedData()) {
                applyUploadComparableEdits(target.operations(), current, safeDatasetType);
            }

            UploadDiffResult diff = diffUploadedFeatures(
                    current,
                    uploaded,
                    safeDatasetType,
                    safeAreaName,
                    target.operations()
            );
            List<Map<String, Object>> operations = diff.operations();
            String comparisonToken = registerShpComparison(
                    username,
                    safeAreaName,
                    comparisonRevision,
                    target.id(),
                    operations
            );
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("areaName", safeAreaName);
            result.put("datasetType", safeDatasetType);
            result.put("fileName", uploadedShp.getName());
            result.put("fieldSchema", expectedFields);
            result.put("operationCount", operations.size());
            result.put("candidateDeletionCount", diff.candidateDeletionCount());
            result.put("protectedFeatureCount", diff.protectedFeatureCount());
            result.put("protectedFieldCount", diff.protectedFieldCount());
            result.put("skippedByManualDeletionCount", diff.skippedByManualDeletionCount());
            result.put("comparisonToken", comparisonToken);
            result.put("comparisonRevision", comparisonRevision);
            result.put("comparisonVersionId", target.id());
            result.put("operations", operations);
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("上传 SHP 比对失败: " + error.getMessage(), error);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private Map<String, Object> overview(String areaName, Path root, Path editFile, int lineCount, int stationCount, Map<String, Object> stations) {
        Path lineFolder = root.resolve(BUS_LINE_FOLDER);
        Path stationFolder = root.resolve(BUS_STATION_FOLDER);
        Path adminFolder = root.resolve(ADMIN_AREA_FOLDER);
        String signature = overviewSignature(lineFolder, stationFolder, adminFolder, editFile);
        CachedOverview cached = overviewCache.get(areaName);
        if (cached != null && cached.signature().equals(signature)) {
            return new LinkedHashMap<>(cached.overview());
        }

        double networkScaleKm = readTotalLengthMeters(lineFolder) / 1000.0;
        AdminArea adminArea = readAdminArea(adminFolder);
        CoverageStats coverageStats = coverageStatsFromStationCollection(stations, adminArea);
        Map<String, Object> overview = overviewStats(lineCount, stationCount, networkScaleKm, adminArea.areaKm2(), coverageStats);
        overviewCache.put(areaName, new CachedOverview(signature, new LinkedHashMap<>(overview)));
        return overview;
    }

    private Map<String, Object> overviewStats(int lineCount, int stationCount, double networkScaleKm, double adminAreaKm2, CoverageStats coverageStats) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("lineCount", lineCount);
        overview.put("networkScaleKm", round2(networkScaleKm));
        overview.put("networkDensityKmPerKm2", adminAreaKm2 > 0 ? round4(networkScaleKm / adminAreaKm2) : null);
        overview.put("stationCount", stationCount);
        overview.put("stationCoverage300Rate", coverageRate(coverageStats.coverage300Km2(), adminAreaKm2));
        overview.put("stationCoverage500Rate", coverageRate(coverageStats.coverage500Km2(), adminAreaKm2));
        overview.put("stationCoverage300Km2", round2(coverageStats.coverage300Km2()));
        overview.put("stationCoverage500Km2", round2(coverageStats.coverage500Km2()));
        overview.put("adminAreaKm2", round2(adminAreaKm2));
        return overview;
    }

    private void applyCurrentEdits(List<Map<String, Object>> operations, Map<String, Object> lines, Map<String, Object> routeStops, Map<String, Object> depots) {
        if (operations.isEmpty()) {
            return;
        }
        for (Map<String, Object> operation : operations) {
            String datasetType = normalizeDatasetType(textValue(operation.get("datasetType")));
            switch (datasetType) {
                case "station" -> applyStationEditOperation(routeStops, operation);
                case "line" -> applyEditOperation(lines, operation, "line");
                case "depot" -> applyEditOperation(depots, operation, "depot");
                default -> {
                }
            }
        }
        refreshFeatureCollectionMetadata(lines);
        refreshFeatureCollectionMetadata(routeStops);
        refreshFeatureCollectionMetadata(depots);
    }

    private void applyUploadComparableEdits(List<Map<String, Object>> operations, Map<String, Object> collection, String datasetType) {
        if (operations.isEmpty()) {
            return;
        }
        for (Map<String, Object> operation : operations) {
            if (!datasetType.equals(normalizeDatasetType(textValue(operation.get("datasetType"))))) {
                continue;
            }
            if ("station".equals(datasetType)) {
                applyStationEditOperation(collection, operation);
            } else {
                applyEditOperation(collection, operation, datasetType);
            }
        }
        refreshFeatureCollectionMetadata(collection);
    }

    private void saveUploadedShpFiles(List<MultipartFile> files, Path tempDir) throws IOException {
        for (MultipartFile file : files) {
            String fileName = safeUploadFileName(file.getOriginalFilename());
            if (fileName.isBlank()) {
                continue;
            }
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                unzipUpload(file, tempDir);
                continue;
            }
            Path target = tempDir.resolve(fileName).normalize();
            if (!target.startsWith(tempDir)) {
                throw new BusinessException("上传文件名无效: " + fileName);
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void unzipUpload(MultipartFile file, Path tempDir) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = safeUploadFileName(entry.getName());
                if (entryName.isBlank()) {
                    continue;
                }
                Path target = tempDir.resolve(entryName).normalize();
                if (!target.startsWith(tempDir)) {
                    throw new BusinessException("压缩包内文件路径无效: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zipInputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private String safeUploadFileName(String originalName) {
        String text = safeText(originalName).replace("\\", "/");
        int index = text.lastIndexOf('/');
        return index >= 0 ? text.substring(index + 1) : text;
    }

    private File findFirstShpRecursively(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
                    .filter(path -> !path.getFileName().toString().startsWith("._"))
                    .sorted()
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        }
    }

    private void assertShpCompanionFiles(File uploadedShp) {
        String shpName = uploadedShp.getName();
        String baseName = shpName.substring(0, shpName.length() - ".shp".length());
        Path folder = uploadedShp.toPath().getParent();
        // .dbf 存放属性表（字段），.shx 为索引；二者缺失会导致字段读不到而被误判为“字段不一样”。
        List<String> missing = new ArrayList<>();
        for (String ext : List.of(".dbf", ".shx")) {
            if (!companionExists(folder, baseName, ext)) {
                missing.add(baseName + ext);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("上传的 SHP 配套文件不完整，缺少 " + missing
                    + "。请同时选择同名的 .shp/.shx/.dbf/.prj/.cpg 全部文件，或直接打包为 zip 上传");
        }
    }

    private boolean companionExists(Path folder, String baseName, String extension) {
        if (folder == null || !Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith("._"))
                    .anyMatch(name -> name.equalsIgnoreCase(baseName + extension));
        } catch (IOException error) {
            return false;
        }
    }

    private UploadDiffResult diffUploadedFeatures(
            Map<String, Object> current,
            Map<String, Object> uploaded,
            String datasetType,
            String areaName,
            List<Map<String, Object>> activeOperations
    ) {
        List<DiffFeature> currentFeatures = featuresForDiff(current, datasetType);
        List<DiffFeature> uploadedFeatures = featuresForDiff(uploaded, datasetType);
        Map<String, DiffFeature> currentByMatchKey = new LinkedHashMap<>();
        Map<String, DiffFeature> uploadedByMatchKey = new LinkedHashMap<>();
        currentFeatures.forEach(item -> currentByMatchKey.put(item.matchKey(), item));
        uploadedFeatures.forEach(item -> uploadedByMatchKey.put(item.matchKey(), item));

        List<Map<String, Object>> operations = new ArrayList<>();
        Set<String> matchedCurrentKeys = new LinkedHashSet<>();
        Set<String> matchedUploadedKeys = new LinkedHashSet<>();
        int protectedFeatureCount = 0;
        int protectedFieldCount = 0;
        int skippedByManualDeletionCount = 0;

        for (DiffFeature uploadedFeature : uploadedFeatures) {
            DiffFeature currentFeature = currentByMatchKey.get(uploadedFeature.matchKey());
            if (currentFeature == null) {
                continue;
            }
            MergeFeatureResult merge = mergeUploadedFeature(
                    currentFeature.feature(),
                    uploadedFeature.feature(),
                    datasetType,
                    activeOperations
            );
            matchedCurrentKeys.add(currentFeature.matchKey());
            matchedUploadedKeys.add(uploadedFeature.matchKey());
            if (merge.hasManualProtection()) {
                protectedFeatureCount++;
                protectedFieldCount += merge.protectedFields().size();
            }
            if (!merge.changedFields().isEmpty()) {
                operations.add(uploadOperation(
                        "replace",
                        datasetType,
                        currentFeature.targetId(),
                        merge.feature(),
                        areaName,
                        merge.changedFields(),
                        merge.protectedFields(),
                        false
                ));
            }
        }

        Map<String, DiffFeature> unmatchedCurrentByIdentity = uniqueFeaturesByIdentity(
                currentFeatures.stream().filter(item -> !matchedCurrentKeys.contains(item.matchKey())).toList()
        );
        for (DiffFeature uploadedFeature : uploadedFeatures) {
            if (matchedUploadedKeys.contains(uploadedFeature.matchKey())) {
                continue;
            }
            String identity = featureIdentity(uploadedFeature.feature());
            DiffFeature currentFeature = identity.isBlank() ? null : unmatchedCurrentByIdentity.remove(identity);
            if (currentFeature == null) {
                continue;
            }
            MergeFeatureResult merge = mergeUploadedFeature(
                    currentFeature.feature(),
                    uploadedFeature.feature(),
                    datasetType,
                    activeOperations
            );
            matchedCurrentKeys.add(currentFeature.matchKey());
            matchedUploadedKeys.add(uploadedFeature.matchKey());
            if (merge.hasManualProtection()) {
                protectedFeatureCount++;
                protectedFieldCount += merge.protectedFields().size();
            }
            if (!merge.changedFields().isEmpty()) {
                operations.add(uploadOperation(
                        "replace",
                        datasetType,
                        currentFeature.targetId(),
                        merge.feature(),
                        areaName,
                        merge.changedFields(),
                        merge.protectedFields(),
                        false
                ));
            }
        }

        Map<String, DiffFeature> unmatchedUploadedByAlias = uniqueFeaturesByAlias(
                uploadedFeatures.stream()
                        .filter(item -> !matchedUploadedKeys.contains(item.matchKey()))
                        .toList(),
                datasetType
        );
        for (DiffFeature currentFeature : currentFeatures) {
            if (matchedCurrentKeys.contains(currentFeature.matchKey())) {
                continue;
            }
            DiffFeature uploadedFeature = null;
            for (String originalAlias : manualOriginalAliasesForFeature(
                    datasetType,
                    currentFeature.feature(),
                    activeOperations
            )) {
                uploadedFeature = unmatchedUploadedByAlias.remove(originalAlias);
                if (uploadedFeature != null) {
                    break;
                }
            }
            if (uploadedFeature == null || matchedUploadedKeys.contains(uploadedFeature.matchKey())) {
                continue;
            }
            MergeFeatureResult merge = mergeUploadedFeature(
                    currentFeature.feature(),
                    uploadedFeature.feature(),
                    datasetType,
                    activeOperations
            );
            matchedCurrentKeys.add(currentFeature.matchKey());
            matchedUploadedKeys.add(uploadedFeature.matchKey());
            if (merge.hasManualProtection()) {
                protectedFeatureCount++;
                protectedFieldCount += merge.protectedFields().size();
            }
            if (!merge.changedFields().isEmpty()) {
                operations.add(uploadOperation(
                        "replace",
                        datasetType,
                        currentFeature.targetId(),
                        merge.feature(),
                        areaName,
                        merge.changedFields(),
                        merge.protectedFields(),
                        false
                ));
            }
        }

        for (DiffFeature uploadedFeature : uploadedFeatures) {
            if (matchedUploadedKeys.contains(uploadedFeature.matchKey())) {
                continue;
            }
            ManualProtection protection = manualProtectionForFeature(datasetType, uploadedFeature.feature(), activeOperations);
            if (protection.manuallyDeleted()) {
                skippedByManualDeletionCount++;
                protectedFeatureCount++;
                continue;
            }
            operations.add(uploadOperation(
                    "add",
                    datasetType,
                    uploadedFeature.targetId(),
                    uploadedFeature.feature(),
                    areaName,
                    nonInternalFeatureFields(uploadedFeature.feature(), true),
                    List.of(),
                    false
            ));
        }

        int candidateDeletionCount = 0;
        for (DiffFeature currentFeature : currentFeatures) {
            if (matchedCurrentKeys.contains(currentFeature.matchKey())) {
                continue;
            }
            ManualProtection protection = manualProtectionForFeature(datasetType, currentFeature.feature(), activeOperations);
            List<String> protectedFields = sortedFields(protection.fields());
            if (!protectedFields.isEmpty()) {
                protectedFeatureCount++;
                protectedFieldCount += protectedFields.size();
            }
            if (protection.fields().contains(EXISTENCE_FIELD)) {
                continue;
            }
            operations.add(uploadOperation(
                    "delete",
                    datasetType,
                    currentFeature.targetId(),
                    currentFeature.feature(),
                    areaName,
                    List.of(),
                    protectedFields,
                    true
            ));
            candidateDeletionCount++;
        }
        return new UploadDiffResult(
                operations,
                candidateDeletionCount,
                protectedFeatureCount,
                protectedFieldCount,
                skippedByManualDeletionCount
        );
    }

    @SuppressWarnings("unchecked")
    private List<DiffFeature> featuresForDiff(Map<String, Object> collection, String datasetType) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Object featuresValue = collection.get("features");
        if (!(featuresValue instanceof List<?> features)) {
            return List.of();
        }
        for (Object item : features) {
            if (!(item instanceof Map<?, ?> rawFeature)) {
                continue;
            }
            Map<String, Object> feature = (Map<String, Object>) rawFeature;
            String stableKey = stableDiffFeatureKey(feature, datasetType);
            if (!stableKey.isBlank()) {
                grouped.computeIfAbsent(stableKey, ignored -> new ArrayList<>()).add(feature);
            }
        }
        List<DiffFeature> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            group.sort(Comparator.comparing(feature -> featureSortKey(feature, datasetType)));
            for (int index = 0; index < group.size(); index++) {
                Map<String, Object> feature = group.get(index);
                result.add(new DiffFeature(
                        entry.getKey() + "#" + index,
                        operationTargetId(feature, datasetType),
                        feature
                ));
            }
        }
        return result;
    }

    private String stableDiffFeatureKey(Map<String, Object> feature, String datasetType) {
        Map<String, Object> properties = featureProperties(feature);
        if ("line".equals(datasetType)) {
            return routeFeatureKey(properties);
        }
        if ("depot".equals(datasetType)) {
            return depotFeatureKey(feature);
        }
        return routeStopStableKey(properties);
    }

    private String featureSortKey(Map<String, Object> feature, String datasetType) {
        Map<String, Object> properties = featureProperties(feature);
        if ("station".equals(datasetType)) {
            String sequence = firstText(properties, "seq", "sequence");
            try {
                return String.format(Locale.ROOT, "%020d", Long.parseLong(sequence)) + "|" + featureIdentity(feature);
            } catch (NumberFormatException ignored) {
                return sequence + "|" + featureIdentity(feature);
            }
        }
        return featureIdentity(feature);
    }

    private Map<String, DiffFeature> uniqueFeaturesByIdentity(List<DiffFeature> features) {
        Map<String, DiffFeature> unique = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (DiffFeature feature : features) {
            String identity = featureIdentity(feature.feature());
            if (identity.isBlank()) {
                continue;
            }
            if (unique.putIfAbsent(identity, feature) != null) {
                duplicates.add(identity);
            }
        }
        duplicates.forEach(unique::remove);
        return unique;
    }

    private Map<String, DiffFeature> uniqueFeaturesByAlias(List<DiffFeature> features, String datasetType) {
        Map<String, DiffFeature> unique = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (DiffFeature feature : features) {
            for (String alias : featureAliases(feature.feature(), datasetType)) {
                if (unique.putIfAbsent(alias, feature) != null) {
                    duplicates.add(alias);
                }
            }
        }
        duplicates.forEach(unique::remove);
        return unique;
    }

    private Set<String> manualOriginalAliasesForFeature(
            String datasetType,
            Map<String, Object> feature,
            List<Map<String, Object>> activeOperations
    ) {
        Set<String> currentAliases = featureAliases(feature, datasetType);
        Set<String> originalAliases = new LinkedHashSet<>();
        for (Map<String, Object> operation : activeOperations) {
            if (!datasetType.equals(normalizeDatasetType(textValue(operation.get("datasetType"))))
                    || isShpUploadOperation(operation)) {
                continue;
            }
            Map<String, Object> payloadFeature = payloadFeature(operationPayload(operation));
            if (payloadFeature == null) {
                continue;
            }
            Set<String> payloadAliases = featureAliases(payloadFeature, datasetType);
            if (payloadAliases.stream().noneMatch(currentAliases::contains)) {
                continue;
            }
            addAlias(originalAliases, targetId(operation, operationPayload(operation)));
        }
        return originalAliases;
    }

    private String featureIdentity(Map<String, Object> feature) {
        Map<String, Object> properties = featureProperties(feature);
        return firstText(properties, "_featureId").isBlank()
                ? safeText(feature.get("id"))
                : firstText(properties, "_featureId");
    }

    private String operationTargetId(Map<String, Object> feature, String datasetType) {
        String identity = safeText(feature.get("id"));
        if (!identity.isBlank()) {
            return identity;
        }
        Map<String, Object> properties = featureProperties(feature);
        String internalIdentity = firstText(properties, "_featureId");
        if (!internalIdentity.isBlank()) {
            return internalIdentity;
        }
        return switch (datasetType) {
            case "line" -> routeFeatureKey(properties);
            case "station" -> routeStopFeatureKey(properties);
            case "depot" -> depotFeatureKey(feature);
            default -> "";
        };
    }

    private String depotFeatureKey(Map<String, Object> feature) {
        Map<String, Object> properties = featureProperties(feature);
        String id = firstText(properties, "depot_id", "station_id", "id", "code", "F001");
        if (!id.isBlank()) {
            return id;
        }
        String name = firstText(properties, "depot_name", "name", "场站名称", "station_name");
        if (!name.isBlank()) {
            return name;
        }
        String coords = depotCoordinateKey(feature.get("geometry"));
        if (!coords.isBlank()) {
            return coords;
        }
        return firstText(properties, "_featureId");
    }

    private String depotCoordinateKey(Object geometryValue) {
        if (!(geometryValue instanceof Map<?, ?> geometry)) {
            return "";
        }
        Object coordinates = geometry.get("coordinates");
        if (coordinates instanceof List<?> list && list.size() >= 2
                && list.get(0) instanceof Number lng && list.get(1) instanceof Number lat) {
            return round6(lng.doubleValue()) + "," + round6(lat.doubleValue());
        }
        return "";
    }

    private String routeFeatureKey(Map<String, Object> properties) {
        return List.of(
                        firstText(properties, "line_id"),
                        firstText(properties, "dir"),
                        firstText(properties, "route_id")
                ).stream()
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "|" + right)
                .orElse(firstText(properties, "_featureId", "name"));
    }

    private String routeStopFeatureKey(Map<String, Object> properties) {
        return List.of(
                        firstText(properties, "line_id"),
                        firstText(properties, "dir"),
                        firstText(properties, "stop_id"),
                        firstText(properties, "seq")
                ).stream()
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private String routeStopStableKey(Map<String, Object> properties) {
        return List.of(
                        firstText(properties, "line_id"),
                        firstText(properties, "dir"),
                        firstText(properties, "stop_id")
                ).stream()
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "|" + right)
                .orElse(firstText(properties, "stop_id", "_featureId"));
    }

    private Map<String, Object> uploadOperation(
            String action,
            String datasetType,
            String targetId,
            Map<String, Object> feature,
            String areaName,
            List<String> changedFields,
            List<String> protectedFields,
            boolean candidateDeletion
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetId", targetId);
        if (!"delete".equals(action)) {
            payload.put("feature", feature);
        }
        payload.put("changedFields", changedFields);
        payload.put("protectedFields", protectedFields);
        if ("station".equals(datasetType)) {
            payload.put("stationScope", "route");
        }

        String name = editTargetNameForFeature(datasetType, feature);
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "upload_" + datasetType + "_" + action + "_"
                + Integer.toUnsignedString(targetId.hashCode()) + "_"
                + UUID.randomUUID().toString().substring(0, 8));
        operation.put("areaName", areaName);
        operation.put("datasetType", datasetType);
        operation.put("type", action + "_" + datasetType + "_from_shp");
        operation.put("source", "shp");
        operation.put("targetId", targetId);
        operation.put("title", name);
        operation.put("detail", uploadOperationDetail(action, datasetType, changedFields, protectedFields));
        operation.put("changedFields", changedFields);
        operation.put("protectedFields", protectedFields);
        operation.put("manualProtected", !protectedFields.isEmpty());
        operation.put("candidateDeletion", candidateDeletion);
        operation.put("payload", payload);
        return operation;
    }

    private String registerShpComparison(
            String username,
            String areaName,
            long revision,
            String versionId,
            List<Map<String, Object>> operations
    ) {
        if (operations.isEmpty()) {
            return "";
        }
        String token = UUID.randomUUID().toString();
        Map<String, String> canonicalOperations = new LinkedHashMap<>();
        for (Map<String, Object> operation : operations) {
            operation.put("comparisonToken", token);
            String operationId = safeText(operation.get("operationId"));
            canonicalOperations.put(operationId, canonicalOperationJson(operation));
        }
        pendingShpComparisons.put(token, new PendingShpComparison(
                safeText(username),
                areaName,
                revision,
                versionId,
                canonicalOperations
        ));
        return token;
    }

    private void validatePendingShpOperations(
            String username,
            String areaName,
            List<Map<String, Object>> operations,
            Path root
    ) {
        Map<String, Object> state = readEditState(root);
        long currentRevision = stateRevision(state);
        String currentVersionId = activeTargetVersion(state).id();
        for (Map<String, Object> operation : operations) {
            if (!isShpUploadOperation(operation) && safeText(operation.get("comparisonToken")).isBlank()) {
                continue;
            }
            String token = safeText(operation.get("comparisonToken"));
            PendingShpComparison comparison = pendingShpComparisons.get(token);
            if (comparison == null
                    || !comparison.username().equals(safeText(username))
                    || !comparison.areaName().equals(areaName)
                    || comparison.revision() != currentRevision
                    || !comparison.versionId().equals(currentVersionId)) {
                throw new BusinessException("SHP 比对结果已失效，请重新上传并比对");
            }
            String operationId = safeText(operation.get("operationId"));
            String expected = comparison.canonicalOperations().get(operationId);
            if (expected == null || !expected.equals(canonicalOperationJson(operation))) {
                throw new BusinessException("SHP 比对结果已被修改，请重新上传并比对");
            }
        }
    }

    private void removeCommittedShpComparisons(List<Map<String, Object>> operations) {
        operations.stream()
                .map(operation -> safeText(operation.get("comparisonToken")))
                .filter(token -> !token.isBlank())
                .distinct()
                .forEach(pendingShpComparisons::remove);
    }

    private String canonicalOperationJson(Map<String, Object> operation) {
        Map<String, Object> copy = new LinkedHashMap<>(operation);
        copy.remove("deletionConfirmed");
        return JSON.toJSONString(canonicalJsonValue(copy));
    }

    private Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalJsonValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalJsonValue).toList();
        }
        return value;
    }

    private String uploadOperationDetail(String action, String datasetType, List<String> changedFields, List<String> protectedFields) {
        String label = datasetTypeLabel(datasetType);
        String detail = switch (action) {
            case "add" -> "上传 SHP 新增" + label;
            case "delete" -> "上传 SHP 中缺少该" + label + "，待确认是否删除";
            default -> "上传 SHP 更新" + label + "：" + changedFields.stream()
                    .map(this::fieldDisplayName)
                    .collect(java.util.stream.Collectors.joining("、"));
        };
        if (!protectedFields.isEmpty()) {
            detail += "；保留人工修改：" + protectedFields.stream()
                    .map(this::fieldDisplayName)
                    .collect(java.util.stream.Collectors.joining("、"));
        }
        return detail;
    }

    private boolean isUnconfirmedShpDeletion(Map<String, Object> operation) {
        String type = safeText(operation.get("type"));
        return type.startsWith("delete_")
                && type.endsWith("_from_shp")
                && !booleanValue(operation.get("deletionConfirmed"));
    }

    private String fieldDisplayName(String field) {
        return switch (safeText(field)) {
            case GEOMETRY_FIELD -> "线路走向/位置";
            case "line_id" -> "线路ID";
            case "dir" -> "方向";
            case "route_id" -> "线路编号";
            case "first" -> "首班时间";
            case "last" -> "末班时间";
            case "interval" -> "发车间隔";
            case "mode" -> "交通方式";
            case "name" -> "名称";
            case "price" -> "票价";
            case "company" -> "所属公司";
            case "stop_id" -> "站点ID";
            case "stop_name" -> "站点名称";
            case "seq" -> "站序";
            case "lon" -> "经度";
            case "lat" -> "纬度";
            default -> safeText(field);
        };
    }

    private String editTargetNameForFeature(String datasetType, Map<String, Object> feature) {
        Map<String, Object> properties = featureProperties(feature);
        if ("line".equals(datasetType)) {
            return firstText(properties, "name", "line_id", "route_id", "_featureId");
        }
        if ("depot".equals(datasetType)) {
            return firstText(properties, "depot_name", "name", "场站名称", "station_name", "_featureId");
        }
        return firstText(properties, "stop_name", "stop_id", "_featureId");
    }

    private String datasetTypeLabel(String datasetType) {
        return switch (datasetType) {
            case "line" -> "线路";
            case "station" -> "站点";
            case "depot" -> "场站";
            case "all" -> "综合";
            default -> "数据";
        };
    }

    private boolean sameFeatureContent(Map<String, Object> left, Map<String, Object> right) {
        return JSON.toJSONString(normalizedFeatureForDiff(left)).equals(JSON.toJSONString(normalizedFeatureForDiff(right)));
    }

    private Map<String, Object> normalizedFeatureForDiff(Map<String, Object> feature) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("geometry", normalizedValueForDiff(feature.get("geometry")));
        Map<String, Object> properties = new TreeMap<>();
        featureProperties(feature).forEach((key, value) -> {
            if (!key.startsWith("_") && !DERIVED_FIELDS.contains(key)) {
                properties.put(key, normalizedValueForDiff(value));
            }
        });
        normalized.put("properties", properties);
        return normalized;
    }

    private Object normalizedValueForDiff(Object value) {
        if (value instanceof Number number) {
            return round6(number.doubleValue());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), normalizedValueForDiff(item)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalizedValueForDiff).toList();
        }
        return value == null ? null : String.valueOf(value).trim();
    }

    private MergeFeatureResult mergeUploadedFeature(
            Map<String, Object> currentFeature,
            Map<String, Object> uploadedFeature,
            String datasetType,
            List<Map<String, Object>> activeOperations
    ) {
        ManualProtection protection = manualProtectionForFeature(datasetType, currentFeature, activeOperations);
        Map<String, Object> merged = new LinkedHashMap<>(uploadedFeature);
        Map<String, Object> mergedProperties = new LinkedHashMap<>(featureProperties(uploadedFeature));
        Map<String, Object> currentProperties = featureProperties(currentFeature);
        for (String field : protection.fields()) {
            if (GEOMETRY_FIELD.equals(field) || EXISTENCE_FIELD.equals(field) || DELETION_FIELD.equals(field)) {
                continue;
            }
            if (currentProperties.containsKey(field)) {
                mergedProperties.put(field, currentProperties.get(field));
            } else {
                mergedProperties.remove(field);
            }
        }
        if (protection.fields().contains(GEOMETRY_FIELD)) {
            merged.put("geometry", currentFeature.get("geometry"));
        }
        merged.put("properties", mergedProperties);
        List<String> changedFields = changedFeatureFields(currentFeature, merged);
        return new MergeFeatureResult(
                merged,
                changedFields,
                sortedFields(protection.fields().stream()
                        .filter(field -> !EXISTENCE_FIELD.equals(field) && !DELETION_FIELD.equals(field))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))),
                !protection.fields().isEmpty()
        );
    }

    private List<String> changedFeatureFields(Map<String, Object> currentFeature, Map<String, Object> mergedFeature) {
        List<String> changed = new ArrayList<>();
        if (!JSON.toJSONString(normalizedValueForDiff(currentFeature.get("geometry")))
                .equals(JSON.toJSONString(normalizedValueForDiff(mergedFeature.get("geometry"))))) {
            changed.add(GEOMETRY_FIELD);
        }
        Map<String, Object> currentProperties = featureProperties(currentFeature);
        Map<String, Object> mergedProperties = featureProperties(mergedFeature);
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(currentProperties.keySet());
        keys.addAll(mergedProperties.keySet());
        keys.stream()
                .filter(key -> !key.startsWith("_") && !DERIVED_FIELDS.contains(key))
                .sorted()
                .filter(key -> !JSON.toJSONString(normalizedValueForDiff(currentProperties.get(key)))
                        .equals(JSON.toJSONString(normalizedValueForDiff(mergedProperties.get(key)))))
                .forEach(changed::add);
        return changed;
    }

    private List<String> nonInternalFeatureFields(Map<String, Object> feature, boolean includeGeometry) {
        List<String> fields = featureProperties(feature).keySet().stream()
                .filter(key -> !key.startsWith("_") && !DERIVED_FIELDS.contains(key))
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (includeGeometry && feature.get("geometry") != null) {
            fields.add(0, GEOMETRY_FIELD);
        }
        return fields;
    }

    private ManualProtection manualProtectionForFeature(
            String datasetType,
            Map<String, Object> feature,
            List<Map<String, Object>> activeOperations
    ) {
        Set<String> fields = new LinkedHashSet<>();
        boolean manuallyDeleted = false;
        for (Map<String, Object> operation : activeOperations) {
            if (!datasetType.equals(normalizeDatasetType(textValue(operation.get("datasetType"))))
                    || isShpUploadOperation(operation)) {
                continue;
            }
            String type = safeText(operation.get("type"));
            if ("station".equals(datasetType) && "reorder_line_stations".equals(type)) {
                if (reorderOperationMatchesFeature(operation, feature)) {
                    fields.add("seq");
                }
                continue;
            }
            if (!operationMatchesFeature(operation, feature, datasetType)) {
                continue;
            }
            if (type.startsWith("delete_")) {
                fields.add(DELETION_FIELD);
                manuallyDeleted = true;
                continue;
            }
            fields.addAll(manualChangedFields(operation, feature, datasetType));
        }
        return new ManualProtection(fields, manuallyDeleted);
    }

    private boolean isShpUploadOperation(Map<String, Object> operation) {
        return "shp".equalsIgnoreCase(safeText(operation.get("source")))
                || safeText(operation.get("type")).endsWith("_from_shp");
    }

    private boolean operationMatchesFeature(Map<String, Object> operation, Map<String, Object> feature, String datasetType) {
        if ("station".equals(datasetType) && "route".equals(stationOperationScope(operation))) {
            return routeStationOperationMatchesFeature(operation, feature);
        }
        Set<String> aliases = featureAliases(feature, datasetType);
        String targetId = targetId(operation, operationPayload(operation));
        if (!targetId.isBlank() && aliases.contains(targetId)) {
            return true;
        }
        Map<String, Object> payloadFeature = payloadFeature(operationPayload(operation));
        if (payloadFeature == null) {
            return false;
        }
        Set<String> payloadAliases = featureAliases(payloadFeature, datasetType);
        return payloadAliases.stream().anyMatch(aliases::contains);
    }

    private String stationOperationScope(Map<String, Object> operation) {
        Map<String, Object> payload = operationPayload(operation);
        String scope = firstText(operation, "stationScope", "scope");
        return scope.isBlank() ? firstText(payload, "stationScope", "scope") : scope;
    }

    private boolean routeStationOperationMatchesFeature(Map<String, Object> operation, Map<String, Object> feature) {
        String targetId = targetId(operation, operationPayload(operation));
        if (!targetId.isBlank() && isExactStationTarget(feature, targetId)) {
            return true;
        }
        Map<String, Object> payloadFeature = payloadFeature(operationPayload(operation));
        if (payloadFeature == null) {
            return false;
        }
        return routeStopStableKey(featureProperties(feature))
                .equals(routeStopStableKey(featureProperties(payloadFeature)));
    }

    private Set<String> featureAliases(Map<String, Object> feature, String datasetType) {
        Set<String> aliases = new LinkedHashSet<>();
        Map<String, Object> properties = featureProperties(feature);
        addAlias(aliases, safeText(feature.get("id")));
        for (String key : List.of("_featureId", "_stationKey", "_lineKey", "_depotKey")) {
            addAlias(aliases, firstText(properties, key));
        }
        if ("line".equals(datasetType)) {
            addAlias(aliases, routeFeatureKey(properties));
            addAlias(aliases, firstText(properties, "line_id"));
            addAlias(aliases, firstText(properties, "route_id"));
        } else if ("station".equals(datasetType)) {
            addAlias(aliases, routeStopFeatureKey(properties));
            addAlias(aliases, routeStopStableKey(properties));
            addAlias(aliases, firstText(properties, "stop_id"));
        } else if ("depot".equals(datasetType)) {
            addAlias(aliases, depotFeatureKey(feature));
            addAlias(aliases, firstText(properties, "depot_id", "station_id", "id", "code", "F001"));
            addAlias(aliases, firstText(properties, "depot_name", "name", "场站名称", "station_name"));
        }
        return aliases;
    }

    private void addAlias(Set<String> aliases, String value) {
        if (!safeText(value).isBlank()) {
            aliases.add(safeText(value));
        }
    }

    private boolean reorderOperationMatchesFeature(Map<String, Object> operation, Map<String, Object> feature) {
        Map<String, Object> payload = operationPayload(operation);
        Map<String, Object> properties = featureProperties(feature);
        String lineId = firstText(payload, "lineId", "line_id");
        if (!lineId.isBlank() && !lineId.equals(firstText(properties, "line_id", "route_id"))) {
            return false;
        }
        String direction = firstText(payload, "dir", "direction");
        if (!direction.isBlank() && !direction.equals(firstText(properties, "dir", "direction"))) {
            return false;
        }
        String featureStopId = firstText(properties, "stop_id");
        String featureSequence = firstText(properties, "seq", "sequence");
        for (Map<String, Object> change : mutableMapList(payload.get("changes"))) {
            String target = firstText(change, "targetId", "featureId");
            String changeDirection = firstText(change, "dir", "direction");
            if (!changeDirection.isBlank() && !changeDirection.equals(firstText(properties, "dir", "direction"))) {
                continue;
            }
            if (!target.isBlank() && isExactStationTarget(feature, target)) {
                return true;
            }
            String stopId = firstText(change, "stopId", "stop_id");
            String fromSequence = firstText(change, "fromSeq", "from_sequence");
            String toSequence = firstText(change, "toSeq", "seq");
            if (!stopId.isBlank() && stopId.equals(featureStopId)
                    && (featureSequence.equals(fromSequence) || featureSequence.equals(toSequence))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> manualChangedFields(Map<String, Object> operation, Map<String, Object> feature, String datasetType) {
        Map<String, Object> payload = operationPayload(operation);
        Set<String> fields = new LinkedHashSet<>();
        stringList(operation.get("changedFields")).forEach(field -> fields.add(normalizeProtectedField(field)));
        stringList(payload.get("changedFields")).forEach(field -> fields.add(normalizeProtectedField(field)));
        String type = safeText(operation.get("type"));
        if (type.startsWith("add_")) {
            if (fields.isEmpty()) {
                fields.addAll(nonInternalFeatureFields(feature, true));
            }
            fields.add(EXISTENCE_FIELD);
        } else if (fields.isEmpty()) {
            if (type.startsWith("rename_")) {
                fields.add(switch (datasetType) {
                    case "line" -> "name";
                    case "station" -> "stop_name";
                    case "depot" -> nameField(featureProperties(feature), "depot");
                    default -> "name";
                });
            } else if (type.startsWith("move_")) {
                fields.add(GEOMETRY_FIELD);
                if ("station".equals(datasetType)) {
                    fields.add("lon");
                    fields.add("lat");
                }
            } else if ("update_line_headway".equals(type)) {
                fields.add("interval");
            } else if ("update_line_stations".equals(type)) {
                fields.add("station_list_edit");
            } else if (type.startsWith("replace_")) {
                fields.addAll(legacyChangedFields(operation, feature));
                if (fields.isEmpty()) {
                    Map<String, Object> payloadFeature = payloadFeature(payload);
                    fields.addAll(nonInternalFeatureFields(payloadFeature == null ? feature : payloadFeature, true));
                }
            }
        }
        if ("station".equals(datasetType) && (fields.contains("lon") || fields.contains("lat"))) {
            fields.add(GEOMETRY_FIELD);
        }
        fields.removeIf(String::isBlank);
        return fields;
    }

    private Set<String> legacyChangedFields(Map<String, Object> operation, Map<String, Object> feature) {
        String detail = safeText(operation.get("detail"));
        int separator = Math.max(detail.lastIndexOf('：'), detail.lastIndexOf(':'));
        if (separator < 0 || separator >= detail.length() - 1) {
            return Set.of();
        }
        Map<String, String> labels = Map.ofEntries(
                Map.entry("线路ID", "line_id"),
                Map.entry("方向", "dir"),
                Map.entry("线路编号", "route_id"),
                Map.entry("首班时间", "first"),
                Map.entry("末班时间", "last"),
                Map.entry("发车间隔", "interval"),
                Map.entry("交通方式", "mode"),
                Map.entry("名称", nameField(featureProperties(feature), "depot")),
                Map.entry("票价", "price"),
                Map.entry("所属公司", "company"),
                Map.entry("站点ID", "stop_id"),
                Map.entry("站点名称", "stop_name"),
                Map.entry("站序", "seq"),
                Map.entry("经度", "lon"),
                Map.entry("纬度", "lat"),
                Map.entry("线路走向", GEOMETRY_FIELD),
                Map.entry("几何", GEOMETRY_FIELD)
        );
        Set<String> fields = new LinkedHashSet<>();
        for (String label : detail.substring(separator + 1).split("[、,，;；]")) {
            String text = safeText(label);
            if (text.isBlank() || "属性".equals(text)) {
                continue;
            }
            String field = labels.get(text);
            if (field == null && featureProperties(feature).containsKey(text)) {
                field = text;
            }
            if (field != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    private String normalizeProtectedField(String field) {
        String normalized = safeText(field);
        if ("the_geom".equalsIgnoreCase(normalized)
                || "$geometry".equalsIgnoreCase(normalized)
                || "线路走向".equals(normalized)
                || "位置".equals(normalized)) {
            return GEOMETRY_FIELD;
        }
        return normalized;
    }

    private List<String> sortedFields(Set<String> fields) {
        return fields.stream().filter(field -> !field.isBlank()).sorted().toList();
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        } catch (Exception ignored) {
            // Best effort cleanup for temporary upload files.
        }
    }

    private List<Map<String, Object>> currentOperations(Map<String, Object> state) {
        return mutableMapList(state.get("operations"));
    }

    private Map<String, Object> appendCurrentEdits(Path root, Map<String, Object> manifest) throws IOException {
        Map<String, Object> state = readEditState(root);
        List<Map<String, Object>> operations = new ArrayList<>(activeTargetVersion(state).operations());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> committedOperations = (List<Map<String, Object>>) manifest.get("operations");
        operations.addAll(committedOperations);
        return writeEditState(root, state, manifest, operations);
    }

    private Path stateFile(Path root) {
        return root.resolve(EDIT_STATE_FOLDER).resolve("current-edits.json");
    }

    private Map<String, Object> readEditState(Path root) {
        Path file = stateFile(root);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(Files.readString(file), new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception error) {
            throw new BusinessException("读取真实数据编辑库失败: " + file, error);
        }
    }

    private Map<String, Object> writeEditState(Path root, Map<String, Object> state, Map<String, Object> manifest, List<Map<String, Object>> activeOperations) throws IOException {
        Path file = stateFile(root);
        Files.createDirectories(file.getParent());
        long nextRevision = stateRevision(state) + 1;
        String versionId = safeText(manifest.get("versionId"));
        String changeType = safeText(manifest.get("changeType"));
        if (changeType.isBlank()) {
            changeType = "commit";
            manifest.put("changeType", changeType);
        }
        if (safeText(manifest.get("activeDataVersionId")).isBlank()) {
            manifest.put("activeDataVersionId", versionId);
        }
        manifest.put("revision", nextRevision);
        manifest.put("operationCount", currentOperations(manifest).size());
        manifest.put("stateOperationIds", operationIds(activeOperations));

        List<Map<String, Object>> versions = mutableMapList(state.get("versions"));
        versions.add(new LinkedHashMap<>(manifest));
        state.put("revision", nextRevision);
        state.put("currentVersionId", versionId);
        state.put("activeVersionId", versionId);
        state.put("activeDataVersionId", safeText(manifest.get("activeDataVersionId")));
        state.put("updatedAt", System.currentTimeMillis());
        state.put("versions", versions);
        state.put("operations", activeOperations);
        writeJsonAtomically(file, state);
        return manifest;
    }

    private Map<String, Object> switchActiveVersion(Path root, Map<String, Object> state, TargetVersion target, List<Map<String, Object>> activeOperations, String username) throws IOException {
        Path file = stateFile(root);
        Files.createDirectories(file.getParent());
        long nextRevision = stateRevision(state) + 1;
        state.put("revision", nextRevision);
        state.put("currentVersionId", target.id());
        state.put("activeVersionId", target.id());
        state.put("activeDataVersionId", target.dataVersionId());
        state.put("operations", activeOperations);
        state.put("lastSwitchBy", safeText(username).isBlank() ? "未知用户" : safeText(username));
        state.put("lastSwitchAt", System.currentTimeMillis());
        state.put("updatedAt", System.currentTimeMillis());
        writeJsonAtomically(file, state);
        return state;
    }

    private void writeJsonAtomically(Path file, Map<String, Object> state) throws IOException {
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(tempFile, JSON.toJSONString(state), StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void assertFreshRevision(Path root, Long baseRevision) {
        if (baseRevision == null) {
            return;
        }
        long currentRevision = stateRevision(readEditState(root));
        if (baseRevision.longValue() != currentRevision) {
            throw new BusinessException("真实数据已被其他用户更新，请刷新当前区域后再提交");
        }
    }

    private void assertFreshBaseVersion(Map<String, Object> state, String baseVersionId) {
        String expected = safeText(baseVersionId);
        if (expected.isBlank()) {
            return;
        }
        String current = activeTargetVersion(state).id();
        if (!expected.equals(current)) {
            throw new BusinessException("真实数据版本已切换，请刷新当前区域后再提交");
        }
    }

    private Path activeDataRoot(Path root, Map<String, Object> state) {
        String activeDataVersionId = activeDataVersionId(state);
        if (activeDataVersionId.isBlank() || "__base__".equals(activeDataVersionId)) {
            return root;
        }
        Path versionRoot = root.resolve(VERSION_FOLDER).resolve(activeDataVersionId);
        return Files.isDirectory(versionRoot) ? versionRoot : root;
    }

    private Path dataRootForVersion(Path root, TargetVersion target) {
        if (target == null || "__base__".equals(target.dataVersionId())) {
            return root;
        }
        Path versionRoot = root.resolve(VERSION_FOLDER).resolve(target.dataVersionId());
        return Files.isDirectory(versionRoot) ? versionRoot : root;
    }

    private TargetVersion latestTargetVersion(Map<String, Object> state) {
        List<Map<String, Object>> versions = visibleHistoryVersions(state);
        if (versions.isEmpty()) {
            return new TargetVersion("__base__", "__base__", "原始 shp 数据", List.of(), List.of(), true);
        }
        String versionId = safeText(versions.get(versions.size() - 1).get("versionId"));
        return targetVersion(versionId, mutableMapList(state.get("versions")));
    }

    private TargetVersion activeTargetVersion(Map<String, Object> state) {
        String versionId = activeVersionId(state);
        if (versionId.isBlank()) {
            return latestTargetVersion(state);
        }
        return targetVersion(versionId, mutableMapList(state.get("versions")));
    }

    private Map<String, Object> historySummary(Path root) {
        Map<String, Object> state = readEditState(root);
        List<Map<String, Object>> versions = visibleHistoryVersions(state);
        TargetVersion latest = latestTargetVersion(state);
        TargetVersion active = activeTargetVersion(state);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revision", stateRevision(state));
        summary.put("currentVersionId", active.id());
        summary.put("activeVersionId", active.id());
        summary.put("activeDataVersionId", active.dataVersionId());
        summary.put("latestVersionId", latest.id());
        summary.put("updatedAt", longValue(state.get("updatedAt"), 0));
        summary.put("lastSwitchBy", safeText(state.get("lastSwitchBy")));
        summary.put("lastSwitchAt", longValue(state.get("lastSwitchAt"), 0));
        summary.put("versionCount", versions.size() + 1);
        summary.put("operationCount", active.operations().size());
        return summary;
    }

    private List<Map<String, Object>> historyVersions(Map<String, Object> state) {
        List<Map<String, Object>> versions = visibleHistoryVersions(state);
        TargetVersion active = activeTargetVersion(state);
        String activeVersionId = active.id();
        String activeDataVersionId = active.dataVersionId();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("versionId", "__base__");
        base.put("areaName", state.get("areaName"));
        base.put("datasetType", "all");
        base.put("username", "系统");
        base.put("message", "原始 shp 数据");
        base.put("committedAt", 0);
        base.put("revision", 0);
        base.put("changeType", "base");
        base.put("operationCount", 0);
        base.put("baseVersionId", "");
        base.put("baseVersionLabel", "");
        base.put("isCurrentLogVersion", "__base__".equals(activeVersionId));
        base.put("isActiveDataVersion", "__base__".equals(activeDataVersionId));
        base.put("operations", List.of());
        base.put("evidenceImages", List.of());
        result.add(base);
        for (Map<String, Object> version : versions) {
            Map<String, Object> item = new LinkedHashMap<>();
            String versionId = safeText(version.get("versionId"));
            item.put("versionId", versionId);
            item.put("areaName", version.get("areaName"));
            item.put("datasetType", version.get("datasetType"));
            item.put("username", version.get("username"));
            item.put("message", version.get("message"));
            item.put("committedAt", longValue(version.get("committedAt"), 0));
            item.put("revision", longValue(version.get("revision"), 0));
            item.put("changeType", safeText(version.get("changeType")).isBlank() ? "commit" : safeText(version.get("changeType")));
            item.put("operationCount", numberValue(version.get("operationCount")).intValue());
            item.put("baseVersionId", safeText(version.get("baseVersionId")));
            item.put("baseVersionLabel", versionLabelById(versions, safeText(version.get("baseVersionId"))));
            item.put("isCurrentLogVersion", versionId.equals(activeVersionId));
            item.put("isActiveDataVersion", versionId.equals(activeDataVersionId));
            item.put("operations", currentOperations(version));
            item.put("evidenceImages", evidenceImages(version));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> visibleHistoryVersions(Map<String, Object> state) {
        List<Map<String, Object>> versions = mutableMapList(state.get("versions"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> version : versions) {
            String changeType = safeText(version.get("changeType"));
            if ("rollback".equals(changeType) || "switch".equals(changeType)) {
                continue;
            }
            result.add(version);
        }
        return result;
    }

    private TargetVersion targetVersion(String targetVersionId, List<Map<String, Object>> versions) {
        if ("__base__".equals(targetVersionId)) {
            return new TargetVersion("__base__", "__base__", "原始 shp 数据", List.of(), List.of(), true);
        }
        List<String> fallbackOperationIds = new ArrayList<>();
        for (Map<String, Object> version : versions) {
            String versionId = safeText(version.get("versionId"));
            String changeType = safeText(version.get("changeType"));
            if (!"rollback".equals(changeType)) {
                fallbackOperationIds.addAll(operationIdsOfVersion(version));
            }
            if (versionId.equals(targetVersionId)) {
                List<String> stateOperationIds = stringList(version.get("stateOperationIds"));
                List<String> operationIds = stateOperationIds.isEmpty() ? fallbackOperationIds : stateOperationIds;
                String label = safeText(version.get("message"));
                String dataVersionId = safeText(version.get("activeDataVersionId"));
                boolean materializedData = booleanValue(version.get("materializedData"));
                List<Map<String, Object>> operations = operationsByIds(operationIds, operationCatalog(versions));
                return new TargetVersion(versionId, dataVersionId.isBlank() ? versionId : dataVersionId, label.isBlank() ? versionId : label, operationIds, operations, materializedData);
            }
        }
        throw new BusinessException("历史版本不存在: " + targetVersionId);
    }

    private String versionLabelById(List<Map<String, Object>> versions, String versionId) {
        if (safeText(versionId).isBlank()) {
            return "";
        }
        if ("__base__".equals(versionId)) {
            return "原始 shp 数据";
        }
        for (Map<String, Object> version : versions) {
            if (versionId.equals(safeText(version.get("versionId")))) {
                String message = safeText(version.get("message"));
                return message.isBlank() ? versionId : message;
            }
        }
        return versionId;
    }

    private Map<String, Map<String, Object>> operationCatalog(List<Map<String, Object>> versions) {
        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        for (Map<String, Object> version : versions) {
            List<Map<String, Object>> operations = currentOperations(version);
            for (int index = 0; index < operations.size(); index++) {
                Map<String, Object> operation = new LinkedHashMap<>(operations.get(index));
                String operationId = operationIdOf(operation, index);
                if (!operationId.isBlank()) {
                    operation.put("operationId", operationId);
                    catalog.put(operationId, operation);
                }
            }
        }
        return catalog;
    }

    private List<Map<String, Object>> operationsByIds(List<String> operationIds, Map<String, Map<String, Object>> operationCatalog) {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (String operationId : operationIds) {
            Map<String, Object> operation = operationCatalog.get(operationId);
            if (operation != null) {
                operations.add(new LinkedHashMap<>(operation));
            }
        }
        return operations;
    }

    private List<String> operationIds(List<Map<String, Object>> operations) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            String operationId = operationIdOf(operations.get(index), index);
            if (!operationId.isBlank()) {
                ids.add(operationId);
            }
        }
        return ids;
    }

    private List<String> operationIdsOfVersion(Map<String, Object> version) {
        List<Map<String, Object>> operations = currentOperations(version);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            String operationId = operationIdOf(operations.get(index), index);
            if (!operationId.isBlank()) {
                ids.add(operationId);
            }
        }
        return ids;
    }

    private String operationIdOf(Map<String, Object> operation, int fallbackIndex) {
        String operationId = firstText(operation, "operationId", "id");
        if (!operationId.isBlank()) {
            return operationId;
        }
        String versionId = safeText(operation.get("versionId"));
        return versionId.isBlank() ? "" : versionId + "_" + fallbackIndex;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = safeText(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private String activeDataVersionId(Map<String, Object> state) {
        String activeDataVersionId = safeText(state.get("activeDataVersionId"));
        return activeDataVersionId.isBlank() ? "__base__" : activeDataVersionId;
    }

    private String activeVersionId(Map<String, Object> state) {
        String activeVersionId = safeText(state.get("activeVersionId"));
        if (activeVersionId.isBlank()) {
            activeVersionId = safeText(state.get("currentVersionId"));
        }
        if ("__base__".equals(activeVersionId) || visibleVersionExists(state, activeVersionId)) {
            return activeVersionId;
        }
        return activeDataVersionId(state);
    }

    private boolean visibleVersionExists(Map<String, Object> state, String versionId) {
        if (safeText(versionId).isBlank()) {
            return false;
        }
        for (Map<String, Object> version : visibleHistoryVersions(state)) {
            if (versionId.equals(safeText(version.get("versionId")))) {
                return true;
            }
        }
        return false;
    }

    private long stateRevision(Map<String, Object> state) {
        return longValue(state.get("revision"), 0);
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            String text = safeText(value);
            return text.isBlank() ? fallback : Long.parseLong(text);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private Map<String, Object> buildManifest(String versionId, String areaName, String datasetType, String username, String message, List<Map<String, Object>> operations, String baseVersionId, List<Map<String, Object>> evidenceImages) {
        long committedAt = System.currentTimeMillis();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("versionId", versionId);
        manifest.put("areaName", areaName);
        manifest.put("datasetType", datasetType);
        manifest.put("username", safeText(username).isBlank() ? "未知用户" : safeText(username));
        manifest.put("message", safeText(message));
        manifest.put("baseVersionId", safeText(baseVersionId).isBlank() ? "__base__" : safeText(baseVersionId));
        manifest.put("committedAt", committedAt);
        manifest.put("changeType", "commit");
        manifest.put("evidenceImages", evidenceImages == null ? List.of() : evidenceImages);
        List<Map<String, Object>> enrichedOperations = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            Map<String, Object> operation = operations.get(index);
            Map<String, Object> enriched = new LinkedHashMap<>(operation);
            if (firstText(enriched, "operationId", "id").isBlank()) {
                enriched.put("operationId", versionId + "_" + index);
            }
            enriched.put("versionId", versionId);
            enriched.put("areaName", areaName);
            String operationDatasetType = normalizeDatasetType(textValue(operation.get("datasetType")));
            enriched.put("datasetType", operationDatasetType.isBlank() ? datasetType : operationDatasetType);
            enriched.put("username", manifest.get("username"));
            enriched.put("committedAt", committedAt);
            if (evidenceImages != null && !evidenceImages.isEmpty()) {
                enriched.put("evidenceImages", evidenceImages);
            }
            enrichedOperations.add(enriched);
        }
        manifest.put("operationCount", enrichedOperations.size());
        manifest.put("operations", enrichedOperations);
        return manifest;
    }

    private List<Map<String, Object>> evidenceImages(Map<String, Object> source) {
        return normalizeEvidenceImages(source.get("evidenceImages"));
    }

    private List<Map<String, Object>> normalizeEvidenceImages(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawImage)) {
                continue;
            }
            Map<String, Object> image = stringObjectMap(rawImage);
            String dataUrl = safeText(image.get("dataUrl"));
            if (!dataUrl.startsWith("data:image/") || !dataUrl.contains(";base64,")) {
                throw new BusinessException("证据图片格式无效");
            }
            if (dataUrl.length() > MAX_EVIDENCE_DATA_URL_LENGTH) {
                throw new BusinessException("单张证据图片过大，请压缩后重新上传");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", firstText(image, "id", "name"));
            normalized.put("name", safeText(image.get("name")));
            normalized.put("type", safeText(image.get("type")));
            normalized.put("size", longValue(image.get("size"), 0));
            normalized.put("width", numberValue(image.get("width")));
            normalized.put("height", numberValue(image.get("height")));
            normalized.put("dataUrl", dataUrl);
            result.add(normalized);
            if (result.size() >= MAX_EVIDENCE_IMAGES) {
                break;
            }
        }
        return result;
    }

    private void snapshotCurrentData(Path root, Path versionDir) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new BusinessException("真实数据目录不存在: " + root);
        }
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> children = stream
                    .filter(path -> !shouldSkipSnapshotPath(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path child : children) {
                copyRecursively(child, versionDir.resolve(root.relativize(child)));
            }
        }
    }

    private boolean shouldSkipSnapshotPath(Path path) {
        String name = path.getFileName().toString();
        return VERSION_FOLDER.equals(name) || EDIT_STATE_FOLDER.equals(name) || name.startsWith("._");
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            try (Stream<Path> stream = Files.walk(source)) {
                List<Path> paths = stream
                        .filter(path -> !path.getFileName().toString().startsWith("._"))
                        .sorted()
                        .toList();
                for (Path path : paths) {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private List<Map<String, Object>> operationsForMaterialization(TargetVersion latest, String datasetType, List<Map<String, Object>> operations) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (latest != null && !latest.materializedData()) {
            result.addAll(latest.operations());
        }
        for (Map<String, Object> operation : operations) {
            Map<String, Object> normalized = new LinkedHashMap<>(operation);
            if (normalizeDatasetType(textValue(normalized.get("datasetType"))).isBlank()) {
                normalized.put("datasetType", datasetType);
            }
            result.add(normalized);
        }
        return result;
    }

    private void materializeVersionShp(Path versionDir, List<Map<String, Object>> operations) {
        if (operations.isEmpty()) {
            return;
        }
        Set<String> datasetTypes = materializableDatasetTypes(operations);
        if (datasetTypes.contains("line") || datasetTypes.contains("station")) {
            Map<String, Object> lines = readStandardShp(versionDir.resolve(BUS_LINE_FOLDER), STANDARD_ROUTE_FIELDS, LineString.class, "线路");
            Map<String, Object> routeStops = readStationData(versionDir.resolve(BUS_STATION_FOLDER));
            applyUploadComparableEdits(operations, lines, "line");
            applyUploadComparableEdits(operations, routeStops, "station");
            enrichDerivedAttributes(lines, routeStops);
            writeStandardShp(versionDir.resolve(BUS_LINE_FOLDER), STANDARD_ROUTE_FIELDS, LineString.class, "线路", lines);
            writeStationData(versionDir.resolve(BUS_STATION_FOLDER), routeStops);
        }
        if (datasetTypes.contains("depot")) {
            Map<String, Object> depots = readFirstShp(versionDir.resolve(BUS_DEPOT_FOLDER));
            applyUploadComparableEdits(operations, depots, "depot");
            writeFirstShp(versionDir.resolve(BUS_DEPOT_FOLDER), "场站", depots);
        }
    }

    private Set<String> materializableDatasetTypes(List<Map<String, Object>> operations) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> operation : operations) {
            String datasetType = normalizeDatasetType(textValue(operation.get("datasetType")));
            if ("line".equals(datasetType) || "station".equals(datasetType) || "depot".equals(datasetType)) {
                result.add(datasetType);
            }
        }
        return result;
    }

    private void writeFirstShp(Path folder, String datasetLabel, Map<String, Object> collection) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            throw new BusinessException("缺少" + datasetLabel + "SHP: " + folder);
        }
        rewriteShp(shpFile, null, null, datasetLabel, collection);
    }

    private void writeStandardShp(Path folder, List<String> expectedFields, Class<? extends Geometry> expectedGeometryType, String datasetLabel, Map<String, Object> collection) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            throw new BusinessException("缺少标准" + datasetLabel + "SHP: " + folder);
        }
        rewriteShp(shpFile, expectedFields, expectedGeometryType, datasetLabel, collection);
    }

    private void rewriteShp(File shpFile, List<String> expectedFields, Class<? extends Geometry> expectedGeometryType, String datasetLabel, Map<String, Object> collection) {
        ShapefileDataStore dataStore = null;
        Transaction transaction = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setMemoryMapped(false);
            dataStore.setBufferCachingEnabled(false);
            dataStore.setCharset(shapefileCharset(shpFile));
            String typeName = dataStore.getTypeNames()[0];
            SimpleFeatureType schema = dataStore.getSchema(typeName);
            validateStandardSchema(shpFile, schema, expectedFields, expectedGeometryType, datasetLabel);
            List<String> expandedFields = expandedAttributeFields(schema, collection);
            if (expandedFields.size() > schemaAttributeFields(schema).size()) {
                dataStore.dispose();
                dataStore = null;
                rewriteExpandedShapefile(shpFile, expandedFields, expectedGeometryType, datasetLabel, collection);
                return;
            }
            SimpleFeatureCollection features = toSimpleFeatureCollection(schema, collection, expectedGeometryType, datasetLabel);
            SimpleFeatureSource source = dataStore.getFeatureSource(typeName);
            if (!(source instanceof SimpleFeatureStore store)) {
                throw new BusinessException(datasetLabel + "SHP 不支持写入: " + shpFile.getAbsolutePath());
            }
            transaction = new DefaultTransaction("rewrite-real-data-shp");
            store.setTransaction(transaction);
            store.removeFeatures(Filter.INCLUDE);
            store.addFeatures(features);
            transaction.commit();
        } catch (BusinessException error) {
            rollbackQuietly(transaction);
            throw error;
        } catch (Exception error) {
            rollbackQuietly(transaction);
            throw new BusinessException("写入真实数据 shp 失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            closeQuietly(transaction);
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    private void rewriteExpandedShapefile(
            File shpFile,
            List<String> fields,
            Class<? extends Geometry> expectedGeometryType,
            String datasetLabel,
            Map<String, Object> collection
    ) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(shpFile.toPath().getParent(), ".shp-rewrite-");
            Path tempShp = tempDir.resolve(shpFile.getName());
            writeNewShapefile(shpFile, tempShp, fields, expectedGeometryType, datasetLabel, collection);
            replaceShapefileBundle(tempShp, shpFile.toPath());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("扩展" + datasetLabel + "SHP 字段失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void writeNewShapefile(
            File sourceShp,
            Path targetShp,
            List<String> fields,
            Class<? extends Geometry> expectedGeometryType,
            String datasetLabel,
            Map<String, Object> collection
    ) {
        ShapefileDataStore sourceStore = null;
        ShapefileDataStore targetStore = null;
        Transaction transaction = null;
        try {
            sourceStore = new ShapefileDataStore(sourceShp.toURI().toURL());
            sourceStore.setMemoryMapped(false);
            sourceStore.setBufferCachingEnabled(false);
            Charset charset = shapefileCharset(sourceShp);
            sourceStore.setCharset(charset);
            SimpleFeatureType sourceSchema = sourceStore.getSchema(sourceStore.getTypeNames()[0]);
            Class<?> geometryBinding = sourceSchema.getGeometryDescriptor() == null
                    ? expectedGeometryType
                    : sourceSchema.getGeometryDescriptor().getType().getBinding();
            if ("station".equals(normalizeDatasetType(datasetLabel))) {
                geometryBinding = Point.class;
            }

            SimpleFeatureTypeBuilder schemaBuilder = new SimpleFeatureTypeBuilder();
            schemaBuilder.setName(sourceSchema.getTypeName());
            if (sourceSchema.getCoordinateReferenceSystem() != null) {
                schemaBuilder.setCRS(sourceSchema.getCoordinateReferenceSystem());
            }
            String geometryName = sourceSchema.getGeometryDescriptor() == null
                    ? "the_geom"
                    : sourceSchema.getGeometryDescriptor().getName().getLocalPart();
            schemaBuilder.add(geometryName, geometryBinding == null ? Geometry.class : geometryBinding);

            Map<String, AttributeDescriptor> sourceDescriptors = new LinkedHashMap<>();
            for (AttributeDescriptor descriptor : sourceSchema.getAttributeDescriptors()) {
                if (sourceSchema.getGeometryDescriptor() != null
                        && descriptor.getName().equals(sourceSchema.getGeometryDescriptor().getName())) {
                    continue;
                }
                sourceDescriptors.put(descriptor.getName().getLocalPart(), descriptor);
            }
            for (String field : fields) {
                AttributeDescriptor sourceDescriptor = sourceDescriptors.get(field);
                if (sourceDescriptor != null) {
                    schemaBuilder.add(sourceDescriptor);
                    continue;
                }
                Class<?> binding = inferredAttributeBinding(collection, field);
                if (String.class.equals(binding)) {
                    schemaBuilder.length(inferredStringLength(collection, field));
                }
                schemaBuilder.add(field, binding);
            }
            SimpleFeatureType targetSchema = schemaBuilder.buildFeatureType();

            Files.createDirectories(targetShp.getParent());
            ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
            Map<String, Serializable> params = new LinkedHashMap<>();
            params.put("url", targetShp.toUri().toURL());
            params.put("create spatial index", Boolean.TRUE);
            targetStore = (ShapefileDataStore) factory.createNewDataStore(params);
            targetStore.setCharset(charset);
            targetStore.createSchema(targetSchema);

            SimpleFeatureCollection features = toSimpleFeatureCollection(
                    targetStore.getSchema(targetStore.getTypeNames()[0]),
                    collection,
                    expectedGeometryType,
                    datasetLabel
            );
            SimpleFeatureSource source = targetStore.getFeatureSource(targetStore.getTypeNames()[0]);
            if (!(source instanceof SimpleFeatureStore store)) {
                throw new BusinessException(datasetLabel + "SHP 不支持写入: " + targetShp);
            }
            transaction = new DefaultTransaction("create-real-data-shp");
            store.setTransaction(transaction);
            store.addFeatures(features);
            transaction.commit();
            Files.writeString(sidecarPath(targetShp, ".cpg"), charset.name(), StandardCharsets.UTF_8);
            copyProjectionIfMissing(sourceShp.toPath(), targetShp);
        } catch (BusinessException error) {
            rollbackQuietly(transaction);
            throw error;
        } catch (Exception error) {
            rollbackQuietly(transaction);
            throw new BusinessException("生成" + datasetLabel + "SHP 失败: " + targetShp, error);
        } finally {
            closeQuietly(transaction);
            if (targetStore != null) {
                targetStore.dispose();
            }
            if (sourceStore != null) {
                sourceStore.dispose();
            }
        }
    }

    private List<String> expandedAttributeFields(SimpleFeatureType schema, Map<String, Object> collection) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(schemaAttributeFields(schema));
        for (Map<String, Object> feature : mutableMapList(collection.get("features"))) {
            featureProperties(feature).keySet().stream()
                    .filter(key -> !key.startsWith("_"))
                    .forEach(fields::add);
        }
        return List.copyOf(fields);
    }

    private List<String> schemaAttributeFields(SimpleFeatureType schema) {
        return schema.getAttributeDescriptors().stream()
                .filter(descriptor -> schema.getGeometryDescriptor() == null
                        || !descriptor.getName().equals(schema.getGeometryDescriptor().getName()))
                .map(descriptor -> descriptor.getName().getLocalPart())
                .toList();
    }

    private Class<?> inferredAttributeBinding(Map<String, Object> collection, String field) {
        boolean sawValue = false;
        boolean allIntegral = true;
        boolean allNumeric = true;
        boolean allBoolean = true;
        for (Map<String, Object> feature : mutableMapList(collection.get("features"))) {
            Object value = featureProperties(feature).get(field);
            if (value == null || safeText(value).isBlank()) {
                continue;
            }
            sawValue = true;
            allBoolean &= value instanceof Boolean;
            allNumeric &= value instanceof Number;
            allIntegral &= value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
        }
        if (!sawValue) return String.class;
        if (allBoolean) return Boolean.class;
        if (allIntegral) return Long.class;
        if (allNumeric) return Double.class;
        return String.class;
    }

    private int inferredStringLength(Map<String, Object> collection, String field) {
        int length = 32;
        for (Map<String, Object> feature : mutableMapList(collection.get("features"))) {
            length = Math.max(length, safeText(featureProperties(feature).get(field)).length());
        }
        return Math.min(length, 254);
    }

    private void replaceShapefileBundle(Path sourceShp, Path targetShp) throws IOException {
        String targetBaseName = fileBaseName(targetShp.getFileName().toString());
        Path targetFolder = targetShp.getParent();
        try (Stream<Path> stream = Files.list(targetFolder)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                if (fileBaseName(path.getFileName().toString()).equalsIgnoreCase(targetBaseName)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Path sourceFolder = sourceShp.getParent();
        String sourceBaseName = fileBaseName(sourceShp.getFileName().toString());
        try (Stream<Path> stream = Files.list(sourceFolder)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                if (!fileBaseName(path.getFileName().toString()).equalsIgnoreCase(sourceBaseName)) {
                    continue;
                }
                Files.move(path, targetFolder.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void copyProjectionIfMissing(Path sourceShp, Path targetShp) throws IOException {
        Path targetPrj = sidecarPath(targetShp, ".prj");
        if (Files.isRegularFile(targetPrj)) return;
        Path sourcePrj = sidecarPath(sourceShp, ".prj");
        if (Files.isRegularFile(sourcePrj)) {
            Files.copy(sourcePrj, targetPrj, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path sidecarPath(Path shpPath, String extension) {
        return shpPath.resolveSibling(fileBaseName(shpPath.getFileName().toString()) + extension);
    }

    private String fileBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private SimpleFeatureCollection toSimpleFeatureCollection(SimpleFeatureType schema, Map<String, Object> collection, Class<? extends Geometry> expectedGeometryType, String datasetLabel) {
        DefaultFeatureCollection result = new DefaultFeatureCollection(null, schema);
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        List<Map<String, Object>> features = mutableMapList(collection.get("features"));
        for (int index = 0; index < features.size(); index++) {
            Map<String, Object> feature = features.get(index);
            Geometry geometry = geometryFromGeoJson(feature.get("geometry"));
            if (geometry == null || geometry.isEmpty()) {
                throw new BusinessException(datasetLabel + "SHP 写入失败: 第 " + (index + 1) + " 行缺少几何");
            }
            Class<?> geometryBinding = schema.getGeometryDescriptor() == null ? null : schema.getGeometryDescriptor().getType().getBinding();
            geometry = coerceGeometryForSchema(geometry, geometryBinding);
            if (!isExpectedGeometry(geometry, expectedGeometryType)) {
                throw new BusinessException(datasetLabel + "SHP 写入失败: 第 " + (index + 1) + " 行几何类型为 " + geometry.getGeometryType());
            }
            Map<String, Object> properties = featureProperties(feature);
            builder.reset();
            for (AttributeDescriptor descriptor : schema.getAttributeDescriptors()) {
                String fieldName = descriptor.getName().getLocalPart();
                if (schema.getGeometryDescriptor() != null && descriptor.getName().equals(schema.getGeometryDescriptor().getName())) {
                    builder.add(geometry);
                } else {
                    builder.add(coerceAttributeValue(properties.get(fieldName), descriptor.getType().getBinding()));
                }
            }
            String featureId = safeText(feature.get("id"));
            result.add(builder.buildFeature(featureId.isBlank() ? null : featureId));
        }
        return result;
    }

    private Geometry coerceGeometryForSchema(Geometry geometry, Class<?> binding) {
        if (binding == null || binding.isInstance(geometry)) {
            return geometry;
        }
        if (MultiLineString.class.isAssignableFrom(binding) && geometry instanceof LineString lineString) {
            return GEOMETRY_FACTORY.createMultiLineString(new LineString[]{lineString});
        }
        if (LineString.class.isAssignableFrom(binding) && geometry instanceof MultiLineString multiLineString && multiLineString.getNumGeometries() == 1) {
            return multiLineString.getGeometryN(0);
        }
        return geometry;
    }

    private Object coerceAttributeValue(Object value, Class<?> binding) {
        String text = safeText(value);
        if (binding == null || String.class.equals(binding)) {
            return text;
        }
        if (text.isBlank()) {
            return null;
        }
        try {
            if (Integer.class.equals(binding) || int.class.equals(binding)) return Integer.parseInt(text);
            if (Long.class.equals(binding) || long.class.equals(binding)) return Long.parseLong(text);
            if (Double.class.equals(binding) || double.class.equals(binding)) return Double.parseDouble(text);
            if (Float.class.equals(binding) || float.class.equals(binding)) return Float.parseFloat(text);
            if (Short.class.equals(binding) || short.class.equals(binding)) return Short.parseShort(text);
            if (Boolean.class.equals(binding) || boolean.class.equals(binding)) return Boolean.parseBoolean(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
        return text;
    }

    private Geometry geometryFromGeoJson(Object geometryValue) {
        if (!(geometryValue instanceof Map<?, ?> rawGeometry)) {
            return null;
        }
        Map<String, Object> geometry = stringObjectMap(rawGeometry);
        String type = safeText(geometry.get("type"));
        Object coordinates = geometry.get("coordinates");
        return switch (type) {
            case "Point" -> {
                Coordinate coordinate = coordinateFromValue(coordinates);
                yield coordinate == null ? null : GEOMETRY_FACTORY.createPoint(coordinate);
            }
            case "LineString" -> GEOMETRY_FACTORY.createLineString(coordinateArrayFromValue(coordinates));
            case "MultiLineString" -> {
                if (!(coordinates instanceof List<?> lines)) {
                    yield null;
                }
                List<LineString> lineStrings = new ArrayList<>();
                for (Object line : lines) {
                    Coordinate[] lineCoordinates = coordinateArrayFromValue(line);
                    if (lineCoordinates.length >= 2) {
                        lineStrings.add(GEOMETRY_FACTORY.createLineString(lineCoordinates));
                    }
                }
                yield lineStrings.isEmpty() ? null : GEOMETRY_FACTORY.createMultiLineString(lineStrings.toArray(LineString[]::new));
            }
            default -> null;
        };
    }

    private Coordinate[] coordinateArrayFromValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return new Coordinate[0];
        }
        List<Coordinate> coordinates = new ArrayList<>();
        for (Object item : list) {
            Coordinate coordinate = coordinateFromValue(item);
            if (coordinate != null) {
                coordinates.add(coordinate);
            }
        }
        return coordinates.toArray(Coordinate[]::new);
    }

    private Coordinate coordinateFromValue(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 2) {
            return null;
        }
        Double lng = doubleValue(list.get(0));
        Double lat = doubleValue(list.get(1));
        return lng == null || lat == null ? null : new Coordinate(lng, lat);
    }

    private void rollbackQuietly(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        try {
            transaction.rollback();
        } catch (Exception ignored) {
            // Best effort rollback for shapefile rewrite failures.
        }
    }

    private void closeQuietly(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        try {
            transaction.close();
        } catch (Exception ignored) {
            // Best effort cleanup for shapefile rewrite transactions.
        }
    }

    @SuppressWarnings("unchecked")
    private void applyEditOperation(Map<String, Object> collection, Map<String, Object> operation, String datasetType) {
        Object featuresValue = collection.get("features");
        if (!(featuresValue instanceof List<?> rawFeatures)) {
            return;
        }
        List<Map<String, Object>> features = (List<Map<String, Object>>) rawFeatures;
        String type = safeText(operation.get("type"));
        Map<String, Object> payload = operationPayload(operation);
        if (type.startsWith("add_")) {
            Map<String, Object> payloadFeature = payloadFeature(payload);
            if (payloadFeature != null) {
                features.add(payloadFeature);
                return;
            }
            Map<String, Object> feature = featureFromOperation(operation, payload, datasetType);
            if (feature != null) {
                features.add(feature);
            }
            return;
        }
        String targetId = targetId(operation, payload);
        if (targetId.isBlank()) {
            return;
        }
        int index = findFeatureIndex(features, targetId);
        if (index < 0) {
            return;
        }
        if (type.startsWith("replace_")) {
            Map<String, Object> payloadFeature = payloadFeature(payload);
            if (payloadFeature != null) {
                features.set(index, payloadFeature);
            }
            return;
        }
        if (type.startsWith("delete_")) {
            features.remove(index);
            return;
        }

        Map<String, Object> feature = features.get(index);
        Map<String, Object> properties = featureProperties(feature);
        if (type.startsWith("rename_")) {
            String name = firstText(payload, "name", "newName", "stationName", "lineName", "depotName");
            if (!name.isBlank()) {
                properties.put(nameField(properties, datasetType), name);
            }
        } else if (type.startsWith("move_")) {
            Double lng = doubleValue(payload.get("lng"));
            Double lat = doubleValue(payload.get("lat"));
            if (lng != null && lat != null) {
                feature.put("geometry", pointGeometry(lng, lat));
            }
        } else if ("update_line_headway".equals(type)) {
            String headway = firstText(payload, "headway", "avgHeadway", "interval");
            if (!headway.isBlank()) {
                properties.put(lineHeadwayField(properties), headway);
            }
        } else if ("update_line_stations".equals(type)) {
            String stations = firstText(payload, "stations", "stationList", "stationNames");
            if (!stations.isBlank()) {
                properties.put("station_list_edit", stations);
            }
        }
    }

    private void applyLineStationReorder(List<Map<String, Object>> features, Map<String, Object> payload) {
        String lineId = firstText(payload, "lineId", "line_id");
        String direction = firstText(payload, "dir", "direction");
        List<Map<String, Object>> changes = mutableMapList(payload.get("changes"));
        List<Map<String, Object>> matchedFeatures = new ArrayList<>();
        List<Object> sequenceValues = new ArrayList<>();
        for (Map<String, Object> change : changes) {
            String targetId = firstText(change, "targetId", "featureId");
            String stopId = firstText(change, "stopId", "stop_id");
            String changeDirection = firstText(change, "dir", "direction");
            String targetDirection = changeDirection.isBlank() ? direction : changeDirection;
            String fromSequence = firstText(change, "fromSeq", "from_sequence");
            String toSequence = firstText(change, "toSeq", "seq");
            if ((targetId.isBlank() && stopId.isBlank()) || toSequence.isBlank()) {
                continue;
            }
            String routeStopTarget = lineId.isBlank() || targetDirection.isBlank()
                    || stopId.isBlank() || fromSequence.isBlank()
                    ? ""
                    : lineId + "|" + targetDirection + "|" + stopId + "|" + fromSequence;
            for (Map<String, Object> feature : features) {
                Map<String, Object> properties = featureProperties(feature);
                if (!lineId.isBlank() && !lineId.equals(firstText(properties, "line_id", "route_id"))) {
                    continue;
                }
                if (!targetDirection.isBlank()
                        && !targetDirection.equals(firstText(properties, "dir", "direction"))) {
                    continue;
                }
                if ((!targetId.isBlank() && isExactStationTarget(feature, targetId))
                        || (!routeStopTarget.isBlank() && isExactStationTarget(feature, routeStopTarget))
                        || (!stopId.isBlank()
                            && stopId.equals(firstText(properties, "stop_id"))
                            && fromSequence.equals(firstText(properties, "seq", "sequence")))) {
                    matchedFeatures.add(feature);
                    sequenceValues.add(integerOrText(toSequence));
                    break;
                }
            }
        }
        for (int index = 0; index < matchedFeatures.size(); index++) {
            featureProperties(matchedFeatures.get(index)).put("seq", sequenceValues.get(index));
        }
    }

    private Map<String, Object> payloadFeature(Map<String, Object> payload) {
        Object featureValue = payload.get("feature");
        if (!(featureValue instanceof Map<?, ?> feature)) {
            return null;
        }
        Map<String, Object> result = stringObjectMap(feature);
        featureProperties(result).keySet().removeIf(DERIVED_FIELDS::contains);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyStationEditOperation(Map<String, Object> routeStops, Map<String, Object> operation) {
        Object featuresValue = routeStops.get("features");
        if (!(featuresValue instanceof List<?> rawFeatures)) {
            return;
        }
        List<Map<String, Object>> features = (List<Map<String, Object>>) rawFeatures;
        String type = safeText(operation.get("type"));
        Map<String, Object> payload = operationPayload(operation);
        if ("reorder_line_stations".equals(type)) {
            applyLineStationReorder(features, payload);
            return;
        }
        if (type.startsWith("add_")) {
            Map<String, Object> payloadFeature = payloadFeature(payload);
            if (payloadFeature != null) {
                features.add(payloadFeature);
                return;
            }
            Map<String, Object> feature = standardStopFeatureFromOperation(operation, payload);
            if (feature != null) {
                features.add(feature);
            }
            return;
        }
        String targetId = targetId(operation, payload);
        if (targetId.isBlank()) {
            return;
        }
        boolean routeScoped = "route".equals(stationOperationScope(operation));
        if (type.startsWith("replace_")) {
            Map<String, Object> payloadFeature = payloadFeature(payload);
            if (payloadFeature == null) {
                return;
            }
            Map<String, Object> payloadProperties = featureProperties(payloadFeature);
            String payloadStationKey = firstText(payloadProperties, "_stationKey");
            String payloadStopId = firstText(payloadProperties, "stop_id");
            if (!routeScoped && (targetId.equals(payloadStationKey) || targetId.equals(payloadStopId))) {
                for (Map<String, Object> feature : features) {
                    if (isStationTarget(feature, targetId)) {
                        if (type.endsWith("_from_shp")) {
                            featureProperties(feature).keySet().removeIf(key ->
                                    !key.startsWith("_")
                                            && !ROUTE_STOP_RELATION_FIELDS.contains(key)
                                            && !DERIVED_FIELDS.contains(key)
                                            && !payloadProperties.containsKey(key));
                        }
                        applyPhysicalStationReplacement(feature, payloadFeature);
                    }
                }
                return;
            }
            for (int index = 0; index < features.size(); index++) {
                if (stationTargetMatches(features.get(index), targetId, routeScoped)) {
                    features.set(index, payloadFeature);
                    return;
                }
            }
            return;
        }
        if (type.startsWith("delete_")) {
            features.removeIf(feature -> stationTargetMatches(feature, targetId, routeScoped));
            return;
        }
        for (Map<String, Object> feature : features) {
            if (!stationTargetMatches(feature, targetId, routeScoped)) {
                continue;
            }
            Map<String, Object> properties = featureProperties(feature);
            if (type.startsWith("rename_")) {
                String name = firstText(payload, "name", "newName", "stationName");
                if (!name.isBlank()) {
                    properties.put("stop_name", name);
                }
            } else if (type.startsWith("move_")) {
                Double lng = doubleValue(payload.get("lng"));
                Double lat = doubleValue(payload.get("lat"));
                if (lng != null && lat != null) {
                    feature.put("geometry", pointGeometry(lng, lat));
                    properties.put("lon", round6(lng));
                    properties.put("lat", round6(lat));
                }
            }
        }
    }

    private void applyPhysicalStationReplacement(Map<String, Object> targetFeature, Map<String, Object> replacementFeature) {
        Object geometry = replacementFeature.get("geometry");
        if (geometry != null) {
            targetFeature.put("geometry", geometry);
        }
        Map<String, Object> targetProperties = featureProperties(targetFeature);
        Map<String, Object> replacementProperties = featureProperties(replacementFeature);
        replacementProperties.forEach((key, value) -> {
            if (!key.startsWith("_")
                    && !ROUTE_STOP_RELATION_FIELDS.contains(key)
                    && !DERIVED_FIELDS.contains(key)) {
                targetProperties.put(key, value);
            }
        });
    }

    private boolean isStationTarget(Map<String, Object> feature, String targetId) {
        if (isExactStationTarget(feature, targetId)) {
            return true;
        }
        Map<String, Object> properties = featureProperties(feature);
        for (String key : List.of("_stationKey", "stop_id", "line_id", "id", "stop_name")) {
            String value = firstText(properties, key);
            if (!value.isBlank() && targetId.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean stationTargetMatches(Map<String, Object> feature, String targetId, boolean routeScoped) {
        return routeScoped ? isExactStationTarget(feature, targetId) : isStationTarget(feature, targetId);
    }

    private boolean isExactStationTarget(Map<String, Object> feature, String targetId) {
        if (targetId.equals(safeText(feature.get("id")))) {
            return true;
        }
        Map<String, Object> properties = featureProperties(feature);
        if (targetId.equals(routeStopFeatureKey(properties))) {
            return true;
        }
        for (String key : List.of("_featureId", "_routeStopKey")) {
            String value = firstText(properties, key);
            if (!value.isBlank() && targetId.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> standardStopFeatureFromOperation(Map<String, Object> operation, Map<String, Object> payload) {
        Map<String, Object> feature = featureFromOperation(operation, payload, "station");
        if (feature == null) {
            return null;
        }
        Map<String, Object> properties = featureProperties(feature);
        String name = firstText(properties, "stop_name", "name");
        String id = safeText(feature.get("id"));
        properties.clear();
        properties.put("_featureId", id);
        properties.put("line_id", "");
        properties.put("dir", "");
        properties.put("stop_id", id);
        properties.put("stop_name", name.isBlank() ? "未命名站点" : name);
        properties.put("seq", "");
        properties.put("lon", firstCoordinateValue(feature, 0));
        properties.put("lat", firstCoordinateValue(feature, 1));
        return feature;
    }

    private Map<String, Object> featureFromOperation(Map<String, Object> operation, Map<String, Object> payload, String datasetType) {
        Double lng = doubleValue(payload.get("lng"));
        Double lat = doubleValue(payload.get("lat"));
        if (lng == null || lat == null) {
            return null;
        }
        String id = firstText(operation, "operationId", "id");
        if (id.isBlank()) {
            id = datasetType + "_" + UUID.randomUUID();
        }
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", id);
        feature.put("geometry", pointGeometry(lng, lat));
        Map<String, Object> properties = new LinkedHashMap<>();
        String name = firstText(payload, "name", "stationName", "depotName");
        properties.put("_featureId", id);
        properties.put(nameField(properties, datasetType), name.isBlank() ? "未命名" : name);
        feature.put("properties", properties);
        return feature;
    }

    private Map<String, Object> pointGeometry(double lng, double lat) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Point");
        geometry.put("coordinates", List.of(round6(lng), round6(lat)));
        return geometry;
    }

    private void refreshFeatureCollectionMetadata(Map<String, Object> collection) {
        Object featuresValue = collection.get("features");
        if (!(featuresValue instanceof List<?> features)) {
            collection.put("featureCount", 0);
            collection.put("bounds", null);
            return;
        }
        double[] bounds = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        int count = 0;
        for (Object item : features) {
            if (!(item instanceof Map<?, ?> feature)) {
                continue;
            }
            count++;
            expandGeoJsonBounds(feature.get("geometry"), bounds);
        }
        collection.put("featureCount", count);
        collection.put("bounds", Double.isFinite(bounds[0]) ? bounds : null);
    }

    private void expandGeoJsonBounds(Object geometryValue, double[] bounds) {
        if (!(geometryValue instanceof Map<?, ?> geometry)) {
            return;
        }
        expandCoordinateBounds(geometry.get("coordinates"), bounds);
    }

    private void expandCoordinateBounds(Object value, double[] bounds) {
        if (value instanceof List<?> list) {
            if (list.size() >= 2 && list.get(0) instanceof Number && list.get(1) instanceof Number) {
                double lng = ((Number) list.get(0)).doubleValue();
                double lat = ((Number) list.get(1)).doubleValue();
                bounds[0] = Math.min(bounds[0], lng);
                bounds[1] = Math.min(bounds[1], lat);
                bounds[2] = Math.max(bounds[2], lng);
                bounds[3] = Math.max(bounds[3], lat);
                return;
            }
            for (Object item : list) {
                expandCoordinateBounds(item, bounds);
            }
        }
    }

    private String normalizeDatasetType(String value) {
        String text = safeText(value).toLowerCase();
        return switch (text) {
            case "station", "stations", "站点" -> "station";
            case "line", "lines", "route", "线路" -> "line";
            case "depot", "depots", "场站" -> "depot";
            case "all", "mixed", "综合", "全部数据" -> "all";
            default -> "";
        };
    }

    private List<Map<String, Object>> mutableMapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(stringObjectMap(map));
                }
            }
        }
        return result;
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private Map<String, Object> operationPayload(Map<String, Object> operation) {
        Object payload = operation.get("payload");
        return payload instanceof Map<?, ?> map ? stringObjectMap(map) : operation;
    }

    private String targetId(Map<String, Object> operation, Map<String, Object> payload) {
        return firstText(operation, "targetId", "featureId", "stationKey", "lineKey", "depotKey", "_featureId", "_stationKey")
                .isBlank()
                ? firstText(payload, "targetId", "featureId", "stationKey", "lineKey", "depotKey", "_featureId", "_stationKey")
                : firstText(operation, "targetId", "featureId", "stationKey", "lineKey", "depotKey", "_featureId", "_stationKey");
    }

    private int findFeatureIndex(List<Map<String, Object>> features, String targetId) {
        for (int index = 0; index < features.size(); index++) {
            Map<String, Object> feature = features.get(index);
            if (targetId.equals(safeText(feature.get("id")))) {
                return index;
            }
            Map<String, Object> properties = featureProperties(feature);
            if (targetId.equals(routeFeatureKey(properties))
                    || targetId.equals(routeStopFeatureKey(properties))
                    || targetId.equals(depotFeatureKey(feature))) {
                return index;
            }
            for (String key : List.of(
                    "_featureId", "_stationKey", "_lineKey", "_depotKey",
                    "line_id", "route_id", "stop_id", "depot_id", "station_id",
                    "id", "name", "stop_name", "depot_name", "场站名称", "station_name"
            )) {
                String value = firstText(properties, key);
                if (!value.isBlank() && targetId.equals(value)) {
                    return index;
                }
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> featureProperties(Map<String, Object> feature) {
        Object value = feature.get("properties");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> properties = (Map<String, Object>) value;
            feature.put("properties", properties);
            return properties;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        feature.put("properties", properties);
        return properties;
    }

    private String nameField(Map<String, Object> properties, String datasetType) {
        List<String> candidates = switch (datasetType) {
            case "line" -> List.of("name");
            case "depot" -> List.of("depot_name", "name", "场站名称", "station_name");
            default -> List.of("stop_name");
        };
        for (String candidate : candidates) {
            if (properties.containsKey(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private String lineHeadwayField(Map<String, Object> properties) {
        return "interval";
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = safeText(source.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String adminDistrictName(Map<String, Object> properties) {
        return firstText(properties,
                "_districtName",
                "Name",
                "name",
                "NAME",
                "名称",
                "区名",
                "行政区",
                "行政区名",
                "区县",
                "县区",
                "district",
                "District",
                "AdminName");
    }

    private String textValue(Object value) {
        return safeText(value);
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = safeText(value);
            return text.isBlank() ? null : Double.parseDouble(text);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private Double coverageRate(double coverageKm2, double adminAreaKm2) {
        if (coverageKm2 <= 0 || adminAreaKm2 <= 0) {
            return null;
        }
        return round2(coverageKm2 / adminAreaKm2 * 100.0);
    }

    private Map<String, Object> readFirstShp(Path folder) {
        return readShp(findFirstShp(folder), null, null, "真实数据");
    }

    private Map<String, Object> readStandardShp(Path folder, List<String> expectedFields, Class<? extends Geometry> expectedGeometryType, String datasetLabel) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            throw new BusinessException("缺少标准" + datasetLabel + "SHP: " + folder);
        }
        return readShp(shpFile, expectedFields, expectedGeometryType, datasetLabel);
    }

    /**
     * 读取站点数据为"线路经停占位"集合（line_id/dir/stop_id/stop_name/seq/lon/lat）。
     * 兼容两种磁盘格式：
     * 1) 旧标准：站点 SHP 即占位级（含 line_id/seq 字段），直接读取；
     * 2) 物理唯一站台：站点 SHP 每站台一个点（stop_id/stop_name/lon/lat），
     *    经停关系由同目录 line_stop_sequence.csv 提供，读取时合成占位集合。
     */
    private Map<String, Object> readStationData(Path folder) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            throw new BusinessException("缺少标准站点SHP: " + folder);
        }
        if (isUniqueStationShp(shpFile)) {
            Map<String, Object> uniqueStops = readShp(shpFile, UNIQUE_STOP_FIELDS, Point.class, "站点");
            return routeStopsFromSequenceCsv(folder, uniqueStops);
        }
        return readShp(shpFile, STANDARD_STOP_FIELDS, Point.class, "站点");
    }

    /**
     * 写回站点数据，保持磁盘原有格式：
     * 旧标准直接重写占位 SHP；物理唯一站台格式则聚合站台重写 SHP，并同步经停 CSV。
     */
    private void writeStationData(Path folder, Map<String, Object> routeStops) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            throw new BusinessException("缺少标准站点SHP: " + folder);
        }
        if (isUniqueStationShp(shpFile)) {
            Map<String, Object> uniqueStops = uniqueStationCollectionForWrite(routeStops);
            rewriteShp(shpFile, UNIQUE_STOP_FIELDS, Point.class, "站点", uniqueStops);
            writeSequenceCsv(folder.resolve(STATION_SEQUENCE_CSV), routeStops);
            return;
        }
        rewriteShp(shpFile, STANDARD_STOP_FIELDS, Point.class, "站点", routeStops);
    }

    private boolean isUniqueStationShp(File shpFile) {
        List<String> fields = shapefileFieldNames(shpFile);
        return fields.contains("stop_id")
                && !(fields.contains("line_id") && fields.contains("seq"));
    }

    private List<String> shapefileFieldNames(File shpFile) {
        ShapefileDataStore dataStore = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setMemoryMapped(false);
            dataStore.setBufferCachingEnabled(false);
            SimpleFeatureType schema = dataStore.getSchema(dataStore.getTypeNames()[0]);
            return schema.getAttributeDescriptors().stream()
                    .filter(descriptor -> schema.getGeometryDescriptor() == null
                            || !descriptor.getName().equals(schema.getGeometryDescriptor().getName()))
                    .map(descriptor -> descriptor.getName().getLocalPart())
                    .toList();
        } catch (Exception error) {
            throw new BusinessException("读取真实数据 shp 字段失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> routeStopsFromSequenceCsv(Path folder, Map<String, Object> uniqueStops) {
        Path csvFile = folder == null ? null : folder.resolve(STATION_SEQUENCE_CSV);
        if (csvFile == null || !Files.isRegularFile(csvFile)) {
            throw new BusinessException("站点为物理唯一站台格式，但缺少经停关系文件 "
                    + STATION_SEQUENCE_CSV + ": " + folder
                    + "；该文件需与站点 SHP 同目录，包含 line_id,seq,stop_id,stop_name,lon,lat 列");
        }

        Map<String, Map<String, Object>> stationByStopId = new LinkedHashMap<>();
        for (Map<String, Object> feature : mutableMapList(uniqueStops.get("features"))) {
            String stopId = firstText(featureProperties(feature), "stop_id");
            if (!stopId.isBlank()) {
                if (stationByStopId.putIfAbsent(stopId, feature) != null) {
                    throw new BusinessException("站点 SHP 存在重复 stop_id: " + stopId);
                }
            }
        }

        Map<String, Object> collection = emptyFeatureCollection();
        List<Map<String, Object>> features = (List<Map<String, Object>>) collection.get("features");
        Set<String> referencedStopIds = new LinkedHashSet<>();
        for (Map<String, String> row : readSequenceCsv(csvFile)) {
            String lineId = safeText(row.get("line_id"));
            String direction = safeText(row.getOrDefault("dir", "0"));
            if (direction.isBlank()) {
                direction = "0";
            }
            String seq = safeText(row.getOrDefault("seq", row.get("sequence")));
            String stopId = safeText(row.get("stop_id"));
            if (lineId.isBlank() || stopId.isBlank()) {
                continue;
            }
            referencedStopIds.add(stopId);
            Map<String, Object> station = stationByStopId.get(stopId);
            Object geometry = null;
            String stopName = safeText(row.get("stop_name"));
            if (station != null) {
                geometry = station.get("geometry");
                String stationName = firstText(featureProperties(station), "stop_name");
                if (!stationName.isBlank()) {
                    stopName = stationName;
                }
            }
            Double lon = doubleValue(row.get("lon"));
            Double lat = doubleValue(row.get("lat"));
            if (geometry == null && lon != null && lat != null) {
                geometry = pointGeometry(lon, lat);
            }
            if (geometry == null) {
                continue;
            }
            if (lon == null || lat == null) {
                Object coordsLon = firstCoordinateOf(geometry, 0);
                Object coordsLat = firstCoordinateOf(geometry, 1);
                lon = coordsLon instanceof Number number ? number.doubleValue() : null;
                lat = coordsLat instanceof Number number ? number.doubleValue() : null;
            }
            String featureId = "rs." + lineId + "." + direction + "." + seq;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "Feature");
            item.put("id", featureId);
            item.put("geometry", geometry);
            Map<String, Object> properties = new LinkedHashMap<>();
            if (station != null) {
                featureProperties(station).forEach((key, value) -> {
                    if (!key.startsWith("_") && !ROUTE_STOP_RELATION_FIELDS.contains(key)) {
                        properties.put(key, value);
                    }
                });
            }
            properties.put("line_id", lineId);
            properties.put("dir", direction);
            properties.put("stop_id", stopId);
            properties.put("stop_name", stopName);
            properties.put("seq", integerOrText(seq));
            properties.put("lon", lon == null ? null : round6(lon));
            properties.put("lat", lat == null ? null : round6(lat));
            properties.put("_featureId", featureId);
            item.put("properties", properties);
            features.add(item);
        }

        // 不在任何线路经停关系中的站台（例如编辑新增的独立站点）需保留，
        // 否则写回后再读取会丢失。
        for (Map.Entry<String, Map<String, Object>> entry : stationByStopId.entrySet()) {
            if (referencedStopIds.contains(entry.getKey())) {
                continue;
            }
            Map<String, Object> station = entry.getValue();
            Map<String, Object> stationProperties = featureProperties(station);
            String featureId = "rs.orphan." + entry.getKey();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "Feature");
            item.put("id", featureId);
            item.put("geometry", station.get("geometry"));
            Map<String, Object> properties = new LinkedHashMap<>();
            stationProperties.forEach((key, value) -> {
                if (!key.startsWith("_") && !ROUTE_STOP_RELATION_FIELDS.contains(key)) {
                    properties.put(key, value);
                }
            });
            properties.put("line_id", "");
            properties.put("dir", "");
            properties.put("stop_id", entry.getKey());
            properties.put("stop_name", firstText(stationProperties, "stop_name"));
            properties.put("seq", "");
            properties.put("lon", stationProperties.get("lon"));
            properties.put("lat", stationProperties.get("lat"));
            properties.put("_featureId", featureId);
            item.put("properties", properties);
            features.add(item);
        }

        refreshFeatureCollectionMetadata(collection);
        collection.put("fileName", uniqueStops.get("fileName"));
        collection.put("sequenceCsv", csvFile.getFileName().toString());
        return collection;
    }

    private Object integerOrText(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return value;
        }
    }

    private Object firstCoordinateOf(Object geometryValue, int index) {
        if (!(geometryValue instanceof Map<?, ?> geometry)) {
            return null;
        }
        Object coordinates = geometry.get("coordinates");
        if (coordinates instanceof List<?> list && list.size() > index) {
            return list.get(index);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> uniqueStationCollectionForWrite(Map<String, Object> routeStops) {
        Map<String, Object> collection = emptyFeatureCollection();
        List<Map<String, Object>> uniqueFeatures = (List<Map<String, Object>>) collection.get("features");
        Map<String, Map<String, Object>> stationByKey = new LinkedHashMap<>();
        Map<String, Set<String>> lineIdsByKey = new LinkedHashMap<>();
        for (Map<String, Object> feature : mutableMapList(routeStops.get("features"))) {
            Map<String, Object> properties = featureProperties(feature);
            String key = firstText(properties, "stop_id");
            if (key.isBlank()) {
                key = firstText(properties, "_featureId", "_stationKey");
            }
            if (key.isBlank()) {
                key = safeText(feature.get("id"));
            }
            if (key.isBlank()) {
                continue;
            }
            String lineId = firstText(properties, "line_id");
            if (!lineId.isBlank()) {
                lineIdsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(lineId);
            }
            if (stationByKey.containsKey(key)) {
                continue;
            }
            Object lon = properties.get("lon");
            Object lat = properties.get("lat");
            if (!(lon instanceof Number) || !(lat instanceof Number)) {
                Object coordsLon = firstCoordinateOf(feature.get("geometry"), 0);
                Object coordsLat = firstCoordinateOf(feature.get("geometry"), 1);
                lon = coordsLon instanceof Number ? coordsLon : lon;
                lat = coordsLat instanceof Number ? coordsLat : lat;
            }
            Map<String, Object> station = new LinkedHashMap<>();
            station.put("type", "Feature");
            station.put("id", key);
            station.put("geometry", feature.get("geometry"));
            Map<String, Object> stationProperties = new LinkedHashMap<>();
            properties.forEach((propertyKey, value) -> {
                if (!propertyKey.startsWith("_") && !ROUTE_STOP_RELATION_FIELDS.contains(propertyKey)) {
                    stationProperties.put(propertyKey, value);
                }
            });
            stationProperties.put("stop_id", key);
            stationProperties.put("stop_name", firstText(properties, "stop_name", "name"));
            stationProperties.put("lon", lon instanceof Number number ? round6(number.doubleValue()) : null);
            stationProperties.put("lat", lat instanceof Number number ? round6(number.doubleValue()) : null);
            stationProperties.put("_featureId", key);
            station.put("properties", stationProperties);
            stationByKey.put(key, station);
            uniqueFeatures.add(station);
        }
        for (Map.Entry<String, Map<String, Object>> entry : stationByKey.entrySet()) {
            Set<String> lineIds = lineIdsByKey.getOrDefault(entry.getKey(), Set.of());
            featureProperties(entry.getValue()).put("route_cnt", lineIds.size());
        }
        refreshFeatureCollectionMetadata(collection);
        LinkedHashSet<String> fields = new LinkedHashSet<>(UNIQUE_STOP_FIELDS);
        uniqueFeatures.forEach(feature -> featureProperties(feature).keySet().stream()
                .filter(key -> !key.startsWith("_"))
                .forEach(fields::add));
        collection.put("attributeFields", List.copyOf(fields));
        return collection;
    }

    private List<Map<String, String>> readSequenceCsv(Path csvFile) {
        try {
            List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
            List<Map<String, String>> rows = new ArrayList<>();
            if (lines.isEmpty()) {
                return rows;
            }
            String headerLine = lines.get(0);
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }
            List<String> header = parseCsvLine(headerLine);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size() && c < cells.size(); c++) {
                    row.put(header.get(c).trim(), cells.get(c));
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException error) {
            throw new BusinessException("读取经停关系 CSV 失败: " + csvFile, error);
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private void writeSequenceCsv(Path csvFile, Map<String, Object> routeStops) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> feature : mutableMapList(routeStops.get("features"))) {
            Map<String, Object> properties = featureProperties(feature);
            String lineId = firstText(properties, "line_id");
            String seq = safeText(properties.get("seq"));
            if (lineId.isBlank() || seq.isBlank()) {
                continue;
            }
            Object lon = properties.get("lon");
            Object lat = properties.get("lat");
            if (!(lon instanceof Number) || !(lat instanceof Number)) {
                Object coordsLon = firstCoordinateOf(feature.get("geometry"), 0);
                Object coordsLat = firstCoordinateOf(feature.get("geometry"), 1);
                lon = coordsLon instanceof Number ? coordsLon : lon;
                lat = coordsLat instanceof Number ? coordsLat : lat;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line_id", lineId);
            row.put("dir", firstText(properties, "dir"));
            row.put("seq", seq);
            row.put("stop_id", firstText(properties, "stop_id", "_featureId"));
            row.put("stop_name", firstText(properties, "stop_name", "name"));
            row.put("lon", lon instanceof Number number ? String.valueOf(round6(number.doubleValue())) : "");
            row.put("lat", lat instanceof Number number ? String.valueOf(round6(number.doubleValue())) : "");
            rows.add(row);
        }
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> safeText(row.get("line_id")))
                .thenComparing(row -> safeText(row.get("dir")))
                .thenComparing(row -> {
                    try {
                        return Integer.parseInt(safeText(row.get("seq")));
                    } catch (NumberFormatException ignored) {
                        return Integer.MAX_VALUE;
                    }
                }));
        StringBuilder builder = new StringBuilder("\uFEFF");
        builder.append("line_id,dir,seq,stop_id,stop_name,lon,lat\n");
        for (Map<String, Object> row : rows) {
            builder
                    .append(csvCell(safeText(row.get("line_id")))).append(',')
                    .append(csvCell(safeText(row.get("dir")))).append(',')
                    .append(csvCell(safeText(row.get("seq")))).append(',')
                    .append(csvCell(safeText(row.get("stop_id")))).append(',')
                    .append(csvCell(safeText(row.get("stop_name")))).append(',')
                    .append(csvCell(safeText(row.get("lon")))).append(',')
                    .append(csvCell(safeText(row.get("lat")))).append('\n');
        }
        try {
            Files.writeString(csvFile, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new BusinessException("写入经停关系 CSV 失败: " + csvFile, error);
        }
    }

    private String csvCell(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Map<String, Object> readShp(File shpFile, List<String> expectedFields, Class<? extends Geometry> expectedGeometryType, String datasetLabel) {
        if (shpFile == null) {
            return emptyFeatureCollection();
        }

        ShapefileDataStore dataStore = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setMemoryMapped(false);
            dataStore.setBufferCachingEnabled(false);
            dataStore.setCharset(shapefileCharset(shpFile));
            String typeName = dataStore.getTypeNames()[0];
            validateStandardSchema(shpFile, dataStore.getSchema(typeName), expectedFields, expectedGeometryType, datasetLabel);
            Map<String, Object> collection = emptyFeatureCollection();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> features = (List<Map<String, Object>>) collection.get("features");

            try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(typeName).getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Object geometryValue = feature.getDefaultGeometry();
                    if (!(geometryValue instanceof Geometry geometry) || geometry.isEmpty()) {
                        continue;
                    }
                    if (!isExpectedGeometry(geometry, expectedGeometryType)) {
                        throw new BusinessException(datasetLabel + "SHP 几何类型不符合标准: " + shpFile.getName()
                                + "；要求 " + expectedGeometryType.getSimpleName()
                                + "；实际 " + geometry.getGeometryType());
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "Feature");
                    item.put("id", feature.getID());
                    item.put("geometry", geometryToGeoJson(geometry));
                    Map<String, Object> properties = propertiesOf(feature);
                    properties.put("_featureId", feature.getID());
                    item.put("properties", properties);
                    features.add(item);
                    expandBounds(collection, geometry);
                }
            }
            collection.put("featureCount", features.size());
            collection.put("fileName", shpFile.getName());
            collection.put("attributeFields", schemaAttributeFields(dataStore.getSchema(typeName)));
            return collection;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("读取真实数据 shp 失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    private File findFirstShp(Path folder) {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(folder)) {
            List<Path> shpFiles = stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".shp"))
                    .filter(path -> !path.getFileName().toString().startsWith("._"))
                    .sorted()
                    .toList();
            if (shpFiles.size() > 1) {
                List<String> fileNames = shpFiles.stream().map(path -> path.getFileName().toString()).toList();
                throw new BusinessException("真实数据目录只能保留一个 SHP: " + folder + "；当前文件 " + fileNames);
            }
            return shpFiles.isEmpty() ? null : shpFiles.get(0).toFile();
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("扫描真实数据目录失败: " + folder, error);
        }
    }

    private Charset shapefileCharset(File shpFile) {
        if (shpFile == null) {
            return StandardCharsets.UTF_8;
        }
        String fileName = shpFile.getName();
        String baseName = fileName.toLowerCase(Locale.ROOT).endsWith(".shp")
                ? fileName.substring(0, fileName.length() - ".shp".length())
                : fileName;
        Path cpgFile = shpFile.toPath().resolveSibling(baseName + ".cpg");
        if (!Files.isRegularFile(cpgFile)) {
            return StandardCharsets.UTF_8;
        }
        try {
            String cpg = Files.readString(cpgFile, StandardCharsets.UTF_8).trim();
            if (cpg.startsWith("\uFEFF")) {
                cpg = cpg.substring(1).trim();
            }
            cpg = cpg.replace("\"", "").replace("'", "").trim();
            if (cpg.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            return Charset.forName(normalizeCpgCharset(cpg));
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private String normalizeCpgCharset(String cpg) {
        return switch (cpg.toUpperCase(Locale.ROOT)) {
            case "65001", "UTF8" -> "UTF-8";
            case "936", "CP936" -> "GBK";
            case "54936", "CP54936" -> "GB18030";
            default -> cpg;
        };
    }

    private Object firstCoordinateValue(Map<String, Object> feature, int index) {
        Object geometryValue = feature.get("geometry");
        if (!(geometryValue instanceof Map<?, ?> rawGeometry)) {
            return "";
        }
        Object coordinatesValue = rawGeometry.get("coordinates");
        if (!(coordinatesValue instanceof List<?> coordinates) || coordinates.size() <= index) {
            return "";
        }
        return coordinates.get(index);
    }

    private void stripDerivedProperties(Map<String, Object> collection) {
        for (Map<String, Object> feature : mutableMapList(collection.get("features"))) {
            featureProperties(feature).keySet().removeIf(DERIVED_FIELDS::contains);
        }
    }

    private void validateStandardSchema(File shpFile, SimpleFeatureType schema, List<String> expectedFields, Class<? extends Geometry> expectedGeometryType, String datasetLabel) {
        if (expectedFields == null || expectedFields.isEmpty()) {
            return;
        }
        List<String> actualFields = schema.getAttributeDescriptors().stream()
                .filter(descriptor -> schema.getGeometryDescriptor() == null || !descriptor.getName().equals(schema.getGeometryDescriptor().getName()))
                .map(descriptor -> descriptor.getName().getLocalPart())
                .toList();
        // 仅要求包含全部标准字段（可以有多余字段，不能缺少），不限制字段顺序。
        List<String> missing = expectedFields.stream().filter(field -> !actualFields.contains(field)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(datasetLabel + "SHP 字段不完整: " + shpFile.getName()
                    + "；缺少标准字段 " + missing
                    + "；标准字段为 " + expectedFields
                    + "（可包含额外字段，但不能缺少标准字段）");
        }
    }

    private boolean isExpectedGeometry(Geometry geometry, Class<? extends Geometry> expectedGeometryType) {
        if (expectedGeometryType == null) {
            return true;
        }
        if (LineString.class.equals(expectedGeometryType)) {
            return geometry instanceof LineString || geometry instanceof MultiLineString;
        }
        return expectedGeometryType.isInstance(geometry);
    }

    private void enrichDerivedAttributes(Map<String, Object> lines, Map<String, Object> routeStops) {
        List<Map<String, Object>> stopFeatures = mutableMapList(routeStops.get("features"));
        Map<String, Set<String>> routeIdsByStation = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> stopsByLineId = new LinkedHashMap<>();
        for (Map<String, Object> stopFeature : stopFeatures) {
            Map<String, Object> stopProperties = featureProperties(stopFeature);
            String stationKey = stationPhysicalKey(stopFeature);
            String lineId = firstText(stopProperties, "line_id", "route_id");
            if (!stationKey.isBlank() && !lineId.isBlank()) {
                routeIdsByStation.computeIfAbsent(stationKey, ignored -> new LinkedHashSet<>()).add(lineId);
            }
            if (!lineId.isBlank()) {
                stopsByLineId.computeIfAbsent(lineId, ignored -> new ArrayList<>()).add(stopFeature);
            }
        }
        for (Map<String, Object> stopFeature : stopFeatures) {
            String stationKey = stationPhysicalKey(stopFeature);
            featureProperties(stopFeature).put("route_cnt", routeIdsByStation.getOrDefault(stationKey, Set.of()).size());
        }

        for (Map<String, Object> lineFeature : mutableMapList(lines.get("features"))) {
            Map<String, Object> lineProperties = featureProperties(lineFeature);
            String lineId = firstText(lineProperties, "line_id", "route_id");
            List<Map<String, Object>> matchedStops = stopsByLineId.getOrDefault(lineId, List.of()).stream()
                    .filter(stop -> routeStopDirectionMatches(featureProperties(stop), lineProperties))
                    .sorted(Comparator.comparingInt(stop -> sequenceValue(featureProperties(stop))))
                    .toList();
            Geometry geometry = geometryFromGeoJson(lineFeature.get("geometry"));
            double lengthMeters = geometry == null ? 0 : geometryLengthMeters(geometry);
            double straightMeters = routeStraightDistanceMeters(matchedStops, geometry);
            lineProperties.put("len_km", lengthMeters > 0 ? round4(lengthMeters / 1000.0) : null);
            lineProperties.put("directness", lengthMeters > 0 && straightMeters > 0
                    ? round4(lengthMeters / straightMeters)
                    : null);
            lineProperties.put("stop_count", matchedStops.size());
            lineProperties.put("avg_stop_m", matchedStops.size() > 1 && lengthMeters > 0
                    ? round2(lengthMeters / (matchedStops.size() - 1))
                    : null);
        }
    }

    private boolean routeStopDirectionMatches(Map<String, Object> stopProperties, Map<String, Object> lineProperties) {
        String direction = firstText(lineProperties, "dir");
        String stopDirection = firstText(stopProperties, "dir");
        return direction.isBlank() || stopDirection.isBlank() || direction.equals(stopDirection);
    }

    private int sequenceValue(Map<String, Object> properties) {
        try {
            return Integer.parseInt(safeText(properties.get("seq")));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private double routeStraightDistanceMeters(List<Map<String, Object>> stops, Geometry lineGeometry) {
        if (stops.size() > 1) {
            Coordinate first = firstPointCoordinate(stops.get(0));
            Coordinate last = firstPointCoordinate(stops.get(stops.size() - 1));
            if (first != null && last != null) {
                double distance = distanceMeters(first, last);
                if (distance > 0) return distance;
            }
        }
        if (lineGeometry != null) {
            Coordinate[] coordinates = lineGeometry.getCoordinates();
            if (coordinates.length > 1) {
                return distanceMeters(coordinates[0], coordinates[coordinates.length - 1]);
            }
        }
        return 0;
    }

    private Coordinate firstPointCoordinate(Map<String, Object> feature) {
        Object lon = firstCoordinateOf(feature.get("geometry"), 0);
        Object lat = firstCoordinateOf(feature.get("geometry"), 1);
        if (lon instanceof Number lng && lat instanceof Number latitude) {
            return new Coordinate(lng.doubleValue(), latitude.doubleValue());
        }
        return null;
    }

    private String stationPhysicalKey(Map<String, Object> feature) {
        Map<String, Object> properties = featureProperties(feature);
        String key = firstText(properties, "stop_id", "_stationKey");
        if (!key.isBlank()) return key;
        return firstText(properties, "stop_name") + "|" + safeText(feature.get("geometry"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> uniqueStationsFromRouteStops(Map<String, Object> routeStops) {
        Map<String, Object> collection = emptyFeatureCollection();
        Object rawFeatures = routeStops.get("features");
        if (!(rawFeatures instanceof List<?> features)) {
            return collection;
        }
        List<Map<String, Object>> uniqueFeatures = (List<Map<String, Object>>) collection.get("features");
        Map<String, Map<String, Object>> stationByKey = new LinkedHashMap<>();
        for (Object item : features) {
            if (!(item instanceof Map<?, ?> rawFeature)) {
                continue;
            }
            Map<String, Object> feature = stringObjectMap(rawFeature);
            Map<String, Object> properties = featureProperties(feature);
            String key = firstText(properties, "stop_id");
            if (key.isBlank()) {
                key = firstText(properties, "stop_name") + "|" + safeText(feature.get("geometry"));
            }
            if (key.isBlank()) {
                continue;
            }
            if (stationByKey.containsKey(key)) {
                continue;
            }
            Map<String, Object> station = new LinkedHashMap<>();
            station.put("type", "Feature");
            station.put("id", key);
            station.put("geometry", feature.get("geometry"));
            Map<String, Object> stationProperties = new LinkedHashMap<>();
            stationProperties.put("_featureId", key);
            properties.forEach((propertyKey, value) -> {
                if (!propertyKey.startsWith("_") && !ROUTE_STOP_RELATION_FIELDS.contains(propertyKey)) {
                    stationProperties.put(propertyKey, value);
                }
            });
            stationProperties.put("stop_id", firstText(properties, "stop_id"));
            stationProperties.put("stop_name", firstText(properties, "stop_name", "name"));
            Object lon = properties.get("lon");
            Object lat = properties.get("lat");
            stationProperties.put("lon", lon == null ? firstCoordinateOf(feature.get("geometry"), 0) : lon);
            stationProperties.put("lat", lat == null ? firstCoordinateOf(feature.get("geometry"), 1) : lat);
            station.put("properties", stationProperties);
            stationByKey.put(key, station);
            uniqueFeatures.add(station);
        }
        refreshFeatureCollectionMetadata(collection);
        collection.put("sourceFileName", routeStops.get("fileName"));
        LinkedHashSet<String> fields = new LinkedHashSet<>(UNIQUE_STOP_FIELDS);
        uniqueFeatures.forEach(feature -> featureProperties(feature).keySet().stream()
                .filter(key -> !key.startsWith("_"))
                .filter(key -> !ROUTE_STOP_RELATION_FIELDS.contains(key))
                .forEach(fields::add));
        collection.put("attributeFields", List.copyOf(fields));
        return collection;
    }

    private String overviewSignature(Path... folders) {
        StringBuilder builder = new StringBuilder();
        for (Path folder : folders) {
            Path normalized = folder.toAbsolutePath().normalize();
            builder.append(normalized).append("=");
            if (Files.isRegularFile(normalized)) {
                appendFileSignature(builder, normalized);
                builder.append(";");
                continue;
            }
            if (!Files.isDirectory(normalized)) {
                builder.append("missing;");
                continue;
            }
            try (Stream<Path> stream = Files.walk(normalized, 1)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().startsWith("._"))
                        .sorted()
                        .forEach(path -> appendFileSignature(builder, path));
                builder.append(";");
            } catch (IOException error) {
                throw new BusinessException("读取真实数据文件信息失败: " + normalized, error);
            }
        }
        return builder.toString();
    }

    private String realDataSignature(Path root, Path dataRoot) {
        return overviewSignature(
                dataRoot.resolve(BUS_LINE_FOLDER),
                dataRoot.resolve(BUS_STATION_FOLDER),
                dataRoot.resolve(BUS_DEPOT_FOLDER),
                root.resolve(ADMIN_AREA_FOLDER),
                stateFile(root)
        );
    }

    private String realDataCacheKey(String areaName, String versionId) {
        return safeText(areaName) + "::" + (safeText(versionId).isBlank() ? "__base__" : safeText(versionId));
    }

    private void invalidateAreaRealDataCaches(String areaName) {
        overviewCache.remove(areaName);
        String prefix = safeText(areaName) + "::";
        realDataCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void trimRealDataCache() {
        int overflow = realDataCache.size() - MAX_REAL_DATA_CACHE_ENTRIES;
        if (overflow <= 0) return;
        realDataCache.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().createdAt()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .forEach(realDataCache::remove);
    }

    private void appendFileSignature(StringBuilder builder, Path path) {
        try {
            builder
                    .append(path.getFileName())
                    .append(":")
                    .append(Files.size(path))
                    .append(":")
                    .append(Files.getLastModifiedTime(path).toMillis())
                    .append("|");
        } catch (IOException error) {
            throw new BusinessException("读取真实数据文件信息失败: " + path, error);
        }
    }

    private double readTotalLengthMeters(Path folder) {
        return readGeometryMetric(folder, this::geometryLengthMeters);
    }

    private double readTotalAreaSquareMeters(Path folder) {
        return readGeometryMetric(folder, this::geometryAreaSquareMeters);
    }

    private double readGeometryMetric(Path folder, GeometryMetric metric) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            return 0;
        }

        ShapefileDataStore dataStore = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setCharset(shapefileCharset(shpFile));
            String typeName = dataStore.getTypeNames()[0];
            double total = 0;
            try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(typeName).getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Object geometryValue = feature.getDefaultGeometry();
                    if (geometryValue instanceof Geometry geometry && !geometry.isEmpty()) {
                        total += metric.value(geometry);
                    }
                }
            }
            return total;
        } catch (Exception error) {
            throw new BusinessException("统计真实数据 shp 失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    private AdminArea readAdminArea(Path folder) {
        List<Geometry> geometries = readGeometries(folder);
        if (geometries.isEmpty()) {
            return new AdminArea(0, GEOMETRY_FACTORY.createGeometryCollection(), new LocalProjection(0, 0));
        }

        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (Geometry geometry : geometries) {
            var envelope = geometry.getEnvelopeInternal();
            minLon = Math.min(minLon, envelope.getMinX());
            minLat = Math.min(minLat, envelope.getMinY());
            maxLon = Math.max(maxLon, envelope.getMaxX());
            maxLat = Math.max(maxLat, envelope.getMaxY());
        }

        LocalProjection projection = new LocalProjection((minLon + maxLon) / 2.0, (minLat + maxLat) / 2.0);
        List<Geometry> projected = geometries.stream()
                .map(geometry -> projectedCopy(geometry, projection))
                .toList();
        Geometry union = UnaryUnionOp.union(projected);
        Geometry adminGeometry = union == null ? GEOMETRY_FACTORY.createGeometryCollection() : union.buffer(0);
        return new AdminArea(adminGeometry.getArea() / 1_000_000.0, adminGeometry, projection);
    }

    private CoverageStats readCoverageStats(Path stationFolder, AdminArea adminArea) {
        if (adminArea.areaKm2() <= 0 || adminArea.geometry().isEmpty()) {
            return new CoverageStats(0, 0);
        }
        List<Coordinate> stationCoordinates = readProjectedCoordinates(stationFolder, adminArea.projection());
        if (stationCoordinates.isEmpty()) {
            return new CoverageStats(0, 0);
        }
        Geometry stations = GEOMETRY_FACTORY.createMultiPointFromCoords(stationCoordinates.toArray(Coordinate[]::new));
        double coverage300Km2 = clippedBufferAreaKm2(stations, COVERAGE_300_METERS, adminArea.geometry());
        double coverage500Km2 = clippedBufferAreaKm2(stations, COVERAGE_500_METERS, adminArea.geometry());
        return new CoverageStats(coverage300Km2, coverage500Km2);
    }

    private CoverageStats coverageStatsFromStationCollection(Map<String, Object> stationCollection, AdminArea adminArea) {
        if (adminArea.areaKm2() <= 0 || adminArea.geometry().isEmpty()) {
            return new CoverageStats(0, 0);
        }
        Object featuresValue = stationCollection.get("features");
        if (!(featuresValue instanceof List<?> features) || features.isEmpty()) {
            return new CoverageStats(0, 0);
        }
        List<Coordinate> stationCoordinates = new ArrayList<>();
        for (Object item : features) {
            if (!(item instanceof Map<?, ?> feature)) {
                continue;
            }
            Object geometryValue = feature.get("geometry");
            if (!(geometryValue instanceof Map<?, ?> geometry)) {
                continue;
            }
            collectProjectedPointCoordinates(geometry.get("coordinates"), adminArea.projection(), stationCoordinates);
        }
        if (stationCoordinates.isEmpty()) {
            return new CoverageStats(0, 0);
        }
        Geometry stations = GEOMETRY_FACTORY.createMultiPointFromCoords(stationCoordinates.toArray(Coordinate[]::new));
        double coverage300Km2 = clippedBufferAreaKm2(stations, COVERAGE_300_METERS, adminArea.geometry());
        double coverage500Km2 = clippedBufferAreaKm2(stations, COVERAGE_500_METERS, adminArea.geometry());
        return new CoverageStats(coverage300Km2, coverage500Km2);
    }

    private void collectProjectedPointCoordinates(Object coordinatesValue, LocalProjection projection, List<Coordinate> output) {
        if (!(coordinatesValue instanceof List<?> coordinates) || coordinates.size() < 2) {
            return;
        }
        Object lngValue = coordinates.get(0);
        Object latValue = coordinates.get(1);
        if (lngValue instanceof Number lng && latValue instanceof Number lat) {
            output.add(projection.project(new Coordinate(lng.doubleValue(), lat.doubleValue())));
        }
    }

    private double clippedBufferAreaKm2(Geometry stations, double radiusMeters, Geometry adminGeometry) {
        Geometry coverage = stations.buffer(radiusMeters, 8);
        Geometry clippedCoverage = coverage.intersection(adminGeometry);
        return clippedCoverage.getArea() / 1_000_000.0;
    }

    private List<Coordinate> readProjectedCoordinates(Path folder, LocalProjection projection) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            return List.of();
        }

        ShapefileDataStore dataStore = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setCharset(shapefileCharset(shpFile));
            String typeName = dataStore.getTypeNames()[0];
            List<Coordinate> coordinates = new ArrayList<>();
            try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(typeName).getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Object geometryValue = feature.getDefaultGeometry();
                    if (!(geometryValue instanceof Geometry geometry) || geometry.isEmpty()) {
                        continue;
                    }
                    for (Coordinate coordinate : geometry.getCoordinates()) {
                        coordinates.add(projection.project(coordinate));
                    }
                }
            }
            return coordinates;
        } catch (Exception error) {
            throw new BusinessException("读取站点覆盖范围失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    private List<Geometry> readGeometries(Path folder) {
        File shpFile = findFirstShp(folder);
        if (shpFile == null) {
            return List.of();
        }

        ShapefileDataStore dataStore = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setCharset(shapefileCharset(shpFile));
            String typeName = dataStore.getTypeNames()[0];
            List<Geometry> geometries = new ArrayList<>();
            try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(typeName).getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Object geometryValue = feature.getDefaultGeometry();
                    if (geometryValue instanceof Geometry geometry && !geometry.isEmpty()) {
                        geometries.add(geometry.copy());
                    }
                }
            }
            return geometries;
        } catch (Exception error) {
            throw new BusinessException("读取行政区范围失败: " + shpFile.getAbsolutePath(), error);
        } finally {
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    private Geometry projectedCopy(Geometry geometry, LocalProjection projection) {
        Geometry projected = geometry.copy();
        projected.apply((CoordinateFilter) coordinate -> {
            Coordinate meterCoordinate = projection.project(coordinate);
            coordinate.x = meterCoordinate.x;
            coordinate.y = meterCoordinate.y;
        });
        projected.geometryChanged();
        return projected;
    }

    private double geometryLengthMeters(Geometry geometry) {
        String type = geometry.getGeometryType();
        if ("LineString".equals(type) || "LinearRing".equals(type)) {
            return coordinateLengthMeters(geometry.getCoordinates());
        }
        double total = 0;
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry child = geometry.getGeometryN(i);
            if (child != geometry) {
                total += geometryLengthMeters(child);
            }
        }
        return total;
    }

    private double coordinateLengthMeters(Coordinate[] coordinates) {
        double total = 0;
        for (int i = 1; i < coordinates.length; i++) {
            total += distanceMeters(coordinates[i - 1], coordinates[i]);
        }
        return total;
    }

    private double distanceMeters(Coordinate first, Coordinate second) {
        double lat1 = Math.toRadians(first.y);
        double lat2 = Math.toRadians(second.y);
        double deltaLat = Math.toRadians(second.y - first.y);
        double deltaLon = Math.toRadians(second.x - first.x);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double geometryAreaSquareMeters(Geometry geometry) {
        if (geometry instanceof Polygon polygon) {
            double area = ringAreaSquareMeters(polygon.getExteriorRing().getCoordinates());
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                area -= ringAreaSquareMeters(polygon.getInteriorRingN(i).getCoordinates());
            }
            return Math.max(area, 0);
        }
        double total = 0;
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry child = geometry.getGeometryN(i);
            if (child != geometry) {
                total += geometryAreaSquareMeters(child);
            }
        }
        return total;
    }

    private double ringAreaSquareMeters(Coordinate[] coordinates) {
        if (coordinates.length < 4) {
            return 0;
        }
        double refLon = 0;
        double refLat = 0;
        for (Coordinate coordinate : coordinates) {
            refLon += coordinate.x;
            refLat += coordinate.y;
        }
        refLon /= coordinates.length;
        refLat /= coordinates.length;

        double area = 0;
        double cosRefLat = Math.cos(Math.toRadians(refLat));
        for (int i = 0; i < coordinates.length; i++) {
            Coordinate current = coordinates[i];
            Coordinate next = coordinates[(i + 1) % coordinates.length];
            double x1 = EARTH_RADIUS_METERS * Math.toRadians(current.x - refLon) * cosRefLat;
            double y1 = EARTH_RADIUS_METERS * Math.toRadians(current.y - refLat);
            double x2 = EARTH_RADIUS_METERS * Math.toRadians(next.x - refLon) * cosRefLat;
            double y2 = EARTH_RADIUS_METERS * Math.toRadians(next.y - refLat);
            area += x1 * y2 - x2 * y1;
        }
        return Math.abs(area) / 2.0;
    }

    private Map<String, Object> emptyFeatureCollection() {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", new ArrayList<>());
        collection.put("bounds", null);
        collection.put("featureCount", 0);
        return collection;
    }

    private Map<String, Object> propertiesOf(SimpleFeature feature) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Property property : feature.getProperties()) {
            if (property.getName().equals(feature.getDefaultGeometryProperty().getName())) {
                continue;
            }
            Object value = property.getValue();
            properties.put(property.getName().toString(), value == null ? null : value.toString());
        }
        return properties;
    }

    private Map<String, Object> geometryToGeoJson(Geometry geometry) {
        Map<String, Object> result = new LinkedHashMap<>();
        String type = geometry.getGeometryType();
        result.put("type", type);
        switch (type) {
            case "Point" -> result.put("coordinates", coordinate(geometry.getCoordinate()));
            case "MultiPoint", "LineString" -> result.put("coordinates", coordinates(geometry.getCoordinates()));
            case "MultiLineString", "Polygon" -> result.put("coordinates", nestedCoordinates(geometry));
            case "MultiPolygon" -> result.put("coordinates", multiPolygonCoordinates(geometry));
            default -> result.put("coordinates", List.of());
        }
        return result;
    }

    private List<Object> coordinate(Coordinate coordinate) {
        return List.of(round6(coordinate.x), round6(coordinate.y));
    }

    private List<Object> coordinates(Coordinate[] coordinates) {
        List<Object> list = new ArrayList<>(coordinates.length);
        for (Coordinate coordinate : coordinates) {
            list.add(coordinate(coordinate));
        }
        return list;
    }

    private List<Object> nestedCoordinates(Geometry geometry) {
        List<Object> list = new ArrayList<>(geometry.getNumGeometries());
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            list.add(coordinates(geometry.getGeometryN(i).getCoordinates()));
        }
        return list;
    }

    private List<Object> multiPolygonCoordinates(Geometry geometry) {
        List<Object> list = new ArrayList<>(geometry.getNumGeometries());
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            list.add(nestedCoordinates(geometry.getGeometryN(i)));
        }
        return list;
    }

    private void expandBounds(Map<String, Object> collection, Geometry geometry) {
        Object value = collection.get("bounds");
        double[] bounds = value instanceof double[] current ? current : new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Coordinate coordinate : geometry.getCoordinates()) {
            bounds[0] = Math.min(bounds[0], coordinate.x);
            bounds[1] = Math.min(bounds[1], coordinate.y);
            bounds[2] = Math.max(bounds[2], coordinate.x);
            bounds[3] = Math.max(bounds[3], coordinate.y);
        }
        collection.put("bounds", bounds);
    }

    private double[] boundsOf(Map<String, Object> collection) {
        Object bounds = collection.get("bounds");
        return bounds instanceof double[] values ? values : null;
    }

    private double[] mergeBounds(double[] first, double[] second) {
        if (first == null) return second;
        if (second == null) return first;
        return new double[]{
                Math.min(first[0], second[0]),
                Math.min(first[1], second[1]),
                Math.max(first[2], second[2]),
                Math.max(first[3], second[3])
        };
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Number numberValue(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(safeText(value));
    }

    @FunctionalInterface
    private interface GeometryMetric {
        double value(Geometry geometry);
    }

    private record AdminArea(double areaKm2, Geometry geometry, LocalProjection projection) {
    }

    private record CoverageStats(double coverage300Km2, double coverage500Km2) {
    }

    private record CachedOverview(String signature, Map<String, Object> overview) {
    }

    private record CachedRealData(String signature, Map<String, Object> data, long createdAt) {
    }

    private record VersionCollections(
            Path dataRoot,
            Map<String, Object> lines,
            Map<String, Object> routeStops,
            Map<String, Object> depots
    ) {
    }

    private record ExportDataset(
            String datasetType,
            Map<String, Object> collection,
            List<String> fields
    ) {
    }

    private record DiffFeature(
            String matchKey,
            String targetId,
            Map<String, Object> feature
    ) {
    }

    private record ManualProtection(Set<String> fields, boolean manuallyDeleted) {
    }

    private record MergeFeatureResult(
            Map<String, Object> feature,
            List<String> changedFields,
            List<String> protectedFields,
            boolean hasManualProtection
    ) {
    }

    private record UploadDiffResult(
            List<Map<String, Object>> operations,
            int candidateDeletionCount,
            int protectedFeatureCount,
            int protectedFieldCount,
            int skippedByManualDeletionCount
    ) {
    }

    private record PendingShpComparison(
            String username,
            String areaName,
            long revision,
            String versionId,
            Map<String, String> canonicalOperations
    ) {
    }

    private record TargetVersion(String id, String dataVersionId, String label, List<String> operationIds, List<Map<String, Object>> operations, boolean materializedData) {
    }

    private record LocalProjection(double refLon, double refLat) {
        Coordinate project(Coordinate coordinate) {
            double x = EARTH_RADIUS_METERS * Math.toRadians(coordinate.x - refLon) * Math.cos(Math.toRadians(refLat));
            double y = EARTH_RADIUS_METERS * Math.toRadians(coordinate.y - refLat);
            return new Coordinate(x, y);
        }
    }
}
