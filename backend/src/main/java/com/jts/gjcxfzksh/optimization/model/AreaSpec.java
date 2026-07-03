package com.jts.gjcxfzksh.optimization.model;

import lombok.Data;

import java.util.List;

/**
 * 研究区域定义。坐标一律为 lngLat（WGS84），服务端按需转换到模型坐标系。
 */
@Data
public class AreaSpec {

    /** 外环坐标 [[lng,lat], ...]，首尾可以不闭合（服务端自动闭合） */
    private List<double[]> polygon;

    /** 缓冲距离（米），切分时实际保留范围 = 区域 ∪ 缓冲带 */
    private double bufferM = 500;

    /** draw | admin | upload */
    private String source = "draw";

    /** 行政区名称/编码（source=admin 时） */
    private String adminName;
}
