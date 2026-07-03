package com.jts.gjcxfzksh.optimization.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切分结果统计（写入 cutReport.json，同时用于前端展示）。
 */
@Data
public class CutResult {

    private int personsTotal;
    /** 全内保留（保留母本全套创新策略） */
    private int personsInternal;
    /** 跨界折叠 + 方式锁定 */
    private int personsCrossing;
    /** 穿越折叠 + 方式锁定 */
    private int personsThrough;
    private int personsDropped;

    private int linesKept;
    private int linesTruncated;
    private int linesDropped;
    private int routesKept;
    private int stopsKept;

    private int linksKept;
    private int linksDropped;
    private int nodesKept;

    private int transitVehiclesKept;

    /** 原子人群 -> 锁定子人群名 */
    private Map<String, String> lockSubpopulations = new LinkedHashMap<>();

    private long elapsedMs;
}
