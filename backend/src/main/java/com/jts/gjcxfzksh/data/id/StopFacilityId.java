package com.jts.gjcxfzksh.data.id;

import lombok.Data;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 重写TransitStopFacilityId
 */
@Data
public class StopFacilityId extends Id<TransitStopFacility> implements Serializable {

    /**
     * 缓存重复的id不重复创建
     */
    private static final ConcurrentMap<Id<TransitStopFacility>, StopFacilityId> cache = new ConcurrentHashMap<>(1000);

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public StopFacilityId() {
    }


    private StopFacilityId(Id<TransitStopFacility> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建TransitStopFacilityId
     * @param id matsimId
     */
    public static StopFacilityId create(Id<TransitStopFacility> id) {
        synchronized (cache) {
            StopFacilityId facilityId = cache.get(id);
            if (facilityId == null) {
                facilityId = new StopFacilityId(id);
                cache.put(id, facilityId);
            }
            return facilityId;
        }
    }

    public static StopFacilityId create(String obj) {
        synchronized (cache) {
            Id<TransitStopFacility> id = Id.create(obj, TransitStopFacility.class);
            StopFacilityId facilityId = cache.get(id);
            if (facilityId == null) {
                facilityId = new StopFacilityId(id);
                cache.put(id, facilityId);
            }
            return facilityId;
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
