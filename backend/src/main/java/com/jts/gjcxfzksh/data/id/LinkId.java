package com.jts.gjcxfzksh.data.id;

import lombok.Data;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 重写Link
 */
@Data
public class LinkId extends Id<Link> implements Serializable {

    /**
     * 缓存重复的id不重复创建
     */
    private static final ConcurrentMap<Id<Link>, LinkId> cache = new ConcurrentHashMap<>();

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public LinkId() {
    }

    private LinkId(Id<Link> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建LinkId
     *
     * @param id matsimId
     */
    public static LinkId create(Id<Link> id) {
        synchronized (cache) {
            LinkId linkId = cache.get(id);
            if (linkId == null) {
                linkId = new LinkId(id);
                cache.put(id, linkId);
            }
            return linkId;
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
