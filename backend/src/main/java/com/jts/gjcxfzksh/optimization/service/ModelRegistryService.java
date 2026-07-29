package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.cache.ModelCacheManager;
import com.jts.gjcxfzksh.data.entry.Scheme;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.OptimizationDraft;
import com.jts.gjcxfzksh.optimization.model.RunJob;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 运行成功后的模型注册：desc.json、staging -> 正式目录原子迁移、刷新方案扫描、触发缓存构建。
 */
@Slf4j
@Service
public class ModelRegistryService {

    @Resource
    private MatsimConfig matsimConfig;
    @Resource
    private ModelCacheManager modelCacheManager;

    public void validateNames(String areaName, String scope, String baselineName, String variantName) {
        for (String name : new String[]{baselineName, variantName}) {
            if (name == null || name.isBlank()) {
                throw new BusinessException("模型名称不能为空");
            }
            if (name.contains("/") || name.contains("\\") || name.contains("..") || name.startsWith(".") || name.startsWith("_")) {
                throw new BusinessException("模型名称不能包含路径字符且不能以 . 或 _ 开头: " + name);
            }
            if (name.length() > 80) {
                throw new BusinessException("模型名称过长: " + name);
            }
        }
        if (baselineName.equals(variantName)) {
            throw new BusinessException("基线与方案模型不能同名");
        }
        Path scopeDir = matsimConfig.simulationPath(areaName).resolve(scope);
        if (Files.exists(scopeDir.resolve(baselineName))) {
            throw new BusinessException("模型已存在: " + baselineName);
        }
        if (Files.exists(scopeDir.resolve(variantName))) {
            throw new BusinessException("模型已存在: " + variantName);
        }
    }

    /**
     * 注册两个模型。stagingDir 下需存在 baseline/{input,output}、variant/{input,output} 与元数据文件。
     */
    public void register(RunJob job, OptimizationDraft draft, Path stagingDir) {
        String pairId = job.getJobId();
        Scheme parent = matsimConfig.getSchemes().get(job.getParentModel());
        double parentScale = 1.0;
        double areaKm2 = safeAreaKm2(draft);

        Path scopeDir = matsimConfig.simulationPath(job.getAreaName()).resolve(job.getScope());
        moveModel(job, draft, stagingDir.resolve("baseline"), scopeDir.resolve(job.getBaselineName()),
                "baseline", pairId, parentScale, areaKm2);
        moveModel(job, draft, stagingDir.resolve("variant"), scopeDir.resolve(job.getVariantName()),
                "variant", pairId, parentScale, areaKm2);

        // 重新扫描方案目录，让新模型立即出现在 modelList
        matsimConfig.init();
        String baselineKey = job.getAreaName() + "/" + job.getScope() + "/" + job.getBaselineName();
        String variantKey = job.getAreaName() + "/" + job.getScope() + "/" + job.getVariantName();
        job.setBaselineModelKey(baselineKey);
        job.setVariantModelKey(variantKey);
        enqueueCache(baselineKey);
        enqueueCache(variantKey);
    }

    private void moveModel(RunJob job, OptimizationDraft draft, Path stagingModelDir, Path finalDir,
                           String kind, String pairId, double parentScale, double areaKm2) {
        try {
            if (Files.exists(finalDir)) {
                throw new BusinessException("模型目录已存在: " + finalDir.getFileName());
            }
            Files.createDirectories(finalDir);
            // input 与 output 直接移动（同卷原子操作）
            movePath(stagingModelDir.resolve("input"), finalDir.resolve("input"));
            movePath(stagingModelDir.resolve("output"), finalDir.resolve("output"));

            // 元数据
            JSONObject desc = new JSONObject();
            desc.put("detail", buildDetail(job, kind));
            desc.put("_default", false);
            desc.put("scale", 1.0);
            desc.put("area", Math.max(0.01, areaKm2));
            JSONObject optimization = new JSONObject();
            optimization.put("kind", kind);
            optimization.put("pairId", pairId);
            optimization.put("parentModel", job.getParentModel());
            optimization.put("cutMode", "B");
            optimization.put("editsCount", draft.getEdits() == null ? 0 : draft.getEdits().size());
            optimization.put("iterations", job.getIterations());
            optimization.put("generatedAt", System.currentTimeMillis());
            optimization.put("draftName", draft.getName());
            optimization.put("baselineName", job.getBaselineName());
            optimization.put("variantName", job.getVariantName());
            if (draft.getArea() != null) {
                optimization.put("regionPolygon", draft.getArea().getPolygon());
                optimization.put("bufferM", draft.getArea().getBufferM());
            }
            desc.put("optimization", optimization);
            Files.writeString(finalDir.resolve("desc.json"), JSON.toJSONString(desc, JSONWriter.Feature.PrettyFormat));

            // 留档：草稿快照 + 修改清单 + 切分报告
            copyIfExists(stagingModelDir.getParent().resolve("draft.snapshot.json"), finalDir.resolve("draft.snapshot.json"));
            copyIfExists(stagingModelDir.getParent().resolve("edits.json"), finalDir.resolve("edits.json"));
            copyIfExists(stagingModelDir.getParent().resolve("cutReport.json"), finalDir.resolve("cutReport.json"));
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("模型注册失败（文件迁移）: " + e.getMessage(), e);
        }
    }

    private String buildDetail(RunJob job, String kind) {
        String base = "baseline".equals(kind)
                ? "线网优化切分基线（母本：" + job.getParentModel() + "）"
                : "线网优化方案模型（基线：" + job.getBaselineName() + "）";
        return base + "，研究方案：" + job.getDraftName();
    }

    private void movePath(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            throw new BusinessException("缺少目录: " + from);
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(from, to);
        }
    }

    private void copyIfExists(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private double safeAreaKm2(OptimizationDraft draft) {
        try {
            Polygon polygon = GeoUtil.toPolygon(draft.getArea().getPolygon(), null, true);
            return GeoUtil.areaKm2Mercator(polygon, RegionStatsService.centroidLat(draft.getArea()));
        } catch (Exception e) {
            return 1.0;
        }
    }

    private void enqueueCache(String key) {
        try {
            Scheme scheme = matsimConfig.getSchemes().get(key);
            if (scheme != null) {
                modelCacheManager.enqueueIfMissing(scheme);
            }
        } catch (Exception e) {
            log.warn("模型缓存入队失败: {}", key, e);
        }
    }

    /** 清理 staging 中未迁移的残留 */
    public void cleanupStaging(Path stagingDir) {
        if (!Files.exists(stagingDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(stagingDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("staging 清理失败: {}", stagingDir, e);
        }
    }
}
