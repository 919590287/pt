package com.jts.gjcxfzksh.api.model.vo;

import lombok.Data;
import org.matsim.pt.transitSchedule.DepartureImpl;
import org.matsim.pt.transitSchedule.api.Departure;

@Data
public class DepartureVO {

    /**
     * id
     */
    private String id;

    /**
     * 发车时间 秒
     * 除 3600 得到小时
     */
    private Double departureTime;

    /**
     * 车辆id
     */
    private String vehicleId;

    /**
     * matsim班次对象转换
     *
     * @param departure matsim班次对象
     */
    public DepartureVO(Departure departure) {
        this.id = departure.getId().toString();
        this.departureTime = departure.getDepartureTime();
        this.vehicleId = departure.getVehicleId().toString();
    }

}
