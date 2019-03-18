/*
 * Copyright (C) 1999-2009 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.openfire.plugin.util.cache;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.MapListener;
import com.hazelcast.monitor.LocalMapStats;

/**
 * Clustered implementation of the Cache interface using Hazelcast.
 *
 */
public class ClusteredCache<K extends Serializable, V extends Serializable> implements Cache<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(ClusteredCache.class);
    
    private final Set<String> listeners = ConcurrentHashMap.newKeySet();

    /**
     * The map is used for distributed operations such as get, put, etc.
     */
    final IMap<K, V> map;
    private String name;
    private long numberOfGets = 0;

    /**
     * Create a new cache using the supplied named cache as the actual cache implementation
     *
     * @param name a name for the cache, which should be unique per vm.
     * @param cache the cache implementation
     */
    protected ClusteredCache(final String name, final IMap<K, V> cache) {
        this.map = cache;
        this.name = name;
    }

    void addEntryListener(final MapListener listener) {
        listeners.add(map.addEntryListener(listener, false));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public V put(final K key, final V object) {
        if (object == null) { return null; }
        return map.put(key, object);
    }

    @Override
    public V get(final Object key) {
        numberOfGets++;
        return map.get(key);
    }

    @Override
    public V remove(final Object key) {
        return map.remove(key);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public int size() {
        final LocalMapStats stats = map.getLocalMapStats();
        return (int) (stats.getOwnedEntryCount() + stats.getBackupEntryCount());
    }

    @Override
    public boolean containsKey(final Object key) {
        return map.containsKey(key);
    }

    @Override
    public boolean containsValue(final Object value) {
        return map.containsValue(value);
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return map.entrySet();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public Set<K> keySet() {
        return map.keySet();
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> entries) {
        map.putAll(entries);
    }

    @Override
    public Collection<V> values() {
        return map.values();
    }

    @Override
    public long getCacheHits() {
        return map.getLocalMapStats().getHits();
    }

    @Override
    public long getCacheMisses() {
        final long hits = map.getLocalMapStats().getHits();
        return numberOfGets > hits ? numberOfGets - hits : 0;
    }

    @Override
    public int getCacheSize() {
        final LocalMapStats stats = map.getLocalMapStats();
        return (int) (stats.getOwnedEntryMemoryCost() + stats.getBackupEntryMemoryCost());
    }

    @Override
    public long getMaxCacheSize() {
        return CacheFactory.getMaxCacheSize(getName());
    }

    @Override
    public void setMaxCacheSize(final int maxSize) {
        CacheFactory.setMaxSizeProperty(getName(), maxSize);
    }

    @Override
    public long getMaxLifetime() {
        return CacheFactory.getMaxCacheLifetime(getName());
    }

    @Override
    public void setMaxLifetime(final long maxLifetime) {
        CacheFactory.setMaxLifetimeProperty(getName(), maxLifetime);
    }

    void destroy() {
        listeners.forEach(map::removeEntryListener);
        map.destroy();
    }

    boolean lock(final K key, final long timeout) {
        boolean result = true;
        if (timeout < 0) {
            map.lock(key);
        } else if (timeout == 0) {
            result = map.tryLock(key);
        } else {
            try {
                result = map.tryLock(key, timeout, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                logger.error("Failed to get cluster lock", e);
                result = false;
            }
        }
        return result;
    }

    void unlock(final K key) {
        try {
            map.unlock(key);
        } catch (final IllegalMonitorStateException e) {
            logger.error("Failed to release cluster lock", e);
        }
    }

}
