package com.jts.gjcxfzksh.data.id;

import lombok.Data;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import com.jts.gjcxfzksh.data.cache.BackendMemoryCache;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 重写PersonId
 */
@Data
public class PersonId extends Id<Person> implements Serializable {

    /**
     * 缓存重复的id不重复创建
     */
    private static final BackendMemoryCache<Id<Person>, PersonId> cache =
            new BackendMemoryCache<>("id-person", 16L * 1024 * 1024, ignored -> 160L);

    /**
     * id
     */
    private String id;

    /**
     * index
     */
    private int index;

    public PersonId() {
    }

    private PersonId(Id<Person> id) {
        this.id = id.toString();
        this.index = id.index();
    }

    /**
     * 创建PersonId
     *
     * @param id matsimId
     */
    public static PersonId create(Id<Person> id) {
        synchronized (cache) {
            PersonId personId = cache.get(id);
            if (personId == null) {
                personId = new PersonId(id);
                cache.put(id, personId);
            }
            return personId;
        }
    }

    public static PersonId create(String obj) {
        synchronized (cache) {
            Id<Person> id = Id.create(obj, Person.class);
            PersonId personId = cache.get(id);
            if (personId == null) {
                personId = new PersonId(id);
                cache.put(id, personId);
            }
            return personId;
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
