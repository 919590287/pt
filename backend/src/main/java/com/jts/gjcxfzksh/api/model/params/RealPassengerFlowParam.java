package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

@Data
public class RealPassengerFlowParam {

    private String areaName;

    /** 空值/average 表示全样本日平均，否则为 yyyy-MM-dd 运营日期。 */
    private String serviceDate;

    /** 体检评估空间范围；空值或“全市”表示全市。 */
    private String district;

    private String lineId;

    private String routeId;

    private String departureId;

    private String stationName;

    private String facilityId;
}
