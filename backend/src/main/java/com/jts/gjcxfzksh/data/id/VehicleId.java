package com.jts.gjcxfzksh.data.id;

import lombok.Data;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.vehicles.Vehicle;
import com.jts.gjcxfzksh.data.cache.BackendMemoryCache;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 重写VehicleId
 */
@Data
public class VehicleId extends Id<Vehicle> implements Serializable {

    /**
     * 缓存重复的id不重复创建
     */
    private static final BackendMemoryCache<Id<Vehicle>, VehicleId> cache =
            new BackendMemoryCache<>("id-vehicle", 16L * 1024 * 1024, ignored -> 160L);

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public VehicleId() {
    }

    private VehicleId(Id<Vehicle> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建VehicleId
     *
     * @param id matsimId
     */
    public static VehicleId create(Id<Vehicle> id) {
        synchronized (cache) {
            VehicleId vehicleId = cache.get(id);
            if (vehicleId == null) {
                vehicleId = new VehicleId(id);
                cache.put(id, vehicleId);
            }
            return vehicleId;
        }
    }

    /**
     * 创建VehicleId
     */
    public static VehicleId create(String obj) {
        synchronized (cache) {
            Id<Vehicle> id = Id.create(obj, Vehicle.class);
            VehicleId vehicleId = cache.get(id);
            if (vehicleId == null) {
                vehicleId = new VehicleId(id);
                cache.put(id, vehicleId);
            }
            return vehicleId;
        }
    }


    @Override
    public int index() {
        return this.index;
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Id) {
            return this.id.equals(obj.toString());
        } else {
            return super.equals(obj);
        }
    }

    @Override
    public String toString() {
        return this.id;
    }
}
