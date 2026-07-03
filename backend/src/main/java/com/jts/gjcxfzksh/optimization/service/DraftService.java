package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.OptimizationDraft;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 方案草稿存储：pt_data/<区域>/仿真数据/<username>/_drafts/<draftId>.json
 * `_` 前缀目录没有 output 子目录，不会被 MatsimConfig 扫描为模型。
 */
@Slf4j
@Service
public class DraftService {

    public static final String DRAFTS_DIR = "_drafts";

    @Resource
    private MatsimConfig matsimConfig;

    public List<OptimizationDraft> list(String username, String parentModel) {
        List<OptimizationDraft> drafts = new ArrayList<>();
        for (String area : matsimConfig.areaNames()) {
            Path dir = draftsDir(area, username);
            File[] files = dir.toFile().listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                try {
                    OptimizationDraft draft = JSON.parseObject(Files.readString(file.toPath()), OptimizationDraft.class);
                    if (draft == null || draft.getDraftId() == null) {
                        continue;
                    }
                    if (parentModel != null && !parentModel.isBlank() && !parentModel.equals(draft.getParentModel())) {
                        continue;
                    }
                    drafts.add(draft);
                } catch (Exception e) {
                    log.warn("草稿文件解析失败: {}", file.getAbsolutePath(), e);
                }
            }
        }
        drafts.sort(Comparator.comparingLong(OptimizationDraft::getUpdatedAt).reversed());
        return drafts;
    }

    public OptimizationDraft save(String username, OptimizationDraft draft) {
        if (draft == null || draft.getParentModel() == null || draft.getParentModel().isBlank()) {
            throw new BusinessException("草稿缺少母本模型");
        }
        matsimConfig.requireSchemeAccess(draft.getParentModel(), username);
        long now = System.currentTimeMillis();
        if (draft.getDraftId() == null || draft.getDraftId().isBlank()) {
            draft.setDraftId("d_" + Long.toString(now, 36) + "_" + UUID.randomUUID().toString().substring(0, 4));
            draft.setCreatedAt(now);
        }
        validateDraftId(draft.getDraftId());
        if (draft.getName() == null || draft.getName().isBlank()) {
            draft.setName("未命名方案");
        }
        draft.setOwner(username);
        draft.setUpdatedAt(now);
        Path file = draftFile(areaOf(draft.getParentModel()), username, draft.getDraftId());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJSONString(draft, JSONWriter.Feature.PrettyFormat));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("草稿保存失败", e);
        }
        return draft;
    }

    public OptimizationDraft get(String username, String parentModel, String draftId) {
        validateDraftId(draftId);
        Path file = draftFile(areaOf(parentModel), username, draftId);
        if (!Files.exists(file)) {
            throw new BusinessException("草稿不存在或已删除");
        }
        try {
            OptimizationDraft draft = JSON.parseObject(Files.readString(file), OptimizationDraft.class);
            if (draft == null) {
                throw new BusinessException("草稿内容无效");
            }
            return draft;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("草稿读取失败", e);
        }
    }

    public void delete(String username, String parentModel, String draftId) {
        validateDraftId(draftId);
        Path file = draftFile(areaOf(parentModel), username, draftId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new BusinessException("草稿删除失败", e);
        }
    }

    public OptimizationDraft copy(String username, String parentModel, String draftId, String newName) {
        OptimizationDraft origin = get(username, parentModel, draftId);
        origin.setDraftId(null);
        origin.setName(newName == null || newName.isBlank() ? origin.getName() + "-副本" : newName);
        return save(username, origin);
    }

    public static String areaOf(String parentModel) {
        if (parentModel == null || !parentModel.contains("/")) {
            throw new BusinessException("模型标识无效: " + parentModel);
        }
        return parentModel.substring(0, parentModel.indexOf('/'));
    }

    private Path draftsDir(String area, String username) {
        return matsimConfig.simulationPath(area).resolve(username).resolve(DRAFTS_DIR);
    }

    private Path draftFile(String area, String username, String draftId) {
        return draftsDir(area, username).resolve(draftId + ".json");
    }

    private void validateDraftId(String draftId) {
        if (draftId == null || draftId.isBlank() || draftId.contains("..") || draftId.contains("/") || draftId.contains("\\")) {
            throw new BusinessException("草稿标识无效");
        }
    }
}
