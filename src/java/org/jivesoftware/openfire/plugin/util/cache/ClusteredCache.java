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

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapEvent;
import com.hazelcast.map.listener.MapListener;
import com.hazelcast.monitor.LocalMapStats;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusteredCacheEntryListener;
import org.jivesoftware.openfire.cluster.NodeID;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginClassLoader;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Clustered implementation of the Cache interface using Hazelcast.
 *
 */
public class ClusteredCache<K extends Serializable, V extends Serializable> implements Cache<K, V> {

    private final Logger logger;

    private final Set<String> listeners = ConcurrentHashMap.newKeySet();

    /**
     * The map is used for distributed operations such as get, put, etc.
     */
    final IMap<K, V> map;
    private String name;
    private long numberOfGets = 0;

    /**
     * Used to limit the amount of duplicate warnings logged.
     */
    private Instant lastPluginClassLoaderWarning = Instant.EPOCH;
    private final Duration pluginClassLoaderWarningSupression = Duration.ofHours(1);

    /**
     * Create a new cache using the supplied named cache as the actual cache implementation
     *
     * @param name a name for the cache, which should be unique per vm.
     * @param cache the cache implementation
     */
    protected ClusteredCache(final String name, final IMap<K, V> cache) {
        this.map = cache;
        this.name = name;
        logger = LoggerFactory.getLogger(ClusteredCache.class.getName() + "[cache: "+name+"]");
    }

    void addEntryListener(final MapListener listener) {
        listeners.add(map.addEntryListener(listener, false));
    }

    @Override
    public String addClusteredCacheEntryListener(@Nonnull final ClusteredCacheEntryListener<K, V> clusteredCacheEntryListener, final boolean includeValues, final boolean includeEventsFromLocalNode)
    {
        final EntryListener<K, V> listener = new EntryListener<K, V>() {
            @Override
            public void mapEvicted(MapEvent event) {
                if (includeEventsFromLocalNode || !event.getMember().localMember()) {
                    final NodeID eventNodeId = ClusteredCacheFactory.getNodeID(event.getMember());
                    logger.trace("Processing map evicted event of node '{}'", eventNodeId);
                    clusteredCacheEntryListener.mapEvicted(eventNodeId);
                }
            }

            @Override
            public void mapCleared(MapEvent event) {
                if (includeEventsFromLocalNode || !event.getMember().localMember()) {
                    final NodeID eventNodeId = ClusteredCacheFactory.getNodeID(event.getMember());
                    logger.trace("Processing map cleared event of node '{}'", eventNodeId);
                    clusteredCacheEntryListener.mapCleared(eventNodeId);
                }
            }

            @Override
            public void entryUpdated(EntryEvent event) {
                if (includeEventsFromLocalNode || !event.getMember().localMember()) {
                    final NodeID eventNodeId = ClusteredCacheFactory.getNodeID(event.getMember());
                    logger.trace("Processing entry update event of node '{}' for key '{}'", eventNodeId, event.getKey());
                    clusteredCacheEntryListener.entryUpdated((K) event.getKey(), (V) event.getOldValue(), (V) event.getValue(), eventNodeId);
                }
            }

            @Override
            public void entryRemoved(EntryEvent event) {
                if (includeEventsFromLocalNode || !event.getMember().localMember()) {
                    final NodeID eventNodeId = ClusteredCacheFactory.getNodeID(event.getMember());
                    logger.trace("Processing entry removed event of node '{}' for key '{}'", eventNodeId, event.getKey());
                    clusteredCacheEntryListener.entryRemoved((K) event.getKey(), (V) event.getOldValue(), eventNodeId);
                }
            }

            @Override
            public void entryEvicted(EntryEvent event) {
                if (includeEventsFromLocalNode || !event.getMember().localMember()) {
                    final NodeID eventNodeId = ClusteredCacheFactory.getNodeID(event.getMember());
                    logger.trace("Processing entry evicted event of node '{}' for key '{}'", eventNodeId, event.getKey());
                    clusteredCacheEntryListener.entryEvicted((K) event.getKey(), (V) event.getOldValue(), eventNodeId);
                }
            }

            @Override
            public void entryAdded(EntryEvent event) {
                if (includeEventsFromLocalNode || !event.getMember().localMember()) {
                    final NodeID eventNodeId = ClusteredCacheFactory.getNodeID(event.getMember());
                    logger.trace("Processing entry added event of node '{}' for key '{}'", eventNodeId, event.getKey());
                    clusteredCacheEntryListener.entryAdded((K) event.getKey(), (V) event.getValue(), eventNodeId);
                }
            }
        };

        final String listenerId = map.addEntryListener(listener, includeValues);
        listeners.add(listenerId);
        logger.debug("Added new clustered cache entry listener (including values: {}, includeEventsFromLocalNode: {}) using ID: '{}'", includeValues, includeEventsFromLocalNode, listenerId);
        return listenerId;
    }

    @Override
    public void removeClusteredCacheEntryListener(@Nonnull final String listenerId) {
        logger.debug("Removing clustered cache entry listener: '{}'", listenerId);
        map.removeEntryListener(listenerId);
        listeners.remove(listenerId);
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
        checkForPluginClassLoader(key);
        checkForPluginClassLoader(object);
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

        // Instances are likely all loaded by the same class loader. For resource usage optimization, let's test just one, not all.
        entries.entrySet().stream().findAny().ifPresent(
            e -> {
                checkForPluginClassLoader(e.getKey());
                checkForPluginClassLoader(e.getValue());
            }
        );
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
        return (int) getLongCacheSize();
    }

    @Override
    public long getLongCacheSize() {
        final LocalMapStats stats = map.getLocalMapStats();
        return stats.getOwnedEntryMemoryCost() + stats.getBackupEntryMemoryCost();
    }

    @Override
    public long getMaxCacheSize() {
        return CacheFactory.getMaxCacheSize(getName());
    }

    @Override
    public void setMaxCacheSize(int i) {
        setMaxCacheSize((long) i);
    }

    @Override
    public void setMaxCacheSize(final long maxSize) {
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

    /**
     * Clustered caches should not contain instances of classes that are provided by Openfire plugins. These will cause
     * issues related to class loading when the providing plugin is reloaded. This method verifies if an instance is
     * loaded by a plugin class loader, and logs a warning to the log files when it is. The amount of warnings logged is
     * limited by a time interval.
     *
     * @param o the instance for which to verify the class loader
     * @see <a href="https://github.com/igniterealtime/openfire-hazelcast-plugin/issues/74">Issue #74: Warn against usage of plugin-provided classes in Hazelcast</a>
     */
    protected void checkForPluginClassLoader(final Object o) {
        if (o != null && o.getClass().getClassLoader() instanceof PluginClassLoader
            && lastPluginClassLoaderWarning.isBefore(Instant.now().minus(pluginClassLoaderWarningSupression)) )
        {
            // Try to determine what plugin loaded the offending class.
            String pluginName = null;
            try {
                final Collection<Plugin> plugins = XMPPServer.getInstance().getPluginManager().getPlugins();
                for (final Plugin plugin : plugins) {
                    final PluginClassLoader pluginClassloader = XMPPServer.getInstance().getPluginManager().getPluginClassloader(plugin);
                    if (o.getClass().getClassLoader().equals(pluginClassloader)) {
                        pluginName = XMPPServer.getInstance().getPluginManager().getCanonicalName(plugin);
                        break;
                    }
                }
            } catch (Exception e) {
                logger.debug("An exception occurred while trying to determine the plugin class loader that loaded an instance of {}", o.getClass(), e);
            }
            logger.warn("An instance of {} that is loaded by {} has been added to the cache. " +
                "This will cause issues when reloading the plugin that provides this class. The plugin implementation should be modified.",
                o.getClass(), pluginName != null ? pluginName : "a PluginClassLoader");
            lastPluginClassLoaderWarning = Instant.now();
        }
    }
}
