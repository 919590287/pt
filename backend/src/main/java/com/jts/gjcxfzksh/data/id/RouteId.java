package com.jts.gjcxfzksh.data.id;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RouteId extends Id<TransitRoute> implements Serializable {

    /**
     * 缓存重复的id不重复创建
     */
    private static final ConcurrentMap<Id<TransitRoute>, RouteId> cache = new ConcurrentHashMap<>();

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public RouteId() {
    }

    private RouteId(Id<TransitRoute> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建LinkId
     *
     * @param id matsimId
     */
    public static RouteId create(Id<TransitRoute> id) {
        synchronized (cache) {
            RouteId routeId = cache.get(id);
            if (routeId == null) {
                routeId = new RouteId(id);
                cache.put(id, routeId);
            }
            return routeId;
        }
    }

    public static RouteId create(String obj) {
        synchronized (cache) {
            Id<TransitRoute> id = Id.create(obj, TransitRoute.class);
            RouteId routeId = cache.get(id);
            if (routeId == null) {
                routeId = new RouteId(id);
                cache.put(id, routeId);
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
