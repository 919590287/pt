package com.jts.gjcxfzksh.data.id;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.Departure;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DepartureId extends Id<Departure> implements Serializable {


    /**
     * 缓存重复的id不重复创建
     */
    private static final ConcurrentMap<Id<Departure>, DepartureId> cache = new ConcurrentHashMap<>();

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public DepartureId() {
    }

    private DepartureId(Id<Departure> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建LinkId
     *
     * @param id matsimId
     */
    public static DepartureId create(Id<Departure> id) {
        synchronized (cache) {
            DepartureId routeId = cache.get(id);
            if (routeId == null) {
                routeId = new DepartureId(id);
                cache.put(id, routeId);
            }
            return routeId;
        }
    }

    public static DepartureId create(String id) {
        synchronized (cache) {
            Id<Departure> departureId = Id.create(id, Departure.class);
            DepartureId routeId = cache.get(departureId);
            if (routeId == null) {
                routeId = new DepartureId(departureId);
                cache.put(departureId, routeId);
            }
            return routeId;
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
