/*
 * Copyright (C) 2004-2009 Jive Software, 2022-2024 Ignite Realtime Foundation. All rights reserved.
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

package org.jivesoftware.openfire.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginListener;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.container.PluginManagerListener;
import org.jivesoftware.openfire.plugin.util.cache.ClusterExternalizableUtil;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hazelcast clustering plugin. This implementation is based upon
 * (and borrows heavily from) the original Openfire clustering plugin.
 * See this plugin's README file for more information.
 *
 * @author Tom Evans
 * @author Matt Tucker
 */
public class HazelcastPlugin implements Plugin {

    public static final String PLUGIN_NAME = "Hazelcast Plugin"; // Exact match to plugin.xml
    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastPlugin.class);
    private PluginListener pluginChangeListener;

    @Override
    public void initializePlugin(final PluginManager manager, final File pluginDirectory) {

        LOGGER.info("Waiting for other plugins to initialize before initializing clustering");
        manager.addPluginManagerListener(new PluginManagerListener() {
            @Override
            public void pluginsMonitored() {
                manager.removePluginManagerListener(this);
                initializeClustering(pluginDirectory);
            }
        });

        pluginChangeListener = new PluginListener() {
            @Override
            public void pluginCreated(String s, Plugin plugin)
            {}

            @Override
            public void pluginDestroyed(String s, Plugin plugin) {
                // Although it is more performant to only purge the definitions loaded by the class loader of the related
                // plugin, that implementation is a lot more complex (eg: what to do with parent/child plugins?), while
                // the performance gains are minimal in real-world scenarios. This simple approach has the benefit of being
                // a lot less error-prone.
                ClusterExternalizableUtil.purgeCachedClassDefinitions();
            }
        };
        manager.addPluginListener(pluginChangeListener);
    }

    private void initializeClustering(final File hazelcastPluginDirectory) {
        LOGGER.info("All plugins have initialized; initializing clustering");

        try {
            final Path pathToLocalHazelcastConfig = JiveGlobals.getHomePath().resolve("conf/hazelcast-local-config.xml");
            if (!Files.exists(pathToLocalHazelcastConfig)) {
                Files.copy(Paths.get(hazelcastPluginDirectory.getAbsolutePath(), "classes/hazelcast-local-config.template.xml"), pathToLocalHazelcastConfig);
            }
            ClusterManager.startup();
        } catch (final IOException e) {
            LOGGER.warn("Unable to create local Hazelcast configuration file from template; clustering will not start", e);
        }
    }

    @Override
    public void destroyPlugin() {
        // Shutdown is initiated by XMPPServer before unloading plugins
        if (!XMPPServer.getInstance().isShuttingDown()) {
            try {
                XMPPServer.getInstance().getPluginManager().removePluginListener(pluginChangeListener);
                ClusterExternalizableUtil.purgeCachedClassDefinitions();
            } catch (Throwable t) {
                LOGGER.warn("Unable to remove plugin change listener.", t);
            }

            ClusterManager.shutdown();
        }
    }

}
