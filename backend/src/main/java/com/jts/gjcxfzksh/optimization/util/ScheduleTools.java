package com.jts.gjcxfzksh.optimization.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jts.gjcxfzksh.exception.BusinessException;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 时刻表生成工具：发车时刻展开、站间时分推算、车型解析、车辆引用清理。
 */
public final class ScheduleTools {

    private ScheduleTools() {
    }

    /** "HH:mm" / "HH:mm:ss" -> 秒（支持 >24h，如 25:30） */
    public static double parseTime(String time) {
        if (time == null || time.isBlank()) {
            throw new BusinessException("时间不能为空");
        }
        String[] parts = time.trim().split(":");
        try {
            double h = Double.parseDouble(parts[0]);
            double m = parts.length > 1 ? Double.parseDouble(parts[1]) : 0;
            double s = parts.length > 2 ? Double.parseDouble(parts[2]) : 0;
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            throw new BusinessException("时间格式无效: " + time);
        }
    }

    /**
     * 分时段发车间隔表展开为发车时刻序列（秒）。
     * slots: [{from:"07:00", to:"09:00", headwayMin:5}, ...]
     */
    public static List<Double> expandDepartureTimes(JSONArray slots) {
        if (slots == null || slots.isEmpty()) {
            throw new BusinessException("发车时段表不能为空");
        }
        List<double[]> ranges = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            JSONObject slot = slots.getJSONObject(i);
            double from = parseTime(slot.getString("from"));
            double to = parseTime(slot.getString("to"));
            double headway = slot.getDoubleValue("headwayMin") * 60;
            if (to <= from) {
                to += 24 * 3600; // 跨零点
            }
            if (headway < 60) {
                throw new BusinessException("发车间隔不能小于1分钟");
            }
            if ((to - from) / headway > 600) {
                throw new BusinessException("单时段班次数超过600，请检查发车间隔");
            }
            ranges.add(new double[]{from, to, headway});
        }
        ranges.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Double> times = new ArrayList<>();
        double last = -1;
        for (double[] r : ranges) {
            double t = r[0];
            if (last >= 0 && t <= last) {
                t = last + r[2]; // 时段衔接处避免同刻并发
            }
            for (; t < r[1] + 1; t += r[2]) {
                times.add(t);
                last = t;
            }
        }
        if (times.isEmpty()) {
            throw new BusinessException("发车时段表未产生任何班次");
        }
        return times;
    }

    /**
     * 沿 link 序列推算各站到发时分（相对首班发车）。
     * stopLinkIndex: 每个站点在 linkIds 中的位置（站挂在该 link 上，走完该 link 即到站）。
     */
    public static double[][] computeOffsets(List<Id<Link>> linkIds, int[] stopLinkIndex, Network network,
                                            double opSpeedKmh, double dwellSec) {
        double opSpeed = Math.max(1, opSpeedKmh) / 3.6;
        double[] cumTime = new double[linkIds.size()];
        double t = 0;
        for (int i = 0; i < linkIds.size(); i++) {
            Link link = network.getLinks().get(linkIds.get(i));
            if (link == null) {
                throw new BusinessException("路径引用了不存在的路段: " + linkIds.get(i));
            }
            double speed = Math.min(Math.max(1, link.getFreespeed()), opSpeed);
            t += link.getLength() / speed;
            cumTime[i] = t;
        }
        double[][] offsets = new double[stopLinkIndex.length][2];
        double dwellAccum = 0;
        double firstArr = stopLinkIndex.length > 0 ? cumTime[stopLinkIndex[0]] : 0;
        for (int k = 0; k < stopLinkIndex.length; k++) {
            double arr = cumTime[stopLinkIndex[k]] - firstArr + dwellAccum;
            double dep = arr + (k == stopLinkIndex.length - 1 ? 0 : dwellSec);
            offsets[k][0] = Math.max(0, arr);
            offsets[k][1] = Math.max(0, dep);
            dwellAccum += (k == stopLinkIndex.length - 1 ? 0 : dwellSec);
        }
        return offsets;
    }

    /**
     * 解析/创建车型。params.vehicleType = {ref:"typeId"} 或 {name,seats,standing,lengthM}
     */
    public static VehicleType resolveVehicleType(Vehicles vehicles, JSONObject vt, String editId) {
        if (vt == null) {
            throw new BusinessException("缺少车型配置");
        }
        String ref = vt.getString("ref");
        if (ref != null && !ref.isBlank()) {
            VehicleType existing = vehicles.getVehicleTypes().get(Id.create(ref, VehicleType.class));
            if (existing == null) {
                throw new BusinessException("车型不存在: " + ref);
            }
            return existing;
        }
        Id<VehicleType> id = Id.create("opt_vt_" + editId, VehicleType.class);
        VehicleType existing = vehicles.getVehicleTypes().get(id);
        if (existing != null) {
            return existing;
        }
        VehicleType type = VehicleUtils.createVehicleType(id);
        int seats = vt.getIntValue("seats", 30);
        int standing = vt.getIntValue("standing", 50);
        Double lengthVal = vt.getDouble("lengthM");
        double length = lengthVal == null ? 12 : lengthVal;
        type.getCapacity().setSeats(seats);
        type.getCapacity().setStandingRoom(standing);
        type.setLength(length);
        type.setPcuEquivalents(2.8);
        if (vt.getString("name") != null) {
            type.setDescription(vt.getString("name"));
        }
        vehicles.addVehicleType(type);
        return type;
    }

    /**
     * 为 route 重建发车班次：清除旧班次、生成新班次与车辆（每班一车）。
     */
    public static int rebuildDepartures(TransitSchedule schedule, Vehicles vehicles, TransitLine line, TransitRoute route,
                                        List<Double> departureTimes, VehicleType type, String idPrefix) {
        TransitScheduleFactory factory = schedule.getFactory();
        List<Departure> old = new ArrayList<>(route.getDepartures().values());
        Set<Id<Vehicle>> oldVehicles = new LinkedHashSet<>();
        for (Departure d : old) {
            if (d.getVehicleId() != null) {
                oldVehicles.add(d.getVehicleId());
            }
            route.removeDeparture(d);
        }
        int n = 0;
        for (double t : departureTimes) {
            String suffix = idPrefix + "_" + n;
            Departure dep = factory.createDeparture(Id.create("opt_d_" + suffix, Departure.class), t);
            Id<Vehicle> vid = Id.createVehicleId("opt_v_" + suffix);
            if (vehicles.getVehicles().get(vid) == null) {
                vehicles.addVehicle(VehicleUtils.createVehicle(vid, type));
            }
            dep.setVehicleId(vid);
            route.addDeparture(dep);
            n++;
        }
        removeUnreferencedVehicles(schedule, vehicles, oldVehicles);
        return n;
    }

    /** 移除不再被任何班次引用的车辆 */
    public static void removeUnreferencedVehicles(TransitSchedule schedule, Vehicles vehicles, Set<Id<Vehicle>> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Set<Id<Vehicle>> referenced = new LinkedHashSet<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (Departure d : route.getDepartures().values()) {
                    if (d.getVehicleId() != null) {
                        referenced.add(d.getVehicleId());
                    }
                }
            }
        }
        for (Id<Vehicle> vid : candidates) {
            if (!referenced.contains(vid) && vehicles.getVehicles().containsKey(vid)) {
                vehicles.removeVehicle(vid);
            }
        }
    }

    /** 站点在路径 link 序列中的挂接位置；找不到返回 -1 */
    public static int stopLinkIndex(List<Id<Link>> linkIds, TransitStopFacility facility) {
        if (facility.getLinkId() == null) {
            return -1;
        }
        for (int i = 0; i < linkIds.size(); i++) {
            if (linkIds.get(i).equals(facility.getLinkId())) {
                return i;
            }
        }
        return -1;
    }
}
