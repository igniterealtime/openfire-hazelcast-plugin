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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;

import org.jivesoftware.openfire.PacketException;
import org.jivesoftware.openfire.RoutingTable;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.StreamID;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.cluster.ClusterNodeInfo;
import org.jivesoftware.openfire.cluster.NodeID;
import org.jivesoftware.openfire.handler.DirectedPresence;
import org.jivesoftware.openfire.handler.PresenceUpdateHandler;
import org.jivesoftware.openfire.plugin.util.cluster.HazelcastClusterNodeInfo;
import org.jivesoftware.openfire.session.ClientSessionInfo;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.openfire.session.IncomingServerSession;
import org.jivesoftware.openfire.session.RemoteSessionLocator;
import org.jivesoftware.openfire.spi.BasicStreamIDFactory;
import org.jivesoftware.openfire.spi.ClientRoute;
import org.jivesoftware.openfire.spi.RoutingTableImpl;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.jivesoftware.util.cache.CacheWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

import com.hazelcast.core.Cluster;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleEvent.LifecycleState;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.core.MapEvent;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;

/**
 * ClusterListener reacts to membership changes in the cluster. It takes care of cleaning up the state
 * of the routing table and the sessions within it when a node which manages those sessions goes down.
 */
public class ClusterListener implements MembershipListener, LifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(ClusterListener.class);

    private static final int C2S_CACHE_IDX = 0;
    private static final int ANONYMOUS_C2S_CACHE_IDX = 1;
    private static final int COMPONENT_CACHE_IDX= 2;

    private static final int SESSION_INFO_CACHE_IDX = 3;
    private static final int COMPONENT_SESSION_CACHE_IDX = 4;
    private static final int CM_CACHE_IDX = 5;
    private static final int ISS_CACHE_IDX = 6;

    /**
     * Caches stored in RoutingTable
     */
    private final Cache<DomainPair, byte[]> S2SCache;
    private final Cache<String, HashSet<NodeID>> componentsCache;

    /**
     * Caches stored in SessionManager
     */
    private final Cache<String, ClientSessionInfo> sessionInfoCache;
    private final Cache<String, byte[]> componentSessionsCache;
    private final Cache<String, byte[]> multiplexerSessionsCache;
    private final Cache<String, byte[]> incomingServerSessionsCache;

    /**
     * Caches stored in PresenceUpdateHandler
     */
    private final Cache<String, ConcurrentLinkedQueue<DirectedPresence>> directedPresencesCache;

    private final Map<NodeID, Set<String>[]> nodeSessions = new ConcurrentHashMap<>();
    private final Map<NodeID, Set<DomainPair>> nodeRoutes = new ConcurrentHashMap<>();
    private final Map<NodeID, Map<String, Collection<String>>> nodePresences = new ConcurrentHashMap<>();
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

        C2SCache = CacheFactory.createCache(RoutingTableImpl.C2S_CACHE_NAME);
        anonymousC2SCache = CacheFactory.createCache(RoutingTableImpl.ANONYMOUS_C2S_CACHE_NAME);
        S2SCache = CacheFactory.createCache(RoutingTableImpl.S2S_CACHE_NAME);
        componentsCache = CacheFactory.createCache(RoutingTableImpl.COMPONENT_CACHE_NAME);

        sessionInfoCache = CacheFactory.createCache(SessionManager.C2S_INFO_CACHE_NAME);
        componentSessionsCache = CacheFactory.createCache(SessionManager.COMPONENT_SESSION_CACHE_NAME);
        multiplexerSessionsCache = CacheFactory.createCache(SessionManager.CM_CACHE_NAME);
        incomingServerSessionsCache = CacheFactory.createCache(SessionManager.ISS_CACHE_NAME);

        directedPresencesCache = CacheFactory.createCache(PresenceUpdateHandler.PRESENCE_CACHE_NAME);

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

        if (cacheName.equals(C2SCache.getName())) {
            return allLists[C2S_CACHE_IDX];
        }
        else if (cacheName.equals(anonymousC2SCache.getName())) {
            return allLists[ANONYMOUS_C2S_CACHE_IDX];
        }
        else if (cacheName.equals(componentsCache.getName())) {
            return allLists[COMPONENT_CACHE_IDX];
        }
        else if (cacheName.equals(sessionInfoCache.getName())) {
            return allLists[SESSION_INFO_CACHE_IDX];
        }
        else if (cacheName.equals(componentSessionsCache.getName())) {
            return allLists[COMPONENT_SESSION_CACHE_IDX];
        }
        else if (cacheName.equals(multiplexerSessionsCache.getName())) {
            return allLists[CM_CACHE_IDX];
        }
        else if (cacheName.equals(incomingServerSessionsCache.getName())) {
            return allLists[ISS_CACHE_IDX];
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

    private void cleanupDirectedPresences(final NodeID nodeID) {
        // Remove traces of directed presences sent from node that is gone to entities hosted in this JVM
        final Map<String, Collection<String>> senders = nodePresences.remove(nodeID);
        if (senders != null) {
            for (final Map.Entry<String, Collection<String>> entry : senders.entrySet()) {
                final String sender = entry.getKey();
                final Collection<String> receivers = entry.getValue();
                for (final String receiver : receivers) {
                    try {
                        final Presence presence = new Presence(Presence.Type.unavailable);
                        presence.setFrom(sender);
                        presence.setTo(receiver);
                        XMPPServer.getInstance().getPresenceRouter().route(presence);
                    }
                    catch (final PacketException e) {
                        logger.error("Failed to cleanup directed presences", e);
                    }
                }
            }
        }
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
     * @param key the key that identifies the node that is no longer available.
     */
    private void cleanupNode(final NodeID key) {
        // TODO Fork in another process and even ask other nodes to process work
        final RoutingTable routingTable = XMPPServer.getInstance().getRoutingTable();
        final RemoteSessionLocator sessionLocator = XMPPServer.getInstance().getRemoteSessionLocator();
        final SessionManager manager = XMPPServer.getInstance().getSessionManager();

        // TODO Consider removing each cached entry once processed instead of all at the end. Could be more error-prove.

        final Set<String> registeredUsers = lookupJIDList(key, C2SCache.getName());
        if (!registeredUsers.isEmpty()) {
            for (final String fullJID : new ArrayList<>(registeredUsers)) {
                final JID offlineJID = new JID(fullJID);
                manager.removeSession(null, offlineJID, false, true);
            }
        }

        final Set<String> anonymousUsers = lookupJIDList(key, anonymousC2SCache.getName());
        if (!anonymousUsers.isEmpty()) {
            for (final String fullJID : new ArrayList<>(anonymousUsers)) {
                final JID offlineJID = new JID(fullJID);
                manager.removeSession(null, offlineJID, true, true);
            }
        }

        // Remove outgoing server sessions hosted in node that left the cluster
        final Set<DomainPair> remoteServers = nodeRoutes.get(key);
        if (remoteServers!=null) {
            for (final DomainPair domainPair : remoteServers) {
                routingTable.removeServerRoute(domainPair);
            }
        }
        nodeRoutes.remove(key);


        final Set<String> components = lookupJIDList(key, componentsCache.getName());
        if (!components.isEmpty()) {
            for (final String address : new ArrayList<>(components)) {
                final Lock lock = CacheFactory.getLock(address, componentsCache);
                try {
                    lock.lock();
                    final HashSet<NodeID> nodes = componentsCache.get(address);
                    if (nodes != null) {
                        nodes.remove(key);
                        if (nodes.isEmpty()) {
                            componentsCache.remove(address);
                        }
                        else {
                            componentsCache.put(address, nodes);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        final Set<String> sessionInfo = lookupJIDList(key, sessionInfoCache.getName());
        if (!sessionInfo.isEmpty()) {
            for (final String session : new ArrayList<>(sessionInfo)) {
                sessionInfoCache.remove(session);
                // Registered sessions will be removed
                // by the clean up of the session info cache
            }
        }

        final Set<String> componentSessions = lookupJIDList(key, componentSessionsCache.getName());
        if (!componentSessions.isEmpty()) {
            for (final String domain : new ArrayList<>(componentSessions)) {
                componentSessionsCache.remove(domain);
                // Registered subdomains of external component will be removed
                // by the clean up of the component cache
            }
        }

        final Set<String> multiplexers = lookupJIDList(key, multiplexerSessionsCache.getName());
        if (!multiplexers.isEmpty()) {
            for (final String fullJID : new ArrayList<>(multiplexers)) {
                multiplexerSessionsCache.remove(fullJID);
                // c2s connections connected to node that went down will be cleaned up
                // by the c2s logic above. If the CM went down and the node is up then
                // connections will be cleaned up as usual
            }
        }

        final Set<String> incomingSessions = lookupJIDList(key, incomingServerSessionsCache.getName());
        if (!incomingSessions.isEmpty()) {
            for (final String streamIDValue : new ArrayList<>(incomingSessions)) {
                final StreamID streamID = BasicStreamIDFactory.createStreamID( streamIDValue );
                final IncomingServerSession session = sessionLocator.getIncomingServerSession(key.toByteArray(), streamID);
                // Remove all the hostnames that were registered for this server session
                for (final String hostname : session.getValidatedDomains()) {
                    manager.unregisterIncomingServerSession(hostname, session);
                }
            }
        }
        nodeSessions.remove(key);
        // TODO Make sure that routing table has no entry referring to node that is gone
    }

    /**
     * Simulate an unavailable presence for sessions that were being hosted in other
     * cluster nodes. This method should be used ONLY when this JVM left the cluster.
     *
     * @param key the key that identifies the node that is no longer available.
     */
    private void cleanupPresences(final NodeID key) {
        final Set<String> registeredUsers = lookupJIDList(key, C2SCache.getName());
        if (!registeredUsers.isEmpty()) {
            for (final String fullJID : new ArrayList<>(registeredUsers)) {
                final JID offlineJID = new JID(fullJID);
                try {
                    final Presence presence = new Presence(Presence.Type.unavailable);
                    presence.setFrom(offlineJID);
                    XMPPServer.getInstance().getPresenceRouter().route(presence);
                }
                catch (final PacketException e) {
                    logger.error("Failed to cleanup user presence", e);
                }
            }
        }

        final Set<String> anonymousUsers = lookupJIDList(key, anonymousC2SCache.getName());
        if (!anonymousUsers.isEmpty()) {
            for (final String fullJID : new ArrayList<>(anonymousUsers)) {
                final JID offlineJID = new JID(fullJID);
                try {
                    final Presence presence = new Presence(Presence.Type.unavailable);
                    presence.setFrom(offlineJID);
                    XMPPServer.getInstance().getPresenceRouter().route(presence);
                }
                catch (final PacketException e) {
                    logger.error("Failed to cleanp anonymous presence", e);
                }
            }
        }

        nodeSessions.remove(key);
    }

    /**
     * EntryListener implementation tracks events for caches of c2s sessions.
     */
    private class DirectedPresenceListener implements EntryListener<String, Collection<DirectedPresence>> {

        @Override
        public void entryAdded(final EntryEvent<String, Collection<DirectedPresence>> event) {
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            // Ignore events originated from this JVM
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                // Check if the directed presence was sent to an entity hosted by this JVM
                final RoutingTable routingTable = XMPPServer.getInstance().getRoutingTable();
                final String sender = event.getKey();
                final Collection<String> handlers = new HashSet<>();
                for (final JID handler : getHandlers(event)) {
                    if (routingTable.isLocalRoute(handler)) {
                        // Keep track of the remote sender and local handler that got the directed presence
                        handlers.addAll(getReceivers(event, handler));
                    }
                }
                if (!handlers.isEmpty()) {
                    Map<String, Collection<String>> senders = nodePresences.get(nodeID);
                    if (senders == null) {
                        senders = new ConcurrentHashMap<>();
                        nodePresences.put(nodeID, senders);
                    }
                    senders.put(sender, handlers);
                }
            }
        }

        @Override
        public void entryUpdated(final EntryEvent<String, Collection<DirectedPresence>> event) {
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            // Ignore events originated from this JVM
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                // Check if the directed presence was sent to an entity hosted by this JVM
                final RoutingTable routingTable = XMPPServer.getInstance().getRoutingTable();
                final String sender = event.getKey();
                final Collection<String> handlers = new HashSet<>();
                for (final JID handler : getHandlers(event)) {
                    if (routingTable.isLocalRoute(handler)) {
                        // Keep track of the remote sender and local handler that got the directed presence
                        handlers.addAll(getReceivers(event, handler));
                    }
                }
                Map<String, Collection<String>> senders = nodePresences.get(nodeID);
                if (senders == null) {
                    senders = new ConcurrentHashMap<>();
                    nodePresences.put(nodeID, senders);
                }
                if (!handlers.isEmpty()) {
                    senders.put(sender, handlers);
                }
                else {
                    // Remove any traces of the sender since no directed presence was sent to this JVM
                    senders.remove(sender);
                }
            }
        }

        @Override
        public void entryRemoved(final EntryEvent<String, Collection<DirectedPresence>> event) {
            if (event == null || (event.getValue() == null && event.getOldValue() == null)) {
                // Nothing to remove
                return;
            }
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                final String sender = event.getKey();
                nodePresences.get(nodeID).remove(sender);
            }
        }

        Collection<JID> getHandlers(final EntryEvent<String, Collection<DirectedPresence>> event) {
            final Collection<DirectedPresence> value = event.getValue();
            final Collection<JID> answer = new ArrayList<>();
            if (value != null) {
                for (final DirectedPresence directedPresence : value) {
                    answer.add(directedPresence.getHandler());
                }
            }
            return answer;
        }

        Set<String> getReceivers(final EntryEvent<String, Collection<DirectedPresence>> event, final JID handler) {
            final Collection<DirectedPresence> value = event.getValue();
            for (final DirectedPresence directedPresence : value) {
                if (directedPresence.getHandler().equals(handler)) {
                    return directedPresence.getReceivers();
                }
            }
            return Collections.emptySet();
        }

        @Override
        public void entryEvicted(final EntryEvent<String, Collection<DirectedPresence>> event) {
            entryRemoved(event);
        }

        private void mapClearedOrEvicted(final MapEvent event) {
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            // ignore events which were triggered by this node
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                nodePresences.get(nodeID).clear();
            }
        }

        @Override
        public void mapEvicted(final MapEvent event) {
            mapClearedOrEvicted(event);
        }

        @Override
        public void mapCleared(final MapEvent event) {
            mapClearedOrEvicted(event);
        }
    }

    /**
     * EntryListener implementation tracks events for caches of internal/external components.
     */
    private class ComponentCacheListener implements EntryListener<String, Set<NodeID>> {

        @Override
        public void entryAdded(final EntryEvent<String, Set<NodeID>> event) {
            final Set<NodeID> newValue = event.getValue();
            if (newValue != null) {
                for (final NodeID nodeID : newValue) {
                    //ignore items which this node has added
                    if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                        final Set<String> sessionJIDS = lookupJIDList(nodeID, componentsCache.getName());
                        sessionJIDS.add(event.getKey());
                    }
                }
            }
        }

        @Override
        public void entryUpdated(final EntryEvent<String, Set<NodeID>> event) {
            // Remove any trace to the component that was added/deleted to some node
            final String domain = event.getKey();
            for (final Map.Entry<NodeID, Set<String>[]> entry : nodeSessions.entrySet()) {
                // Get components hosted in this node
                final Set<String> nodeComponents = entry.getValue()[COMPONENT_CACHE_IDX];
                nodeComponents.remove(domain);
            }
            // Trace nodes hosting the component
            entryAdded(event);
        }

        @Override
        public void entryRemoved(final EntryEvent<String, Set<NodeID>> event) {
            final Set<NodeID> newValue = event.getValue();
            if (newValue != null) {
                for (final NodeID nodeID : newValue) {
                    //ignore items which this node has added
                    if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                        final Set<String> sessionJIDS = lookupJIDList(nodeID, componentsCache.getName());
                        sessionJIDS.remove(event.getKey());
                    }
                }
            }
        }

        @Override
        public void entryEvicted(final EntryEvent<String, Set<NodeID>> event) {
            entryRemoved(event);
        }

        private void mapClearedOrEvicted(final MapEvent event) {
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            // ignore events which were triggered by this node
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                final Set<String> sessionJIDs = lookupJIDList(nodeID, componentsCache.getName());
                sessionJIDs.clear();
            }
        }

        @Override
        public void mapEvicted(final MapEvent event) {
            mapClearedOrEvicted(event);
        }

        @Override
        public void mapCleared(final MapEvent event) {
            mapClearedOrEvicted(event);
        }
    }

    synchronized void joinCluster() {
        if (!isDone()) { // already joined
            return;
        }
        addEntryListener(C2SCache, new CacheListener(this, C2SCache.getName()));
        addEntryListener(anonymousC2SCache, new CacheListener(this, anonymousC2SCache.getName()));
        addEntryListener(S2SCache, new S2SCacheListener());
        addEntryListener(componentsCache, new ComponentCacheListener());

        addEntryListener(sessionInfoCache, new CacheListener(this, sessionInfoCache.getName()));
        addEntryListener(componentSessionsCache, new CacheListener(this, componentSessionsCache.getName()));
        addEntryListener(multiplexerSessionsCache, new CacheListener(this, multiplexerSessionsCache.getName()));
        addEntryListener(incomingServerSessionsCache, new CacheListener(this, incomingServerSessionsCache.getName()));

        addEntryListener(directedPresencesCache, new DirectedPresenceListener());

        // Simulate insert events of existing cache content
        simulateCacheInserts(C2SCache);
        simulateCacheInserts(anonymousC2SCache);
        simulateCacheInserts(S2SCache);
        simulateCacheInserts(componentsCache);
        simulateCacheInserts(sessionInfoCache);
        simulateCacheInserts(componentSessionsCache);
        simulateCacheInserts(multiplexerSessionsCache);
        simulateCacheInserts(incomingServerSessionsCache);
        simulateCacheInserts(directedPresencesCache);

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
        // Clean up all traces. This will set all remote sessions as unavailable
        final List<NodeID> nodeIDs = new ArrayList<>(nodeSessions.keySet());

        // Trigger event. Wait until the listeners have processed the event. Caches will be populated
        // again with local content.
        ClusterManager.fireLeftCluster();

        if (!XMPPServer.getInstance().isShuttingDown()) {
            for (final NodeID key : nodeIDs) {
                // Clean up directed presences sent from entities hosted in the leaving node to local entities
                // Clean up directed presences sent to entities hosted in the leaving node from local entities
                cleanupDirectedPresences(key);
                // Clean up no longer valid sessions
                cleanupPresences(key);
            }
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
        final boolean wasSenior = isSenior;
        isSenior = isSeniorClusterMember();
        // local member only
        final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
        if (event.getMember().localMember()) { // We left and re-joined the cluster
            joinCluster();
        } else {
            nodePresences.put(nodeID, new ConcurrentHashMap<>());
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
            cleanupDirectedPresences(nodeID);

            if (!seniorClusterMember && isSeniorClusterMember()) {
                seniorClusterMember = true;
                ClusterManager.fireMarkedAsSeniorClusterMember();
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
        isSenior = isSeniorClusterMember();
        final ClusterNodeInfo priorNodeInfo = clusterNodesInfo.get(ClusteredCacheFactory.getNodeID(event.getMember()));
        clusterNodesInfo.put(ClusteredCacheFactory.getNodeID(event.getMember()),
                new HazelcastClusterNodeInfo(event.getMember(), priorNodeInfo.getJoinedTime()));
    }

    class S2SCacheListener implements EntryListener<DomainPair, byte[]> {
        S2SCacheListener() {
        }

        @Override
        public void entryAdded(final EntryEvent<DomainPair, byte[]> event) {
            handleEntryEvent(event, false);
        }

        @Override
        public void entryUpdated(final EntryEvent<DomainPair, byte[]> event) {
            handleEntryEvent(event, false);
        }

        @Override
        public void entryRemoved(final EntryEvent<DomainPair, byte[]> event) {
            handleEntryEvent(event, true);
        }

        @Override
        public void entryEvicted(final EntryEvent<DomainPair, byte[]> event) {
            handleEntryEvent(event, true);
        }

        private void handleEntryEvent(final EntryEvent<DomainPair, byte[]> event, final boolean removal) {
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            // ignore events which were triggered by this node
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                Set<DomainPair> sessionJIDS = nodeRoutes.get(nodeID);
                if (sessionJIDS == null) {
                    sessionJIDS = new HashSet<>();
                }
                if (removal) {
                    sessionJIDS.remove(event.getKey());
                } else {
                    sessionJIDS.add(event.getKey());
                }
            }
        }

        private void handleMapEvent(final MapEvent event) {
            final NodeID nodeID = ClusteredCacheFactory.getNodeID(event.getMember());
            // ignore events which were triggered by this node
            if (!XMPPServer.getInstance().getNodeID().equals(nodeID)) {
                final Set<DomainPair> sessionJIDS = nodeRoutes.get(nodeID);
                if (sessionJIDS != null) {
                    sessionJIDS.clear();
                }
            }
        }

        @Override
        public void mapCleared(final MapEvent event) {
            handleMapEvent(event);
        }

        @Override
        public void mapEvicted(final MapEvent event) {
            handleMapEvent(event);
        }

    }

    boolean isClusterMember() {
        return clusterMember;
    }
}
