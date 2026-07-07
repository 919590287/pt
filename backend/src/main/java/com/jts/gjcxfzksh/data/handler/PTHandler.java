package com.jts.gjcxfzksh.data.handler;

import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.*;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.Getter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PTHandler implements
        VehicleArrivesAtFacilityEventHandler,
        VehicleDepartsAtFacilityEventHandler,
        PersonEntersVehicleEventHandler,
        PersonLeavesVehicleEventHandler,
        TransitDriverStartsEventHandler {

    @Getter
    private final Set<PTPersonTrack> personTracks = new ObjectOpenHashSet<>();

    private final ConcurrentHashMap<RouteId, TransitRoute> routes = new ConcurrentHashMap<>();
    /* 数据读取临时对应关系 */
    private final ConcurrentMap<VehicleId, StopFacilityId> vfMap = new ConcurrentHashMap<>();
    // VehicleId -> departureId
    private final ConcurrentMap<VehicleId, DepartureId> vdMap = new ConcurrentHashMap<>();
    // VehicleId -> TransitRouteId
    private final ConcurrentMap<VehicleId, RouteId> vrMap = new ConcurrentHashMap<>();
    // VehicleId -> TransitLineId. TransitRouteId is only unique within a line in many MATSim schedules.
    private final ConcurrentMap<VehicleId, LineId> vlMap = new ConcurrentHashMap<>();
    // 公交司机 personId 集合（来自 TransitDriverStarts 事件），显式排除司机的上下车事件。
    // 原实现依赖“司机上/下车时车辆恰好不在站台（vfMap 无记录）”这一事件顺序侥幸过滤，不可靠。
    private final Set<String> driverIds = ConcurrentHashMap.newKeySet();

    public PTHandler(TransitSchedule schedule) {
        // 静态兜底映射：按时刻表 departure→vehicle 建立。同一辆车服务多个班次/交路时
        // 只保留最后注册的班次，会导致归属错位——TransitDriverStarts 事件（下方 handleEvent）
        // 会在每个班次开始时动态覆盖为当前班次，事件齐全时以动态映射为准。
        schedule.getTransitLines().forEach((lineId, line) -> line.getRoutes().forEach((routeId, route) -> {
            routes.put(RouteId.create(routeId), route);
            route.getDepartures().forEach((departureId, departure) -> {
                if (departure.getVehicleId() == null) {
                    return;
                }
                vdMap.put(VehicleId.create(departure.getVehicleId()), DepartureId.create(departureId));
                vrMap.put(VehicleId.create(departure.getVehicleId()), RouteId.create(routeId));
                vlMap.put(VehicleId.create(departure.getVehicleId()), LineId.create(lineId));
            });
        }));
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        if (event.getDriverId() != null) {
            driverIds.add(event.getDriverId().toString());
        }
        if (event.getVehicleId() == null) {
            return;
        }
        VehicleId vehicleId = VehicleId.create(event.getVehicleId());
        if (event.getTransitRouteId() != null) {
            vrMap.put(vehicleId, RouteId.create(event.getTransitRouteId()));
        }
        if (event.getTransitLineId() != null) {
            vlMap.put(vehicleId, LineId.create(event.getTransitLineId()));
        }
        if (event.getDepartureId() != null) {
            vdMap.put(vehicleId, DepartureId.create(event.getDepartureId()));
        }
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        // 上车
        VehicleId vehicleId = VehicleId.create(event.getVehicleId());
        createTrack(vehicleId, PersonId.create(event.getPersonId()), event.getTime(), true);
    }

    @Override
    public void handleEvent(PersonLeavesVehicleEvent event) {
        // 下车
        VehicleId vehicleId = VehicleId.create(event.getVehicleId());
        createTrack(vehicleId, PersonId.create(event.getPersonId()), event.getTime(), false);
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        vfMap.put(VehicleId.create(event.getVehicleId()), StopFacilityId.create(event.getFacilityId()));
    }

    @Override
    public void handleEvent(VehicleDepartsAtFacilityEvent event) {
        vfMap.remove(VehicleId.create(event.getVehicleId()));
    }

    private void createTrack(VehicleId vehicleId, PersonId personId, double time, Boolean enter) {
        if (personId != null && driverIds.contains(personId.toString())) {
            return; // 司机的上下车事件不是客流
        }
        RouteId routeId = vrMap.get(vehicleId);
        DepartureId departureId = vdMap.get(vehicleId);
        if (routeId == null) {
            return;
        }
        StopFacilityId facilityId = vfMap.get(vehicleId);
        if (facilityId == null) {
            return;
        }
        PTPersonTrack trace = new PTPersonTrack();
        trace.setTime(time);
        trace.setPersonId(personId);
        trace.setVehicleId(vehicleId);
        trace.setLineId(vlMap.get(vehicleId));
        trace.setRouteId(routeId);
        trace.setDepartureId(departureId);
        trace.setFacilityId(facilityId);
        trace.setEnter(enter);
        personTracks.add(trace);
    }

}
