/*
 * Copyright (C) 2007-2009 Jive Software. All rights reserved.
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

import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizeConfig;
import com.hazelcast.config.MemberAttributeConfig;
import com.hazelcast.config.MemcacheProtocolConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.RestApiConfig;
import com.hazelcast.core.Cluster;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.Member;
import org.jivesoftware.openfire.JMXManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusterEventListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.cluster.ClusterNodeInfo;
import org.jivesoftware.openfire.cluster.NodeID;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginClassLoader;
import org.jivesoftware.openfire.plugin.HazelcastPlugin;
import org.jivesoftware.openfire.plugin.util.cluster.HazelcastClusterNodeInfo;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.SystemProperty;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.jivesoftware.util.cache.CacheFactoryStrategy;
import org.jivesoftware.util.cache.CacheWrapper;
import org.jivesoftware.util.cache.ClusterTask;
import org.jivesoftware.util.cache.ExternalizableUtil;
import org.jivesoftware.util.cache.ExternalizableUtilStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * CacheFactory implementation to use when using Hazelcast in cluster mode.
 *
 * @author Tom Evans
 * @author Gaston Dombiak
 */
public class ClusteredCacheFactory implements CacheFactoryStrategy {

    private static final SystemProperty<String> HAZELCAST_EXECUTOR_SERVICE_NAME = SystemProperty.Builder.ofType(String.class)
        .setKey("hazelcast.executor.service.name")
        .setDefaultValue("openfire::cluster::executor")
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<Duration> MAX_CLUSTER_EXECUTION_TIME = SystemProperty.Builder.ofType(Duration.class)
        .setKey("hazelcast.max.execution.seconds")
        .setDefaultValue(Duration.ofSeconds(30))
        .setChronoUnit(ChronoUnit.SECONDS)
        .setDynamic(true)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<Duration> CLUSTER_STARTUP_RETRY_TIME = SystemProperty.Builder.ofType(Duration.class)
        .setKey("hazelcast.startup.retry.seconds")
        .setDefaultValue(Duration.ofSeconds(10))
        .setChronoUnit(ChronoUnit.SECONDS)
        .setDynamic(true)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<Integer> CLUSTER_STARTUP_RETRY_COUNT = SystemProperty.Builder.ofType(Integer.class)
        .setKey("hazelcast.startup.retry.count")
        .setDefaultValue(1)
        .setDynamic(true)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<String> HAZELCAST_CONFIG_FILE = SystemProperty.Builder.ofType(String.class)
        .setKey("hazelcast.config.xml.filename")
        .setDefaultValue("hazelcast-cache-config.xml")
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<Boolean> HAZELCAST_JMX_ENABLED = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("hazelcast.config.jmx.enabled")
        .setDefaultValue(Boolean.FALSE)
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<Boolean> HAZELCAST_REST_ENABLED = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("hazelcast.config.rest.enabled")
        .setDefaultValue(Boolean.FALSE)
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();
    private static final SystemProperty<Boolean> HAZELCAST_MEMCACHE_ENABLED = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("hazelcast.config.memcache.enabled")
        .setDefaultValue(Boolean.FALSE)
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();

    private static final Logger logger = LoggerFactory.getLogger(ClusteredCacheFactory.class);
    public static final String PLUGIN_NAME = "hazelcast";

    /**
     * Keep serialization strategy the server was using before we set our strategy. We will
     * restore old strategy when plugin is unloaded.
     */
    private ExternalizableUtilStrategy serializationStrategy;

    /**
     * Storage for cache statistics
     */
    private static Map<String, Map<String, long[]>> cacheStats;

    private static HazelcastInstance hazelcast = null;
    private static Cluster cluster = null;
    private ClusterListener clusterListener;
    private String lifecycleListener;
    private String membershipListener;

    /**
     * Keeps that running state. Initial state is stopped.
     */
    private State state = State.stopped;

    /**
     * Used to limit the amount of duplicate warnings logged.
     */
    private final Cache<String, Instant> pluginClassLoaderWarnings;

    public ClusteredCacheFactory() {
        pluginClassLoaderWarnings = CacheFactory.createLocalCache("PluginClassLoader Warnings for Clustered Tasks");
        pluginClassLoaderWarnings.setMaxLifetime(Duration.ofHours(1).toMillis()); // Minimum duration between logged warnings.
    }

    @Override
    public boolean startCluster() {
        logger.info("Starting hazelcast clustering");
        state = State.starting;

        // Set the serialization strategy to use for transmitting objects between node clusters
        serializationStrategy = ExternalizableUtil.getInstance().getStrategy();
        ExternalizableUtil.getInstance().setStrategy(new ClusterExternalizableUtil());

        // Store previous class loader (in case we change it)
        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        final ClassLoader loader = new ClusterClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        int retry = 0;
        do {
            try {
                final Config config = new ClasspathXmlConfig(HAZELCAST_CONFIG_FILE.getValue());
                final NetworkConfig networkConfig = config.getNetworkConfig();
                if (!HAZELCAST_MEMCACHE_ENABLED.getValue()) {
                    networkConfig.setMemcacheProtocolConfig(new MemcacheProtocolConfig().setEnabled(false));
                }
                if (!HAZELCAST_REST_ENABLED.getValue()) {
                    networkConfig.setRestApiConfig(new RestApiConfig().setEnabled(false));
                }
                final MemberAttributeConfig memberAttributeConfig = config.getMemberAttributeConfig();
                memberAttributeConfig.setStringAttribute(HazelcastClusterNodeInfo.HOST_NAME_ATTRIBUTE, XMPPServer.getInstance().getServerInfo().getHostname());
                memberAttributeConfig.setStringAttribute(HazelcastClusterNodeInfo.NODE_ID_ATTRIBUTE, XMPPServer.getInstance().getNodeID().toString());
                config.setInstanceName("openfire");
                config.setClassLoader(loader);
                if (JMXManager.isEnabled() && HAZELCAST_JMX_ENABLED.getValue()) {
                    config.setProperty("hazelcast.jmx", "true");
                    config.setProperty("hazelcast.jmx.detailed", "true");
                }
                hazelcast = Hazelcast.newHazelcastInstance(config);
                cluster = hazelcast.getCluster();
                state = State.started;
                // CacheFactory is now using clustered caches. We can add our listeners.
                clusterListener = new ClusterListener(cluster);
                clusterListener.joinCluster();
                lifecycleListener = hazelcast.getLifecycleService().addLifecycleListener(clusterListener);
                membershipListener = cluster.addMembershipListener(clusterListener);
                logger.info("Hazelcast clustering started");
                break;
            } catch (final Exception e) {
                cluster = null;
                if (retry < CLUSTER_STARTUP_RETRY_COUNT.getValue()) {
                    logger.warn("Failed to start clustering (" + e.getMessage() + "); " +
                        "will retry in " + StringUtils.getFullElapsedTime(CLUSTER_STARTUP_RETRY_TIME.getValue()));
                    try {
                        Thread.sleep(CLUSTER_STARTUP_RETRY_TIME.getValue().toMillis());
                    } catch (final InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    logger.error("Unable to start clustering - continuing in local mode", e);
                    state = State.stopped;
                }
            }
        } while (retry++ < CLUSTER_STARTUP_RETRY_COUNT.getValue() && !Thread.currentThread().isInterrupted());

        if (oldLoader != null) {
            // Restore previous class loader
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
        return cluster != null;
    }

    @Override
    public void stopCluster() {
        // Stop the cache services.
        cacheStats = null;
        // Update the running state of the cluster
        state = State.stopped;

        // Fire the leftClusterEvent before we leave the cluster - we need to access the clustered data before the
        // cluster is shutdown so it can be copied in to the non-clustered, DefaultCache
        fireLeftClusterAndWaitToComplete(Duration.ofSeconds(30));
        // Stop the cluster
        hazelcast.getLifecycleService().removeLifecycleListener(lifecycleListener);
        cluster.removeMembershipListener(membershipListener);
        Hazelcast.shutdownAll();
        cluster = null;
        lifecycleListener = null;
        membershipListener = null;
        clusterListener = null;

        // Reset packet router to use to deliver packets to remote cluster nodes
        XMPPServer.getInstance().getRoutingTable().setRemotePacketRouter(null);
        // Reset the session locator to use
        XMPPServer.getInstance().setRemoteSessionLocator(null);
        // Set the old serialization strategy was using before clustering was loaded
        ExternalizableUtil.getInstance().setStrategy(serializationStrategy);
    }

    @Override
    public Cache createCache(final String name) {
        // Check if cluster is being started up
        while (state == State.starting) {
            // Wait until cluster is fully started (or failed)
            try {
                Thread.sleep(250);
            } catch (final InterruptedException e) {
                // Ignore
            }
        }
        if (state == State.stopped) {
            throw new IllegalStateException("Cannot create clustered cache when not in a cluster");
        }
        // Determine the time to live. Note that in Hazelcast 0 means "forever", not -1
        final long openfireLifetimeInMilliseconds = CacheFactory.getMaxCacheLifetime(name);
        final int hazelcastLifetimeInSeconds = openfireLifetimeInMilliseconds < 0 ? 0 : Math.max((int) (openfireLifetimeInMilliseconds / 1000), 1);
        // Determine the max cache size. Note that in Hazelcast the max cache size must be positive and is in megabytes
        final long openfireMaxCacheSizeInBytes = CacheFactory.getMaxCacheSize(name);
        final int hazelcastMaxCacheSizeInMegaBytes = openfireMaxCacheSizeInBytes < 0 ? Integer.MAX_VALUE : Math.max((int) openfireMaxCacheSizeInBytes / 1024 / 1024, 1);
        // It's only possible to create a dynamic config if a static one doesn't already exist
        final MapConfig staticConfig = hazelcast.getConfig().getMapConfigOrNull(name);
        if (staticConfig == null) {
            final MapConfig dynamicConfig = new MapConfig(name);
            dynamicConfig.setTimeToLiveSeconds(hazelcastLifetimeInSeconds);
            dynamicConfig.setMaxSizeConfig(new MaxSizeConfig(hazelcastMaxCacheSizeInMegaBytes, MaxSizeConfig.MaxSizePolicy.USED_HEAP_SIZE));
            logger.debug("Creating dynamic map config for cache={}, dynamicConfig={}", name, dynamicConfig);
            hazelcast.getConfig().addMapConfig(dynamicConfig);
        } else {
            logger.debug("Static configuration already exists for cache={}, staticConfig={}", name, staticConfig);
        }
        // TODO: Better genericize this method in CacheFactoryStrategy so we can stop suppressing this warning
        @SuppressWarnings("unchecked") final ClusteredCache clusteredCache = new ClusteredCache(name, hazelcast.getMap(name));
        return clusteredCache;
    }

    @Override
    public void destroyCache(Cache cache) {
        if (cache instanceof CacheWrapper) {
            cache = ((CacheWrapper) cache).getWrappedCache();
        }

        final ClusteredCache clustered = (ClusteredCache) cache;
        clustered.destroy();
    }

    @Override
    public boolean isSeniorClusterMember() {
        if (clusterListener == null || !clusterListener.isClusterMember()) {
            return false;
        }
        return clusterListener.isSeniorClusterMember();
    }

    @Override
    public List<ClusterNodeInfo> getClusterNodesInfo() {
        return clusterListener == null ? Collections.emptyList() : clusterListener.getClusterNodesInfo();
    }

    @Override
    public int getMaxClusterNodes() {
        // No longer depends on license code so just return a big number
        return 10000;
    }

    @Override
    public byte[] getSeniorClusterMemberID() {
        if (cluster != null && !cluster.getMembers().isEmpty()) {
            final Member oldest = cluster.getMembers().iterator().next();
            return getNodeID(oldest).toByteArray();
        } else {
            return null;
        }
    }

    @Override
    public byte[] getClusterMemberID() {
        if (cluster != null) {
            return getNodeID(cluster.getLocalMember()).toByteArray();
        } else {
            return null;
        }
    }

    /**
     * Gets the pseudo-synchronized time from the cluster. While the cluster members may
     * have varying system times, this method is expected to return a timestamp that is
     * synchronized (or nearly so; best effort) across the cluster.
     *
     * @return Synchronized time for all cluster members
     */
    @Override
    public long getClusterTime() {
        return cluster == null ? System.currentTimeMillis() : cluster.getClusterTime();
    }

    /*
     * Execute the given task on the other (non-local) cluster members.
     * Note that this method does not provide the result set for the given
     * task, as the task is run asynchronously across the cluster.
     */
    @Override
    public void doClusterTask(final ClusterTask<?> task) {

        if (cluster == null) {
            return;
        }
        final Set<Member> members = new HashSet<>();
        final Member current = cluster.getLocalMember();
        for (final Member member : cluster.getMembers()) {
            if (!member.getUuid().equals(current.getUuid())) {
                members.add(member);
            }
        }


        if (!members.isEmpty()) {
            // Asynchronously execute the task on the other cluster members
            logger.debug("Executing asynchronous MultiTask: " + task.getClass().getName());
            checkForPluginClassLoader(task);
            hazelcast.getExecutorService(HAZELCAST_EXECUTOR_SERVICE_NAME.getValue()).submitToMembers(new CallableTask<>(task), members);
        } else {
            logger.debug("No cluster members selected for cluster task " + task.getClass().getName());
        }
    }

    /*
     * Execute the given task on the given cluster member.
     * Note that this method does not provide the result set for the given
     * task, as the task is run asynchronously across the cluster.
     */
    @Override
    public void doClusterTask(final ClusterTask<?> task, final byte[] nodeID) {
        if (cluster == null) {
            return;
        }
        final Member member = getMember(nodeID);
        // Check that the requested member was found
        if (member != null) {
            // Asynchronously execute the task on the target member
            logger.debug("Executing asynchronous DistributedTask: " + task.getClass().getName());
            checkForPluginClassLoader(task);
            hazelcast.getExecutorService(HAZELCAST_EXECUTOR_SERVICE_NAME.getValue()).submitToMember(new CallableTask<>(task), member);
        } else {
            final String msg = MessageFormat.format("Requested node {0} not found in cluster", new String(nodeID, StandardCharsets.UTF_8));
            logger.warn(msg);
            throw new IllegalArgumentException(msg);
        }
    }

    /*
     * Execute the given task on the designated cluster members.
     * Note that this method blocks for up to MAX_CLUSTER_EXECUTION_TIME
     * (seconds) per member until the task is run on all members.
     */
    @Override
    public <T> Collection<T> doSynchronousClusterTask(final ClusterTask<T> task, final boolean includeLocalMember) {
        if (cluster == null) {
            return Collections.emptyList();
        }
        final Set<Member> members = new HashSet<>();
        final Member current = cluster.getLocalMember();
        for (final Member member : cluster.getMembers()) {
            if (includeLocalMember || (!member.getUuid().equals(current.getUuid()))) {
                members.add(member);
            }
        }
        final Collection<T> result = new ArrayList<>();
        if (!members.isEmpty()) {
            // Asynchronously execute the task on the other cluster members
            try {
                logger.debug("Executing MultiTask: " + task.getClass().getName());
                checkForPluginClassLoader(task);
                final Map<Member, ? extends Future<T>> futures = hazelcast.getExecutorService(HAZELCAST_EXECUTOR_SERVICE_NAME.getValue()).submitToMembers(new CallableTask<>(task), members);
                long nanosLeft = TimeUnit.SECONDS.toNanos(MAX_CLUSTER_EXECUTION_TIME.getValue().getSeconds() * members.size());
                for (final Future<T> future : futures.values()) {
                    final long start = System.nanoTime();
                    result.add(future.get(nanosLeft, TimeUnit.NANOSECONDS));
                    nanosLeft = nanosLeft - (System.nanoTime() - start);
                }
            } catch (final TimeoutException te) {
                logger.error("Failed to execute cluster task within " + StringUtils.getFullElapsedTime(MAX_CLUSTER_EXECUTION_TIME.getValue()), te);
            } catch (final Exception e) {
                logger.error("Failed to execute cluster task", e);
            }
        } else {
            logger.debug("No cluster members selected for cluster task " + task.getClass().getName());
        }
        return result;
    }

    /*
     * Execute the given task on the designated cluster member.
     * Note that this method blocks for up to MAX_CLUSTER_EXECUTION_TIME
     * (seconds) until the task is run on the given member.
     */
    @Override
    public <T> T doSynchronousClusterTask(final ClusterTask<T> task, final byte[] nodeID) {
        if (cluster == null) {
            return null;
        }
        final Member member = getMember(nodeID);
        T result = null;
        // Check that the requested member was found
        if (member != null) {
            // Asynchronously execute the task on the target member
            logger.debug("Executing DistributedTask: " + task.getClass().getName());
            checkForPluginClassLoader(task);
            try {
                final Future<T> future = hazelcast.getExecutorService(HAZELCAST_EXECUTOR_SERVICE_NAME.getValue()).submitToMember(new CallableTask<>(task), member);
                result = future.get(MAX_CLUSTER_EXECUTION_TIME.getValue().getSeconds(), TimeUnit.SECONDS);
                logger.trace("DistributedTask result: {}", result);
            } catch (final TimeoutException te) {
                logger.error("Failed to execute cluster task within " + MAX_CLUSTER_EXECUTION_TIME + " seconds", te);
            } catch (final Exception e) {
                logger.error("Failed to execute cluster task", e);
            }
        } else {
            final String msg = MessageFormat.format("Requested node {0} not found in cluster", new String(nodeID, StandardCharsets.UTF_8));
            logger.warn(msg);
            throw new IllegalArgumentException(msg);
        }
        return result;
    }

    @Override
    public ClusterNodeInfo getClusterNodeInfo(final byte[] nodeID) {
        if (cluster == null) {
            return null;
        }
        ClusterNodeInfo result = null;
        final Member member = getMember(nodeID);
        if (member != null) {
            result = new HazelcastClusterNodeInfo(member, cluster.getClusterTime());
        }
        return result;
    }

    private Member getMember(final byte[] nodeID) {
        final NodeID memberToFind = NodeID.getInstance(nodeID);
        for (final Member member : cluster.getMembers()) {
            if (memberToFind.equals(getNodeID(member))) {
                return member;
            }
        }
        return null;
    }

    @Override
    public void updateCacheStats(final Map<String, Cache> caches) {
        if (!caches.isEmpty() && cluster != null) {
            // Create the cacheStats map if necessary.
            if (cacheStats == null) {
                cacheStats = hazelcast.getMap("opt-$cacheStats");
            }
            final String uid = getNodeID(cluster.getLocalMember()).toString();
            final Map<String, long[]> stats = new HashMap<>();
            for (final String cacheName : caches.keySet()) {
                final Cache cache = caches.get(cacheName);
                // The following information is published:
                // current size, max size, num elements, cache
                // hits, cache misses.
                final long[] info = new long[5];
                info[0] = cache.getLongCacheSize();
                info[1] = cache.getMaxCacheSize();
                info[2] = cache.size();
                info[3] = cache.getCacheHits();
                info[4] = cache.getCacheMisses();
                stats.put(cacheName, info);
            }
            // Publish message
            cacheStats.put(uid, stats);
        }
    }

    @Override
    public String getPluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public Lock getLock(final Object key, Cache cache) {
        if (cache instanceof CacheWrapper) {
            cache = ((CacheWrapper) cache).getWrappedCache();
        }
        // TODO: Update CacheFactoryStrategy so the signature is getLock(final Serializable key, Cache<Serializable, Serializable> cache)
        @SuppressWarnings("unchecked") final ClusterLock clusterLock = new ClusterLock((Serializable) key, (ClusteredCache<Serializable, ?>) cache);
        return clusterLock;
    }

    /**
     * ClusterTasks that are executed should not be provided by a plugin. These will cause issues related to class
     * loading when the providing plugin is reloaded. This method verifies if an instance of a task is
     * loaded by a plugin class loader, and logs a warning to the log files when it is. The amount of warnings logged is
     * limited by a time interval.
     *
     * @param o the instance for which to verify the class loader
     * @see <a href="https://github.com/igniterealtime/openfire-hazelcast-plugin/issues/74">Issue #74: Warn against usage of plugin-provided classes in Hazelcast</a>
     */
    protected <T extends ClusterTask<?>> void checkForPluginClassLoader(final T o) {
        if (o != null && o.getClass().getClassLoader() instanceof PluginClassLoader
            && !pluginClassLoaderWarnings.containsKey(o.getClass().getName()) )
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
            logger.warn("An instance of {} that is executed as a cluster task. This will cause issues when reloading " +
                    "the plugin that provides this class. The plugin implementation should be modified.",
                pluginName != null ? o.getClass() + " (provided by plugin " + pluginName + ")" : o.getClass());
            pluginClassLoaderWarnings.put(o.getClass().getName(), Instant.now()); // Note that this Instant is unused.
        }
    }

    private static class ClusterLock implements Lock {

        private final Serializable key;
        private final ClusteredCache<Serializable, ?> cache;

        ClusterLock(final Serializable key, final ClusteredCache<Serializable, ?> cache) {
            this.key = key;
            this.cache = cache;
        }

        @Override
        public void lock() {
            cache.lock(key, -1);
        }

        @Override
        public void lockInterruptibly() {
            cache.lock(key, -1);
        }

        @Override
        public boolean tryLock() {
            return cache.lock(key, 0);
        }

        @Override
        public boolean tryLock(final long time, final TimeUnit unit) {
            return cache.lock(key, unit.toMillis(time));
        }

        @Override
        public void unlock() {
            cache.unlock(key);
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CallableTask<V> implements Callable<V>, Serializable {
        private static final long serialVersionUID = -8761271979427214681L;
        private final ClusterTask<V> task;

        CallableTask(final ClusterTask<V> task) {
            this.task = task;
        }

        @Override
        public V call() {
            try {
                task.run();
                logger.trace("CallableTask[{}] result: {}", task.getClass().getName(), task.getResult());
                return task.getResult();
            } catch (final Exception e) {
                logger.error("Unexpected exception running CallableTask[{}]", task.getClass().getName(), e);
                throw e;
            }
        }
    }

    private enum State {
        stopped,
        starting,
        started
    }

    public static NodeID getNodeID(final Member member) {
        return NodeID.getInstance(member.getStringAttribute(HazelcastClusterNodeInfo.NODE_ID_ATTRIBUTE).getBytes(StandardCharsets.UTF_8));
    }

    static void fireLeftClusterAndWaitToComplete(final Duration timeout) {
        final Semaphore leftClusterSemaphore = new Semaphore(0);
        final ClusterEventListener clusterEventListener = new ClusterEventListener() {
            @Override
            public void joinedCluster() {
            }

            @Override
            public void joinedCluster(final byte[] bytes) {
            }

            @Override
            public void leftCluster() {
                leftClusterSemaphore.release();
            }

            @Override
            public void leftCluster(final byte[] bytes) {
            }

            @Override
            public void markedAsSeniorClusterMember() {
            }
        };
        try {
            // Add a listener at the ultimate end of the list of all listeners, to detect that left-cluster event handling
            // has been invoked for all before proceeding.
            ClusterManager.addListener(clusterEventListener, Integer.MAX_VALUE);
            logger.debug("Firing leftCluster() event");
            ClusterManager.fireLeftCluster();
            logger.debug("Waiting for leftCluster() event to be called [timeout={}]", StringUtils.getFullElapsedTime(timeout));
            if (!leftClusterSemaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warn("Timeout waiting for leftCluster() event to be called [timeout={}]", StringUtils.getFullElapsedTime(timeout));
            }
        } catch (final Exception e) {
            logger.error("Unexpected exception waiting for clustering to shut down", e);
        } finally {
            ClusterManager.removeListener(clusterEventListener);
        }
    }

}



