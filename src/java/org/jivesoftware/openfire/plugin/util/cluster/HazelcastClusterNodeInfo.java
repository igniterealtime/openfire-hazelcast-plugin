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

package org.jivesoftware.openfire.plugin.util.cluster;

import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.cluster.ClusterNodeInfo;
import org.jivesoftware.openfire.cluster.NodeID;
import org.jivesoftware.openfire.plugin.util.cache.ClusteredCacheFactory;

import com.hazelcast.core.Member;

/**
 * Cluster Node information as provided by Hazelcast.
 *
 * @author Tom Evans
 * @author Gaston Dombiak
 */
public class HazelcastClusterNodeInfo implements ClusterNodeInfo {

    public static final String HOST_NAME_ATTRIBUTE = "hostname";
    public static final String NODE_ID_ATTRIBUTE = "node-id";
    private final String hostname;
    private final NodeID nodeID;
    private final long joinedTime;
    private final boolean seniorMember;

    public HazelcastClusterNodeInfo(final Member member, final long joinedTime) {
        this.hostname = member.getStringAttribute(HOST_NAME_ATTRIBUTE) + " (" + member.getSocketAddress().getHostString() + ")";
        this.nodeID = ClusteredCacheFactory.getNodeID(member);
        this.joinedTime = joinedTime;
        this.seniorMember = ClusterManager.getSeniorClusterMember().equals(nodeID);
    }

    public String getHostName() {
        return hostname;
    }

    public NodeID getNodeID() {
        return nodeID;
    }

    public long getJoinedTime() {
        return joinedTime;
    }

    public boolean isSeniorMember() {
        return seniorMember;
    }
}
