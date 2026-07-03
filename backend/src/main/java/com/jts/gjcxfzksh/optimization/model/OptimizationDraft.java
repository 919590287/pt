package com.jts.gjcxfzksh.optimization.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 方案草稿：母本模型 + 研究区域 + 修改项清单。
 * 存储于 pt_data/<区域>/仿真数据/<username>/_drafts/<draftId>.json
 * （_ 前缀目录不会被 MatsimConfig 扫描为模型）。
 */
@Data
public class OptimizationDraft {

    private String draftId;
    private String name;
    /** 母本模型 key：区域/scope/模型名 */
    private String parentModel;
    /** 拥有者用户名（服务端填写） */
    private String owner;

    private AreaSpec area;
    private List<EditItem> edits = new ArrayList<>();

    private long createdAt;
    private long updatedAt;
}
