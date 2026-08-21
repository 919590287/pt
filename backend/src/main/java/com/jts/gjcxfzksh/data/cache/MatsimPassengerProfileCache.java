package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.pt.routes.TransitPassengerRoute;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 超大模型客流画像的流式派生缓存。
 *
 * <p>大模型不将 plans 对象化到堆中。本缓存在 {@link MatsimPlansDerivedCache}
 * 的单次 plans 扫描中，按 person 局部去重后直接聚合到 route / lineGroup /
 * station，全程不保留 Person，内存与线路、站点数量相关，而与人口规模无关。</p>
 *
 * <p>画像口径：只读取明确的 selected plan 中实际的
 * {@link TransitPassengerRoute}。同一人在同一线路/线路组/站点只计一次；
 * 出行目的活动仍按公交 leg 计次，与小模型既有面板口径一致。</p>
 */
@Slf4j
public final class MatsimPassengerProfileCache {

    // v2: 缺 plans 时落显式 unsupported，而不是 ready 的空画像。
    public static final String PROFILE_CACHE_VERSION = "passenger-profile-v3";
    private static final String PROFILE_FILE = "profiles.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int IO_BUFFER_BYTES = 1 << 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final BackendMemoryCache<String, Map<String, Object>> MEMORY_CACHE =
            new BackendMemoryCache<>("passenger-profile-json", 48L * 1024 * 1024, BackendMemoryCache::estimate);

    private MatsimPassengerProfileCache() {
    }

    /** 小模型继续使用面板内嵌 population 画像，不额外生成工件。 */
    public static boolean isReady(MatsimData data) {
        if (data == null || !data.isLargeModel()) {
            return true;
        }
        if (!Files.isRegularFile(manifestPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            if (!PROFILE_CACHE_VERSION.equals(manifest.get("cacheVersion")) || !sameSources(data, manifest)) {
                return false;
            }
            if ("unsupported".equals(manifest.get("status"))) return true;
            return "ready".equals(manifest.get("status")) && Files.isRegularFile(profilePath(data));
        } catch (Exception e) {
            throw new IllegalStateException("客流画像缓存状态读取失败: " + manifestPath(data), e);
        }
    }

    static Context buildContext(MatsimData data) {
        Map<String, String> stationByFacility = new HashMap<>();
        if (data.getScenario() != null && data.getSchedule() != null) {
            data.getSchedule().getFacilities().forEach((id, facility) -> {
                String facilityId = id.toString();
                stationByFacility.put(facilityId, nonBlank(facility.getName(), facilityId));
            });
        }

        Map<String, Set<String>> groupsByRoute = new HashMap<>();
        if (MatsimRoutePanelCache.isReady(data)) {
            Object groupsValue = MatsimRoutePanelCache.readRoutePanelIndex(data).get("lineGroups");
            if (groupsValue instanceof Map<?, ?> groups) {
                for (Map.Entry<?, ?> entry : groups.entrySet()) {
                    String groupKey = String.valueOf(entry.getKey());
                    if (!(entry.getValue() instanceof Map<?, ?> group)) {
                        continue;
                    }
                    Object routeKeysValue = group.get("routeKeys");
                    if (!(routeKeysValue instanceof Iterable<?> routeKeys)) {
                        continue;
                    }
                    for (Object routeKey : routeKeys) {
                        groupsByRoute.computeIfAbsent(String.valueOf(routeKey), ignored -> new LinkedHashSet<>())
                                .add(groupKey);
                    }
                }
            }
        }
        return new Context(Map.copyOf(stationByFacility), immutableSetMap(groupsByRoute));
    }

    private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    static Aggregation newAggregation(Context context) {
        return new Aggregation(context);
    }

    static void storeBuiltAggregation(MatsimData data, Aggregation aggregation, long startedAt) {
        if (!data.isLargeModel()) {
            return;
        }
        try {
            Map<String, Object> payload = aggregation.toPayload();
            MatsimCachePaths.recreateVersionDir(data, PROFILE_CACHE_VERSION);
            writeGzipJsonAtomic(profilePath(data), payload);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MatsimCachePaths.deleteOtherVersions(data, "passenger-profile-v", PROFILE_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(data));
            log.info("超大模型客流画像生成完成: model={}, routes={}, groups={}, stations={}, elapsedMs={}",
                    data.getName(), aggregation.routes.size(), aggregation.lineGroups.size(),
                    aggregation.stations.size(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            writeFailedManifest(data);
            throw new RuntimeException("超大模型客流画像生成失败: " + e.getMessage(), e);
        }
    }

    static void writeFailedManifest(MatsimData data) {
        if (data == null || !data.isLargeModel()) {
            return;
        }
        try {
            Files.createDirectories(cacheDir(data));
            writeJsonAtomic(manifestPath(data), manifest(data, false));
        } catch (Exception ignored) {
        }
    }

    static void writeUnsupportedManifest(MatsimData data, String message) {
        if (data == null || !data.isLargeModel()) return;
        try {
            MatsimCachePaths.recreateVersionDir(data, PROFILE_CACHE_VERSION);
            writeJsonAtomic(manifestPath(data), manifest(data, "unsupported", message));
            MatsimCachePaths.deleteOtherVersions(data, "passenger-profile-v", PROFILE_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(data));
        } catch (Exception e) {
            throw new RuntimeException("写入客流画像 unsupported 状态失败", e);
        }
    }

    public static Map<String, Object> applyRouteProfile(MatsimData data, Map<String, Object> detail) {
        if (data == null || !data.isLargeModel() || detail == null || detail.isEmpty()
                || !isReady(data) || isUnsupported(data)) {
            return detail;
        }
        String section = Boolean.TRUE.equals(detail.get("lineGroup")) ? "lineGroups" : "routes";
        String key = text(detail.get("routeKey"));
        if (key.isBlank()) {
            key = routeKey(text(detail.get("lineId")), text(detail.get("routeId")));
        }
        Map<String, Object> demographics = profileFor(data, section, key);
        return withDemographics(detail, demographics);
    }

    /**
     * 大模型 plans 不包含实际执行的 departureId，无法在不重新物化 events 的前提下
     * 伪造单班次人口属性。因此班次面板复用所属 route 的 selected-plan 画像并明确
     * 标注统计范围；小模型继续保留按实际班次 riderIds 生成的精确画像。
     */
    public static Map<String, Object> applyDepartureProfile(MatsimData data, Map<String, Object> detail) {
        if (data == null || !data.isLargeModel() || detail == null || detail.isEmpty()
                || !isReady(data) || isUnsupported(data)) {
            return detail;
        }
        String key = routeKey(text(detail.get("lineId")), text(detail.get("routeId")));
        Map<String, Object> routeProfile = profileFor(data, "routes", key);
        if (routeProfile.isEmpty()) return detail;
        Map<String, Object> scopedProfile = new LinkedHashMap<>(routeProfile);
        scopedProfile.put("profileScope", "route");
        scopedProfile.put("profileScopeLabel", "所属线路");
        return withDemographics(detail, scopedProfile);
    }

    /** 班次时刻表整包只附一份 route 画像，避免为每个 departure 重复序列化相同数据。 */
    public static Map<String, Object> applyDepartureBundleProfile(MatsimData data, Map<String, Object> bundle) {
        if (data == null || !data.isLargeModel() || bundle == null || bundle.isEmpty()
                || !isReady(data) || isUnsupported(data)) {
            return bundle;
        }
        String key = routeKey(text(bundle.get("lineId")), text(bundle.get("routeId")));
        Map<String, Object> routeProfile = profileFor(data, "routes", key);
        if (routeProfile.isEmpty()) return bundle;
        Map<String, Object> scopedProfile = new LinkedHashMap<>(routeProfile);
        scopedProfile.put("profileScope", "route");
        scopedProfile.put("profileScopeLabel", "所属线路");
        Map<String, Object> enriched = new LinkedHashMap<>(bundle);
        enriched.put("demographics", scopedProfile);
        return enriched;
    }

    public static Map<String, Object> applyStationProfile(MatsimData data, Map<String, Object> detail) {
        if (data == null || !data.isLargeModel() || detail == null || detail.isEmpty()
                || !isReady(data) || isUnsupported(data)) {
            return detail;
        }
        Map<String, Object> demographics = profileFor(data, "stations", text(detail.get("stationName")));
        return withDemographics(detail, demographics);
    }

    private static Map<String, Object> withDemographics(
            Map<String, Object> detail, Map<String, Object> demographics) {
        if (demographics.isEmpty()) {
            return detail;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(detail);
        enriched.put("demographics", demographics);
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profileFor(MatsimData data, String section, String key) {
        if (key == null || key.isBlank()) {
            return Map.of();
        }
        Object sectionValue = loadProfiles(data).get(section);
        if (!(sectionValue instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Object profile = values.get(key);
        return profile instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, Object> loadProfiles(MatsimData data) {
        String key = cacheKey(data);
        Map<String, Object> cached = MEMORY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (MEMORY_CACHE) {
            cached = MEMORY_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            try (InputStream raw = new BufferedInputStream(Files.newInputStream(profilePath(data)), IO_BUFFER_BYTES);
                 InputStream gzip = new GZIPInputStream(raw, IO_BUFFER_BYTES)) {
                cached = JSON.readValue(gzip, MAP_TYPE);
                MEMORY_CACHE.put(key, cached);
                return cached;
            } catch (Exception e) {
                throw new RuntimeException("读取超大模型客流画像失败: " + e.getMessage(), e);
            }
        }
    }

    record Context(Map<String, String> stationByFacility,
                   Map<String, Set<String>> groupsByRoute) {
        String station(String facilityId) {
            if (facilityId == null || facilityId.isBlank()) {
                return "";
            }
            return stationByFacility.getOrDefault(facilityId, facilityId);
        }

        Set<String> groups(String routeKey) {
            return groupsByRoute.getOrDefault(routeKey, Set.of());
        }
    }

    static final class Aggregation {
        private final Context context;
        private final Map<String, Counts> routes = new HashMap<>();
        private final Map<String, Counts> lineGroups = new HashMap<>();
        private final Map<String, Counts> stations = new HashMap<>();
        private long persons;
        private long transitRiders;

        private Aggregation(Context context) {
            this.context = context;
        }

        void acceptPerson(Person person) {
            persons++;
            Plan plan = person == null ? null : person.getSelectedPlan();
            if (plan == null) {
                throw new IllegalStateException("乘客画像数据缺少 selectedPlan: person="
                        + (person == null ? "null" : person.getId()));
            }

            List<PlanElement> elements = plan.getPlanElements();
            Set<String> routeKeys = new LinkedHashSet<>();
            Set<String> groupKeys = new LinkedHashSet<>();
            Set<String> stationNames = new LinkedHashSet<>();
            List<PurposeUse> purposes = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof Leg leg)
                        || !(leg.getRoute() instanceof TransitPassengerRoute route)) {
                    continue;
                }
                String lineId = route.getLineId() == null ? "" : route.getLineId().toString();
                String transitRouteId = route.getRouteId() == null ? "" : route.getRouteId().toString();
                if (transitRouteId.isBlank()) {
                    continue;
                }
                String routeKey = routeKey(lineId, transitRouteId);
                routeKeys.add(routeKey);
                Set<String> groups = context.groups(routeKey);
                groupKeys.addAll(groups);

                String accessStation = context.station(route.getAccessStopId() == null
                        ? "" : route.getAccessStopId().toString());
                String egressStation = context.station(route.getEgressStopId() == null
                        ? "" : route.getEgressStopId().toString());
                if (!accessStation.isBlank()) stationNames.add(accessStation);
                if (!egressStation.isBlank()) stationNames.add(egressStation);

                String purpose = nextTripPurpose(elements, i);
                if (purpose != null) {
                    purposes.add(new PurposeUse(routeKey, groups, accessStation, purpose));
                }
            }
            if (routeKeys.isEmpty()) {
                return;
            }

            acceptProfile(PersonProfile.from(person, elements), routeKeys, groupKeys, stationNames, purposes);
        }

        void acceptRawPerson(Set<String> activities, String attributes, Integer age,
                             List<TransitUse> transitUses) {
            persons++;
            if (transitUses == null || transitUses.isEmpty()) return;
            Set<String> routeKeys = new LinkedHashSet<>();
            Set<String> groupKeys = new LinkedHashSet<>();
            Set<String> stationNames = new LinkedHashSet<>();
            List<PurposeUse> purposes = new ArrayList<>();
            for (TransitUse use : transitUses) {
                if (use == null || use.routeId == null || use.routeId.isBlank()) continue;
                String key = routeKey(use.lineId, use.routeId);
                routeKeys.add(key);
                Set<String> groups = context.groups(key);
                groupKeys.addAll(groups);
                String accessStation = context.station(use.accessFacilityId);
                String egressStation = context.station(use.egressFacilityId);
                if (!accessStation.isBlank()) stationNames.add(accessStation);
                if (!egressStation.isBlank()) stationNames.add(egressStation);
                if (use.purpose != null && !use.purpose.isBlank()) {
                    purposes.add(new PurposeUse(key, groups, accessStation, use.purpose));
                }
            }
            if (routeKeys.isEmpty()) return;
            acceptProfile(PersonProfile.fromRaw(activities, attributes, age),
                    routeKeys, groupKeys, stationNames, purposes);
        }

        private void acceptProfile(PersonProfile profile, Set<String> routeKeys,
                                   Set<String> groupKeys, Set<String> stationNames,
                                   List<PurposeUse> purposes) {
            transitRiders++;
            routeKeys.forEach(key -> routes.computeIfAbsent(key, ignored -> new Counts()).addRider(profile));
            groupKeys.forEach(key -> lineGroups.computeIfAbsent(key, ignored -> new Counts()).addRider(profile));
            stationNames.forEach(key -> stations.computeIfAbsent(key, ignored -> new Counts()).addRider(profile));
            for (PurposeUse use : purposes) {
                routes.computeIfAbsent(use.routeKey, ignored -> new Counts()).addPurpose(use.purpose);
                for (String group : use.groups) {
                    lineGroups.computeIfAbsent(group, ignored -> new Counts()).addPurpose(use.purpose);
                }
                if (!use.accessStation.isBlank()) {
                    stations.computeIfAbsent(use.accessStation, ignored -> new Counts()).addPurpose(use.purpose);
                }
            }
        }

        void mergeFrom(Aggregation other) {
            if (other == null) return;
            mergeCounts(routes, other.routes);
            mergeCounts(lineGroups, other.lineGroups);
            mergeCounts(stations, other.stations);
            persons += other.persons;
            transitRiders += other.transitRiders;
        }

        long persons() {
            return persons;
        }

        private static void mergeCounts(Map<String, Counts> target, Map<String, Counts> source) {
            source.forEach((key, value) -> target.computeIfAbsent(key, ignored -> new Counts()).mergeFrom(value));
        }

        Map<String, Object> toPayload() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ready");
            result.put("cacheVersion", PROFILE_CACHE_VERSION);
            result.put("generatedAt", System.currentTimeMillis());
            result.put("personsScanned", persons);
            result.put("transitRiders", transitRiders);
            result.put("routes", payloads(routes));
            result.put("lineGroups", payloads(lineGroups));
            result.put("stations", payloads(stations));
            return result;
        }

        private static Map<String, Object> payloads(Map<String, Counts> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            new TreeMap<>(source).forEach((key, value) -> result.put(key, value.toPayload()));
            return result;
        }
    }

    private record PurposeUse(String routeKey, Set<String> groups, String accessStation, String purpose) {
    }

    record TransitUse(String lineId, String routeId, String accessFacilityId,
                      String egressFacilityId, String purpose) {
        TransitUse withPurpose(String value) {
            return new TransitUse(lineId, routeId, accessFacilityId, egressFacilityId, value);
        }
    }

    private record PersonProfile(boolean commuter, boolean student, boolean elderly,
                                 boolean shopping, boolean leisure, Set<String> activities) {
        static PersonProfile from(Person person, List<PlanElement> elements) {
            Set<String> activities = new LinkedHashSet<>();
            for (PlanElement element : elements) {
                if (element instanceof Activity activity && activity.getType() != null) {
                    String type = activity.getType().toLowerCase(Locale.ROOT);
                    if (!isInteractionActivity(type)) activities.add(type);
                }
            }
            return fromRaw(activities, allAttributeText(person), age(person));
        }

        static PersonProfile fromRaw(Set<String> rawActivities, String rawAttributes, Integer age) {
            Set<String> activities = rawActivities == null ? Set.of() : rawActivities;
            String attributes = rawAttributes == null ? "" : rawAttributes.toLowerCase(Locale.ROOT);
            boolean commuter = hasActivity(activities, "home") && hasActivity(activities, "work")
                    || hasToken(attributes, "worker", "employee", "employed", "commuter", "通勤", "工作");
            boolean student = hasActivity(activities, "school", "educ", "university", "college", "小学", "中学", "学校", "教育")
                    || hasToken(attributes, "student", "school", "university", "学生");
            boolean elderly = age != null && age >= 60
                    || hasToken(attributes, "elderly", "retired", "senior", "老人", "退休");
            boolean shopping = hasActivity(activities, "shop", "mall", "market", "购物", "买")
                    || hasToken(attributes, "shopping", "购物");
            boolean leisure = hasActivity(activities, "leisure", "recreation", "social", "sport", "entertain",
                    "eat", "dining", "休闲", "娱乐", "餐", "运动", "社交")
                    || hasToken(attributes, "leisure", "休闲", "娱乐");
            return new PersonProfile(commuter, student, elderly, shopping, leisure, Set.copyOf(activities));
        }
    }

    private static final class Counts {
        private long total;
        private long commuter;
        private long student;
        private long elderly;
        private long shopping;
        private long leisure;
        private long other;
        private final Map<String, Long> tripPurposes = new HashMap<>();

        void addRider(PersonProfile profile) {
            total++;
            if (profile.elderly) elderly++;
            else if (profile.student) student++;
            if (profile.commuter) commuter++;
            else if (profile.shopping) shopping++;
            else if (profile.leisure) leisure++;
            else other++;
        }

        void addPurpose(String purpose) {
            if (purpose != null && !purpose.isBlank()) tripPurposes.merge(purpose, 1L, Long::sum);
        }

        void mergeFrom(Counts source) {
            total += source.total;
            commuter += source.commuter;
            student += source.student;
            elderly += source.elderly;
            shopping += source.shopping;
            leisure += source.leisure;
            other += source.other;
            source.tripPurposes.forEach((key, value) -> tripPurposes.merge(key, value, Long::sum));
        }

        Map<String, Object> toPayload() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("riderCount", total);
            result.put("commuter", percent(commuter, total));
            result.put("student", percent(student, total));
            result.put("elderly", percent(elderly, total));
            result.put("shopping", percent(shopping, total));
            result.put("leisure", percent(leisure, total));
            result.put("other", percent(other, total));
            result.put("source", "streaming-selected-plans");
            result.put("profileBasis", "selected-plan-transit-users");
            result.put("activitySource", "trip-purpose");
            result.put("activityTypes", activityPayloads(tripPurposes));
            result.put("activityTypeRatios", activityRatios(tripPurposes));
            return result;
        }
    }

    private static List<Map<String, Object>> activityPayloads(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .sorted((left, right) -> {
                    int count = Long.compare(right.getValue(), left.getValue());
                    return count != 0 ? count : left.getKey().compareToIgnoreCase(right.getKey());
                })
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", entry.getKey());
                    item.put("count", entry.getValue());
                    item.put("ratio", percent(entry.getValue(), total));
                    return item;
                })
                .toList();
    }

    private static Map<String, Object> activityRatios(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((left, right) -> {
                    int count = Long.compare(right.getValue(), left.getValue());
                    return count != 0 ? count : left.getKey().compareToIgnoreCase(right.getKey());
                })
                .forEach(entry -> result.put(entry.getKey(), percent(entry.getValue(), total)));
        return result;
    }

    private static String nextTripPurpose(List<PlanElement> elements, int legIndex) {
        for (int i = legIndex + 1; i < elements.size(); i++) {
            if (elements.get(i) instanceof Activity activity && activity.getType() != null) {
                String type = activity.getType().toLowerCase(Locale.ROOT);
                if (!isInteractionActivity(type)) return type;
            }
        }
        return null;
    }

    private static boolean isInteractionActivity(String type) {
        return type != null && type.contains("interaction");
    }

    private static Integer age(Person person) {
        String value = firstText(person, "age", "Age", "AGE", "年龄");
        if (value.isBlank()) return null;
        try {
            return (int) Math.floor(Double.parseDouble(value.trim()));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("乘客年龄字段非法: " + value, error);
        }
    }

    private static String firstText(Person person, String... keys) {
        if (person == null || person.getAttributes() == null) return "";
        for (String key : keys) {
            Object value = person.getAttributes().getAttribute(key);
            if (value != null) return value.toString();
        }
        return "";
    }

    private static String allAttributeText(Person person) {
        if (person == null || person.getAttributes() == null || person.getAttributes().isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        person.getAttributes().getAsMap().forEach((key, value) ->
                result.append(key).append('=').append(value == null ? "" : value).append(';'));
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean hasToken(String text, String... tokens) {
        for (String token : tokens) if (text.contains(token)) return true;
        return false;
    }

    private static boolean hasActivity(Set<String> activities, String... tokens) {
        for (String activity : activities) {
            for (String token : tokens) if (activity.contains(token)) return true;
        }
        return false;
    }

    private static double percent(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return Math.round(numerator * 10_000.0 / denominator) / 100.0;
    }

    private static String routeKey(String lineId, String routeId) {
        return nonBlank(lineId, "") + "::" + nonBlank(routeId, "");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        return manifest(data, ready ? "ready" : "failed", null);
    }

    private static Map<String, Object> manifest(MatsimData data, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("cacheVersion", PROFILE_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        if (message != null && !message.isBlank()) result.put("message", message);
        sourceFingerprint(data, result);
        return result;
    }

    private static boolean isUnsupported(MatsimData data) {
        if (data == null || !Files.isRegularFile(manifestPath(data))) return false;
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "unsupported".equals(manifest.get("status"))
                    && PROFILE_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            throw new IllegalStateException("读取客流画像 unsupported 状态失败: "
                    + manifestPath(data), e);
        }
    }

    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFingerprint(result, "plans", MatsimPlansDerivedCache.resolvePlansFile(data));
        putFingerprint(result, "schedule", data.getOutfile() == null ? null : data.getOutfile().getTransitSchedule());
        result.put("routePanelVersion", MatsimRoutePanelCache.ROUTE_PANEL_CACHE_VERSION);
    }

    private static boolean sameSources(MatsimData data, Map<String, Object> manifest) {
        Map<String, Object> expected = new LinkedHashMap<>();
        sourceFingerprint(data, expected);
        return MatsimSourceFingerprint.sameFlatFingerprint(expected, manifest);
    }

    private static void putFingerprint(Map<String, Object> result, String key, String file) {
        result.put(key + "File", file);
        result.put(key + "Modified", lastModified(file));
        result.put(key + "Size", fileSize(file));
        result.put(key + "Signature", MatsimSourceFingerprint.signature(file));
    }

    private static long lastModified(String file) {
        try {
            return file == null || file.isBlank() || !Files.exists(Path.of(file))
                    ? 0L : Files.getLastModifiedTime(Path.of(file)).toMillis();
        } catch (Exception e) {
            throw new IllegalStateException("读取源文件修改时间失败: " + file, e);
        }
    }

    private static long fileSize(String file) {
        try {
            return file == null || file.isBlank() || !Files.exists(Path.of(file)) ? 0L : Files.size(Path.of(file));
        } catch (Exception e) {
            throw new IllegalStateException("读取源文件大小失败: " + file, e);
        }
    }

    private static void writeGzipJsonAtomic(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp), IO_BUFFER_BYTES);
             OutputStream gzip = new GZIPOutputStream(raw, IO_BUFFER_BYTES)) {
            JSON.writeValue(gzip, payload);
        }
        moveAtomic(tmp, path);
    }

    private static void writeJsonAtomic(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), payload);
        moveAtomic(tmp, path);
    }

    private static void moveAtomic(Path tmp, Path path) throws Exception {
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String cacheKey(MatsimData data) {
        return profilePath(data).toAbsolutePath().normalize().toString();
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, PROFILE_CACHE_VERSION);
    }

    private static Path profilePath(MatsimData data) {
        return cacheDir(data).resolve(PROFILE_FILE);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }
}
