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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginClassLoader;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.plugin.HazelcastPlugin;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class loader to be used by Openfire to load classes that live in the Hazelcast plugin,
 * the Openfire core and also classes defined in other plugins. With this new class loader
 * plugins can now make use of hazelcast.
 * <p>
 * However, there is a catch with this class loader. Plugins that define the same class name
 * (i.e. package and class name) will have a problem if they try to send that class through
 * the cluster. Hazelcast will deserialize the class and will use the first class definition
 * found in the list of plugins.
 * </p>
 * The sequence of search for this class loader is first check the hazelcast plugin that
 * includes checking the Openfire core. If not found then try with the other plugins.
 *
 * @author Tom Evans
 * @author Gaston Dombiak
 */
public class ClusterClassLoader extends ClassLoader {

    private static Logger logger = LoggerFactory.getLogger(ClusterClassLoader.class);

    private static final SystemProperty<String> HAZELCAST_CONFIG_DIR = SystemProperty.Builder.ofType(String.class)
        .setKey("hazelcast.config.xml.directory")
        .setDefaultValue(JiveGlobals.getHomeDirectory() + "/conf")
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();

    private static final SystemProperty<Duration> CLASS_CACHE_DURATION = SystemProperty.Builder.ofType(Duration.class)
        .setKey("hazelcast.cache.class.duration")
        .setChronoUnit(ChronoUnit.MILLIS)
        .setDefaultValue(Duration.ofSeconds(5))
        .setDynamic(false)
        .setPlugin(HazelcastPlugin.PLUGIN_NAME)
        .build();

    private static final PluginManager pluginManager = XMPPServer.getInstance().getPluginManager();

    private final PluginClassLoader hazelcastClassloader;

    // TODO: unload classes provided by plugins that are unloaded. Until then, keep the expiry very short, as to evict classes soon after unloading a plugin.
    private static final Cache<String, Class<?>> CLASS_CACHE = Caffeine.newBuilder()
        .expireAfterWrite(CLASS_CACHE_DURATION.getValue())
        .build();

    ClusterClassLoader() {
        Plugin plugin = pluginManager.getPluginByName(HazelcastPlugin.PLUGIN_NAME)
            .orElseThrow(() -> new IllegalStateException("Unable to find the Hazelcast plugin - name=" + HazelcastPlugin.PLUGIN_NAME));
        hazelcastClassloader = pluginManager.getPluginClassloader(plugin);

        // this is meant to allow loading configuration files from outside the plugin JAR file
        File confFolder = new File(HAZELCAST_CONFIG_DIR.getValue());
        try {
            logger.debug("Adding conf folder {}", confFolder);
            hazelcastClassloader.addURLFile(confFolder.toURI().toURL());
        } catch (MalformedURLException e) {
            logger.error("Error adding folder {} to classpath {}", HAZELCAST_CONFIG_DIR.getValue(), e.getMessage());
        }
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException {
        final Class<?> result = CLASS_CACHE.get(name, this::_loadClass);
        if (result == null) {
            throw new ClassNotFoundException(name);
        }
        return result;
    }

    protected Class<?> _loadClass(String name) {
        return getPluginClassLoaders()
            .map(classLoader -> {
                try {
                    return classLoader.loadClass(name);
                } catch (final ClassNotFoundException ignored) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns a stream of plugin class loaders. The order of the stream is;
     * <ol>
     *     <li>The Hazelcast plugin class loader - which also includes the Openfire class loader too.
     *     This is first as, in general, most classes will belong to the Openfire class loader</li>
     *     <li>All the other plugins, in the order determined by the plugin manager,
     *     excluding the admin and Hazelcast plugins.</li>
     * </ol>
     *
     * @return a stream of class loaders
     */
    private Stream<ClassLoader> getPluginClassLoaders() {
        return Stream.concat(Stream.of(hazelcastClassloader),
            pluginManager.getMetadataExtractedPlugins().values()
            .stream()
            // Filter out the admin and Hazelcast plugins
            .filter(pluginMetadata -> !pluginMetadata.getCanonicalName().equals("admin") &&
                !pluginMetadata.getName().equals(HazelcastPlugin.PLUGIN_NAME))
            .map(pluginMetadata -> pluginManager.getPluginByName(pluginMetadata.getName()))
            .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
            .map(pluginManager::getPluginClassloader));
    }

    public URL getResource(String name) {
        return getPluginClassLoaders()
            .map(pluginClassLoader -> pluginClassLoader.getResource(name))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    public Enumeration<URL> getResources(String name) {
        return getPluginClassLoaders()
            .map(pluginClassLoader -> {
                try {
                    return pluginClassLoader.getResources(name);
                } catch (final IOException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .filter(enumeration -> !enumeration.hasMoreElements())
            .findFirst()
            .orElseGet(Collections::emptyEnumeration);
    }
}
