package io.roastedroot.proxywasm.jaxrs;

import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.plugin.Plugin;
import io.roastedroot.proxywasm.plugin.PluginFactory;
import io.roastedroot.proxywasm.plugin.Pool;
import io.roastedroot.proxywasm.plugin.ServerAdaptor;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.stream.Collectors;

public abstract class AbstractWasmPluginFeature implements DynamicFeature {

    private final HashMap<String, Pool> pluginPools = new HashMap<>();

    public void init(Iterable<PluginFactory> factories, ServerAdaptor serverAdaptor)
            throws StartException {

        if (!pluginPools.isEmpty()) {
            return;
        }

        for (var factory : factories) {
            Plugin plugin = factory.create();
            String name = plugin.name();
            if (this.pluginPools.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate wasm plugin name: " + name);
            }
            Pool pool =
                    plugin.isShared()
                            ? new Pool.SharedPlugin(serverAdaptor, plugin)
                            : new Pool.PluginPerRequest(serverAdaptor, factory, plugin);
            this.pluginPools.put(name, pool);
        }
    }

    public void destroy() {
        for (var pool : pluginPools.values()) {
            pool.close();
        }
        pluginPools.clear();
    }

    public Collection<Pool> getPluginPools() {
        return pluginPools.values();
    }

    public Pool pool(String name) {
        return pluginPools.get(name);
    }

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {

        var resourceMethod = resourceInfo.getResourceMethod();
        if (resourceMethod != null) {
            WasmPlugin pluignNameAnnotation = resourceMethod.getAnnotation(WasmPlugin.class);
            if (pluignNameAnnotation == null) {
                // If no annotation on method, check the class level
                pluignNameAnnotation =
                        resourceInfo.getResourceClass().getAnnotation(WasmPlugin.class);
            }
            if (pluignNameAnnotation != null) {
                var pools =
                        Arrays.stream(pluignNameAnnotation.value())
                                .map(
                                        (name) -> {
                                            Pool pool = pluginPools.get(name);
                                            if (pool != null) {
                                                return pool;
                                            } else {
                                                throw new IllegalArgumentException(
                                                        "Wasm plugin not found: "
                                                                + name
                                                                + " for resource: "
                                                                + resourceInfo
                                                                        .getResourceClass()
                                                                        .getName()
                                                                + "."
                                                                + resourceMethod.getName());
                                            }
                                        })
                                .collect(Collectors.toList());
                context.register(new WasmPluginFilter(pools));
            }
        }
    }
}
