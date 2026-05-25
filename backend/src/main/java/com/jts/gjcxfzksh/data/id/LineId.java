package com.jts.gjcxfzksh.data.id;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.TransitLine;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class LineId extends Id<TransitLine> implements Serializable {


    /**
     * 缓存重复的id不重复创建
     */
    private static final ConcurrentMap<Id<TransitLine>, LineId> cache = new ConcurrentHashMap<>();

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public LineId() {
    }

    private LineId(Id<TransitLine> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建LinkId
     *
     * @param id matsimId
     */
    public static LineId create(Id<TransitLine> id) {
        synchronized (cache) {
            LineId lineId = cache.get(id);
            if (lineId == null) {
                lineId = new LineId(id);
                cache.put(id, lineId);
            }
            return lineId;
        }
    }

    public static LineId create(String obj) {
        synchronized (cache) {
            Id<TransitLine> id = Id.create(obj, TransitLine.class);
            LineId lineId = cache.get(id);
            if (lineId == null) {
                lineId = new LineId(id);
                cache.put(id, lineId);
            }
            return lineId;
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
