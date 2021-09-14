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

import com.hazelcast.core.Cluster;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleEvent.LifecycleState;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.cluster.ClusterNodeInfo;
import org.jivesoftware.openfire.cluster.NodeID;
import org.jivesoftware.openfire.plugin.util.cluster.HazelcastClusterNodeInfo;
import org.jivesoftware.openfire.session.ClientSessionInfo;
import org.jivesoftware.openfire.session.RemoteSessionLocator;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.jivesoftware.util.cache.CacheWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClusterListener reacts to membership changes in the cluster. It takes care of cleaning up the state
 * of the routing table and the sessions within it when a node which manages those sessions goes down.
 */
public class ClusterListener implements MembershipListener, LifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(ClusterListener.class);

    private static final int SESSION_INFO_CACHE_IDX = 3;

    /**
     * Caches stored in SessionManager
     */
    private final Cache<String, ClientSessionInfo> sessionInfoCache;

    private final Map<NodeID, Set<String>[]> nodeSessions = new ConcurrentHashMap<>();

    private boolean seniorClusterMember = false;

    private final Map<Cache<?,?>, EntryListener> entryListeners = new HashMap<>();
    
    private final Cluster cluster;
    private final Map<NodeID, ClusterNodeInfo> clusterNodesInfo = new ConcurrentHashMap<>();
    
    /**
     * Flag that indicates if the listener has done all clean up work when noticed that the
     * cluster has been stopped. This will force Openfire to wait until all clean
     * up (e.g. changing caches implementations) is done before destroying the plugin.
     */
    private boolean done = true;
    /**
     * Flag that indicates if we've joined a cluster or not
     */
    private boolean clusterMember = false;
    private boolean isSenior;

    ClusterListener(final Cluster cluster) {

        this.cluster = cluster;
        for (final Member member : cluster.getMembers()) {
            clusterNodesInfo.put(ClusteredCacheFactory.getNodeID(member),
                    new HazelcastClusterNodeInfo(member, cluster.getClusterTime()));
        }

        sessionInfoCache = CacheFactory.createCache(SessionManager.C2S_INFO_CACHE_NAME);
    }

    private void addEntryListener(final Cache<?, ?> cache, final EntryListener listener) {
        if (cache instanceof CacheWrapper) {
            final Cache wrapped = ((CacheWrapper)cache).getWrappedCache();
            if (wrapped instanceof ClusteredCache) {
                ((ClusteredCache)wrapped).addEntryListener(listener);
                // Keep track of the listener that we added to the cache
                entryListeners.put(cache, listener);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void simulateCacheInserts(final Cache<?, ?> cache) {
        final EntryListener<?,?> entryListener = entryListeners.get(cache);
        if (entryListener != null) {
            if (cache instanceof CacheWrapper) {
                final Cache wrapped = ((CacheWrapper) cache).getWrappedCache();
                if (wrapped instanceof ClusteredCache) {
                    final ClusteredCache clusteredCache = (ClusteredCache) wrapped;
                    for (final Map.Entry<?, ?> entry : cache.entrySet()) {
                        final EntryEvent event = new EntryEvent<>(
                            clusteredCache.map.getName(),
                            cluster.getLocalMember(),
                            EntryEventType.ADDED.getType(),
                            entry.getKey(),
                            null,
                            entry.getValue());
                        entryListener.entryAdded(event);
                    }
                }
            }
        }
    }

    Set<String> lookupJIDList(final NodeID nodeKey, final String cacheName) {
        Set<String>[] allLists = nodeSessions.get(nodeKey);
        if (allLists == null) {
            allLists = insertJIDList(nodeKey);
        }

        if (cacheName.equals(sessionInfoCache.getName())) {
            return allLists[SESSION_INFO_CACHE_IDX];
        }
        else {
            throw new IllegalArgumentException("Unknown cache name: " + cacheName);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String>[] insertJIDList(final NodeID nodeKey) {
        final Set<String>[] allLists =  new Set[] {
            new HashSet<String>(),
            new HashSet<String>(),
            new HashSet<String>(),
            new HashSet<String>(),
            new HashSet<String>(),
            new HashSet<String>(),
            new HashSet<String>()
        };
        nodeSessions.put(nodeKey, allLists);
        return allLists;
    }

    private boolean isDone() {
        return done;
    }


    /**
     * Executes close logic for each session hosted in the remote node that is
     * no longer available. This logic is similar to the close listeners used by
     * the {@link SessionManager}.<p>
     *
     * If the node that went down performed its own clean up logic then the other
     * cluster nodes will have the correct state. That means that this method
     * will not find any sessions to remove.<p>
     *
     * If this operation is too big and we are still in a cluster then we can
     * distribute the work in the cluster to go faster.
     *
     * @param nodeIdToCleanUp the nodeIdToCleanUp that identifies the node that is no longer available.
     */
    private void cleanupNode(final NodeID nodeIdToCleanUp) {

        logger.debug("Going to clean up node {}, which should result in route being removed from the routing table", nodeIdToCleanUp);

        // TODO Fork in another process and even ask other nodes to process work
        final RemoteSessionLocator sessionLocator = XMPPServer.getInstance().getRemoteSessionLocator();
        final SessionManager manager = XMPPServer.getInstance().getSessionManager();

        // TODO Consider removing each cached entry once processed instead of all at the end. Could be more error-prove.

        final Set<String> sessionInfos = lookupJIDList(nodeIdToCleanUp, sessionInfoCache.getName());
        for (final String fullJID : new ArrayList<>(sessionInfos)) {
            final JID offlineJID = new JID(fullJID);
            manager.removeSession(null, offlineJID, false, true);
            // TODO Fix anonymous true/false resolution
        }

        // TODO This also happens in leftCluster of sessionmanager
        final Set<String> sessionInfo = lookupJIDList(nodeIdToCleanUp, sessionInfoCache.getName());
        if (!sessionInfo.isEmpty()) {
            for (final String session : new ArrayList<>(sessionInfo)) {
                sessionInfoCache.remove(session);
                // Registered sessions will be removed
                // by the clean up of the session info cache
            }
        }

        nodeSessions.remove(nodeIdToCleanUp);
        // TODO Make sure that routing table has no entry referring to node that is gone
    }

    synchronized void joinCluster() {
        if (!isDone()) { // already joined
            return;
        }

        addEntryListener(sessionInfoCache, new CacheListener(this, sessionInfoCache.getName()));

        // Simulate insert events of existing cache content
        simulateCacheInserts(sessionInfoCache);

        // Trigger events
        clusterMember = true;
        seniorClusterMember = isSeniorClusterMember();

        ClusterManager.fireJoinedCluster(false);

        if (seniorClusterMember) {
            ClusterManager.fireMarkedAsSeniorClusterMember();
        }
        logger.info("Joined cluster. XMPPServer node={}, Hazelcast UUID={}, seniorClusterMember={}",
            new Object[]{ClusteredCacheFactory.getNodeID(cluster.getLocalMember()), cluster.getLocalMember().getUuid(), seniorClusterMember});
        done = false;
    }

    boolean isSeniorClusterMember() {
        // first cluster member is the oldest
        final Iterator<Member> members = cluster.getMembers().iterator();
        return members.next().getUuid().equals(cluster.getLocalMember().getUuid());
    }

    private synchronized void leaveCluster() {
        if (isDone()) { // not a cluster member
            return;
        }
        clusterMember = false;
        final boolean wasSeniorClusterMember = seniorClusterMember;
        seniorClusterMember = false;

        // Trigger event. Wait until the listeners have processed the event. Caches will be populated
        // again with local content.
        ClusterManager.fireLeftCluster();

        if (!XMPPServer.getInstance().isShuttingDown()) {
            // Remove traces of directed presences sent from local entities to handlers that no longer exist
            // At this point c2s sessions are gone from the routing table so we can identify expired sessions
            XMPPServer.getInstance().getPresenceUpdateHandler().removedExpiredPresences();
        }
        logger.info("Left cluster. XMPPServer node={}, Hazelcast UUID={}, wasSeniorClusterMember={}",
            new Object[]{ClusteredCacheFactory.getNodeID(cluster.getLocalMember()), cluster.getLocalMember().getUuid(), wasSeniorClusterMember});
        done = true;
    }

    @Override
    public void memberAdded(final MembershipEvent event) {
        logger.info("Received a Hazelcast memberAdded event {}", event);

        final boolean wasSenior = isSenior;
        isSenior = isSeniorClusterMember();
        // local member only
        final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
        if (event.getMember().localMember()) { // We left and re-joined the cluster
            joinCluster();
        } else {
            ///nodePresences.put(nodeID, new ConcurrentHashMap<>());
            if(wasSenior && !isSenior) {
                logger.warn("Recovering from split-brain; firing leftCluster()/joinedCluster() events");
                ClusteredCacheFactory.fireLeftClusterAndWaitToComplete(Duration.ofSeconds(30));
                logger.debug("Firing joinedCluster() event");
                ClusterManager.fireJoinedCluster(true);
            } else {
                // Trigger event that a new node has joined the cluster
                ClusterManager.fireJoinedCluster(nodeID.toByteArray(), true);
            }
        }
        clusterNodesInfo.put(nodeID,
                new HazelcastClusterNodeInfo(event.getMember(), cluster.getClusterTime()));
    }

    @Override
    public void memberRemoved(final MembershipEvent event) {
        logger.info("Received a Hazelcast memberRemoved event {}", event);

        isSenior = isSeniorClusterMember();
        final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());

        if (event.getMember().localMember()) {
            logger.info("Leaving cluster: " + nodeID);
            // This node may have realized that it got kicked out of the cluster
            leaveCluster();
        } else {
            // Trigger event that a node left the cluster
            ClusterManager.fireLeftCluster(nodeID.toByteArray());

            // Clean up directed presences sent from entities hosted in the leaving node to local entities
            // Clean up directed presences sent to entities hosted in the leaving node from local entities
//            cleanupDirectedPresences(nodeID);

            if (!seniorClusterMember && isSeniorClusterMember()) {
                seniorClusterMember = true;
                ClusterManager.fireMarkedAsSeniorClusterMember();
            }

            logger.debug("Going to wait a bit before cleaning up the node, so the routing table can update first");
            try {
                Thread.sleep(15000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            cleanupNode(nodeID);
            
            // Remove traces of directed presences sent from local entities to handlers that no longer exist.
            // At this point c2s sessions are gone from the routing table so we can identify expired sessions
            XMPPServer.getInstance().getPresenceUpdateHandler().removedExpiredPresences();
        }
        // Delete nodeID instance (release from memory)
        NodeID.deleteInstance(nodeID.toByteArray());
        clusterNodesInfo.remove(nodeID);
    }
    
    @SuppressWarnings("WeakerAccess")
    public List<ClusterNodeInfo> getClusterNodesInfo() {
        return new ArrayList<>(clusterNodesInfo.values());
    }

    @Override
    public void stateChanged(final LifecycleEvent event) {
        if (event.getState().equals(LifecycleState.SHUTDOWN)) {
            leaveCluster();
        } else if (event.getState().equals(LifecycleState.STARTED)) {
            joinCluster();
        }
    }

    @Override
    public void memberAttributeChanged(final MemberAttributeEvent event) {
        logger.info("Received a Hazelcast memberAttributeChanged event {}", event);
        isSenior = isSeniorClusterMember();
        final ClusterNodeInfo priorNodeInfo = clusterNodesInfo.get(ClusteredCacheFactory.getNodeID(event.getMember()));
        clusterNodesInfo.put(ClusteredCacheFactory.getNodeID(event.getMember()),
                new HazelcastClusterNodeInfo(event.getMember(), priorNodeInfo.getJoinedTime()));
    }

    boolean isClusterMember() {
        return clusterMember;
    }
}
