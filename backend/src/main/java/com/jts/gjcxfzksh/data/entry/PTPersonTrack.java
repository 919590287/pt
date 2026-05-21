package com.jts.gjcxfzksh.data.entry;

import com.jts.gjcxfzksh.data.id.*;
import lombok.Data;

@Data
public class PTPersonTrack {

    /**
     * 乘客id
     */
    private PersonId personId;
    /**
     * 线路id
     */
    private LineId lineId;
    /**
     * 路线id
     */
    private RouteId routeId;
    /**
     * 车辆id
     */
    private VehicleId vehicleId;
    /**
     * 班次id
     */
    private DepartureId departureId;
    /**
     * 站点id
     */
    private StopFacilityId facilityId;
    /**
     * true 为上车， false 为下车
     */
    private Boolean enter;
    /**
     * 上下车时间
     */
    private Double time = 0.;

}
