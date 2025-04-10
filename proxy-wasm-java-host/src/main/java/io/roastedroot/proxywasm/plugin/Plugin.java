package io.roastedroot.proxywasm.plugin;

import static io.roastedroot.proxywasm.Helpers.bytes;
import static io.roastedroot.proxywasm.WellKnownHeaders.AUTHORITY;
import static io.roastedroot.proxywasm.WellKnownHeaders.METHOD;
import static io.roastedroot.proxywasm.WellKnownHeaders.PATH;
import static io.roastedroot.proxywasm.WellKnownHeaders.SCHEME;
import static io.roastedroot.proxywasm.WellKnownProperties.PLUGIN_NAME;
import static io.roastedroot.proxywasm.WellKnownProperties.PLUGIN_VM_ID;

import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.proxywasm.ArrayProxyMap;
import io.roastedroot.proxywasm.ChainedHandler;
import io.roastedroot.proxywasm.ForeignFunction;
import io.roastedroot.proxywasm.Handler;
import io.roastedroot.proxywasm.LogHandler;
import io.roastedroot.proxywasm.LogLevel;
import io.roastedroot.proxywasm.MetricType;
import io.roastedroot.proxywasm.MetricsHandler;
import io.roastedroot.proxywasm.ProxyMap;
import io.roastedroot.proxywasm.ProxyWasm;
import io.roastedroot.proxywasm.QueueName;
import io.roastedroot.proxywasm.SharedData;
import io.roastedroot.proxywasm.SharedDataHandler;
import io.roastedroot.proxywasm.SharedQueueHandler;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.WasmException;
import io.roastedroot.proxywasm.WasmResult;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public final class Plugin {

    private final ReentrantLock lock = new ReentrantLock();
    final ProxyWasm wasm;
    ServerAdaptor serverAdaptor;
    private final boolean shared;
    private final String name;

    private final MetricsHandler metricsHandler;
    private final SharedQueueHandler sharedQueueHandler;
    private final SharedDataHandler sharedDataHandler;

    private Plugin(Builder builder, ProxyWasm proxyWasm) throws StartException {
        Objects.requireNonNull(proxyWasm);
        this.name = Objects.requireNonNullElse(builder.name, "default");
        this.shared = builder.shared;
        this.foreignFunctions = builder.foreignFunctions;
        this.upstreams = builder.upstreams;
        this.strictUpstreams = builder.strictUpstreams;
        this.minTickPeriodMilliseconds = builder.minTickPeriodMilliseconds;
        this.vmConfig = builder.vmConfig;
        this.pluginConfig = builder.pluginConfig;
        this.logger = Objects.requireNonNullElse(builder.logger, LogHandler.DEFAULT);
        ;
        this.metricsHandler =
                Objects.requireNonNullElse(builder.metricsHandler, MetricsHandler.DEFAULT);
        this.sharedQueueHandler =
                Objects.requireNonNullElse(builder.sharedQueueHandler, SharedQueueHandler.DEFAULT);
        this.sharedDataHandler =
                Objects.requireNonNullElse(builder.sharedDataHandler, SharedDataHandler.DEFAULT);

        this.wasm = proxyWasm;
        this.wasm.setPluginHandler(new HandlerImpl());
        this.wasm.start();
    }

    public String name() {
        return name;
    }

    public static Plugin.Builder builder() {
        return new Plugin.Builder();
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public boolean isShared() {
        return shared;
    }

    public ServerAdaptor getServerAdaptor() {
        return serverAdaptor;
    }

    public void setServerAdaptor(ServerAdaptor serverAdaptor) {
        this.serverAdaptor = serverAdaptor;
    }

    public LogHandler logger() {
        return logger;
    }

    public HttpContext createHttpContext(HttpRequestAdaptor requestAdaptor) {
        return new HttpContext(this, requestAdaptor);
    }

    public void close() {
        lock();
        try {
            wasm.close();
            if (cancelTick != null) {
                cancelTick.run();
                cancelTick = null;
            }
            for (var cancelHttpCall : httpCalls.values()) {
                cancelHttpCall.run();
            }
            httpCalls.clear();

        } finally {
            unlock();
        }
    }

    public static class Builder implements Cloneable {

        private ProxyWasm.Builder proxyWasmBuilder = ProxyWasm.builder().withStart(false);
        private boolean shared = true;
        private String name;
        private HashMap<String, ForeignFunction> foreignFunctions;
        private HashMap<String, String> upstreams;
        private boolean strictUpstreams;
        private int minTickPeriodMilliseconds;
        private LogHandler logger;
        private byte[] vmConfig;
        private byte[] pluginConfig;
        private MetricsHandler metricsHandler;
        private SharedQueueHandler sharedQueueHandler;
        private SharedDataHandler sharedDataHandler;

        public Plugin.Builder withName(String name) {
            this.name = name;
            return this;
        }

        public Builder withForeignFunctions(Map<String, ForeignFunction> functions) {
            this.foreignFunctions = new HashMap<>(functions);
            return this;
        }

        public Builder withUpstreams(Map<String, String> upstreams) {
            this.upstreams = new HashMap<>(upstreams);
            return this;
        }

        public Builder withStrictUpstreams(boolean strictUpstreams) {
            this.strictUpstreams = strictUpstreams;
            return this;
        }

        public Builder withMinTickPeriodMilliseconds(int minTickPeriodMilliseconds) {
            this.minTickPeriodMilliseconds = minTickPeriodMilliseconds;
            return this;
        }

        public Builder withLogger(LogHandler logger) {
            this.logger = logger;
            return this;
        }

        public Builder withMetricsHandler(MetricsHandler metricsHandler) {
            this.metricsHandler = metricsHandler;
            return this;
        }

        public Builder withSharedQueueHandler(SharedQueueHandler sharedQueueHandler) {
            this.sharedQueueHandler = sharedQueueHandler;
            return this;
        }

        public Builder withSharedDataHandler(SharedDataHandler sharedDataHandler) {
            this.sharedDataHandler = sharedDataHandler;
            return this;
        }

        public Builder withShared(boolean shared) {
            this.shared = shared;
            return this;
        }

        public Builder withVmConfig(byte[] vmConfig) {
            this.vmConfig = vmConfig;
            return this;
        }

        public Builder withVmConfig(String vmConfig) {
            this.vmConfig = bytes(vmConfig);
            return this;
        }

        public Builder withPluginConfig(byte[] pluginConfig) {
            this.pluginConfig = pluginConfig;
            return this;
        }

        public Builder withPluginConfig(String pluginConfig) {
            this.pluginConfig = bytes(pluginConfig);
            return this;
        }

        public Builder withImportMemory(ImportMemory memory) {
            proxyWasmBuilder = proxyWasmBuilder.withImportMemory(memory);
            return this;
        }

        public Builder withMachineFactory(Function<Instance, Machine> machineFactory) {
            proxyWasmBuilder.withMachineFactory(machineFactory);
            return this;
        }

        public Builder withWasiOptions(WasiOptions options) {
            proxyWasmBuilder.withWasiOptions(options);
            return this;
        }

        public Plugin build(WasmModule module) throws StartException {
            return build(proxyWasmBuilder.build(module));
        }

        public Plugin build(Instance.Builder instanceBuilder) throws StartException {
            return build(proxyWasmBuilder.build(instanceBuilder));
        }

        public Plugin build(Instance instance) throws StartException {
            return build(proxyWasmBuilder.build(instance));
        }

        public Plugin build(ProxyWasm proxyWasm) throws StartException {
            return new Plugin(this, proxyWasm);
        }
    }

    public LogHandler logger;
    static final boolean DEBUG = "true".equals(System.getenv("DEBUG"));
    byte[] vmConfig;
    byte[] pluginConfig;
    private final AtomicInteger lastCallId = new AtomicInteger(0);
    private final HashMap<Integer, Runnable> httpCalls = new HashMap<>();
    HashMap<String, String> upstreams = new HashMap<>();
    boolean strictUpstreams;
    int minTickPeriodMilliseconds;
    private int tickPeriodMilliseconds;
    private Runnable cancelTick;
    HashMap<String, ForeignFunction> foreignFunctions;
    private byte[] funcCallData = new byte[0];
    private final HashMap<List<String>, byte[]> properties = new HashMap<>();

    class HandlerImpl extends ChainedHandler {

        @Override
        protected Handler next() {
            return Handler.DEFAULT;
        }

        // //////////////////////////////////////////////////////////////////////
        // Plugin config
        // //////////////////////////////////////////////////////////////////////
        @Override
        public byte[] getVmConfig() {
            return vmConfig;
        }

        @Override
        public byte[] getPluginConfig() {
            return pluginConfig;
        }

        // //////////////////////////////////////////////////////////////////////
        // Properties
        // //////////////////////////////////////////////////////////////////////

        @Override
        public byte[] getProperty(List<String> path) throws WasmException {
            // TODO: do we need field for vm_id and root_id?
            if (PLUGIN_VM_ID.equals(path)) {
                return bytes(name);
            }
            if (PLUGIN_NAME.equals(path)) {
                return bytes(name);
            }
            return properties.get(path);
        }

        @Override
        public WasmResult setProperty(List<String> path, byte[] value) {
            properties.put(path, value);
            return WasmResult.OK;
        }

        // //////////////////////////////////////////////////////////////////////
        // Logging
        // //////////////////////////////////////////////////////////////////////

        @Override
        public void log(LogLevel level, String message) throws WasmException {
            logger.log(level, message);
        }

        @Override
        public LogLevel getLogLevel() throws WasmException {
            return logger.getLogLevel();
        }

        // //////////////////////////////////////////////////////////////////////
        // Timers
        // //////////////////////////////////////////////////////////////////////
        @Override
        public WasmResult setTickPeriodMilliseconds(int tickMs) {

            // check for no change
            if (tickMs == tickPeriodMilliseconds) {
                return WasmResult.OK;
            }

            // cancel the current tick, if any
            if (cancelTick != null) {
                cancelTick.run();
                cancelTick = null;
            }

            // set the new tick period, if any
            tickPeriodMilliseconds = tickMs;
            if (tickPeriodMilliseconds == 0) {
                return WasmResult.OK;
            }

            // schedule the new tick
            cancelTick =
                    serverAdaptor.scheduleTick(
                            Math.max(minTickPeriodMilliseconds, tickPeriodMilliseconds),
                            () -> {
                                lock();
                                try {
                                    wasm.tick();
                                } finally {
                                    unlock();
                                }
                            });
            return WasmResult.OK;
        }

        // //////////////////////////////////////////////////////////////////////
        // Foreign function interface (FFI)
        // //////////////////////////////////////////////////////////////////////

        @Override
        public byte[] getFuncCallData() {
            return funcCallData;
        }

        @Override
        public WasmResult setFuncCallData(byte[] data) {
            funcCallData = data;
            return WasmResult.OK;
        }

        // //////////////////////////////////////////////////////////////////////
        // HTTP calls
        // //////////////////////////////////////////////////////////////////////

        @Override
        public int httpCall(
                String upstreamName,
                ProxyMap headers,
                byte[] body,
                ProxyMap trailers,
                int timeoutMilliseconds)
                throws WasmException {

            var method = headers.get(METHOD);
            if (method == null) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }

            var scheme = headers.get(SCHEME);
            if (scheme == null) {
                scheme = "http";
            }
            var authority = headers.get(AUTHORITY);
            if (authority == null) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }
            headers.put("Host", authority);

            var connectHostPort = upstreams.get(upstreamName);
            if (connectHostPort == null && strictUpstreams) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }
            if (connectHostPort == null) {
                connectHostPort = authority;
            }

            URI connectUri = null;
            try {
                connectUri = URI.create(scheme + "://" + connectHostPort);
            } catch (IllegalArgumentException e) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }

            var connectHost = connectUri.getHost();
            var connectPort = connectUri.getPort();
            if (connectPort == -1) {
                connectPort = "https".equals(scheme) ? 443 : 80;
            }

            var path = headers.get(PATH);
            if (path == null) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }
            if (!path.isEmpty() && !path.startsWith("/")) {
                path = "/" + path;
            }

            URI uri = null;
            try {
                uri = URI.create(scheme + "://" + authority + path);
            } catch (IllegalArgumentException e) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }

            // Remove all the pseudo headers
            for (var r : new ArrayProxyMap(headers).entries()) {
                if (r.getKey().startsWith(":")) {
                    headers.remove(r.getKey());
                }
            }

            try {
                var id = lastCallId.incrementAndGet();
                var future =
                        serverAdaptor.scheduleHttpCall(
                                method,
                                connectHost,
                                connectPort,
                                uri,
                                headers,
                                body,
                                trailers,
                                timeoutMilliseconds,
                                (resp) -> {
                                    lock();
                                    try {
                                        if (httpCalls.remove(id) == null) {
                                            return; // the call could have already been cancelled
                                        }
                                        wasm.sendHttpCallResponse(
                                                id, resp.headers, new ArrayProxyMap(), resp.body);
                                    } finally {
                                        unlock();
                                    }
                                });
                httpCalls.put(id, future);
                return id;
            } catch (InterruptedException e) {
                throw new WasmException(WasmResult.INTERNAL_FAILURE);
            }
        }

        @Override
        public int dispatchHttpCall(
                String upstreamName,
                ProxyMap headers,
                byte[] body,
                ProxyMap trailers,
                int timeoutMilliseconds)
                throws WasmException {
            return httpCall(upstreamName, headers, body, trailers, timeoutMilliseconds);
        }

        // //////////////////////////////////////////////////////////////////////
        // Metrics
        // //////////////////////////////////////////////////////////////////////

        @Override
        public int defineMetric(MetricType type, String name) throws WasmException {
            return metricsHandler.defineMetric(type, name);
        }

        @Override
        public long getMetric(int metricId) throws WasmException {
            return metricsHandler.getMetric(metricId);
        }

        @Override
        public WasmResult incrementMetric(int metricId, long value) {
            return metricsHandler.incrementMetric(metricId, value);
        }

        @Override
        public WasmResult recordMetric(int metricId, long value) {
            return metricsHandler.recordMetric(metricId, value);
        }

        @Override
        public WasmResult removeMetric(int metricId) {
            return metricsHandler.removeMetric(metricId);
        }

        // //////////////////////////////////////////////////////////////////////
        // FFI
        // //////////////////////////////////////////////////////////////////////

        @Override
        public ForeignFunction getForeignFunction(String name) {
            if (foreignFunctions == null) {
                return null;
            }
            return foreignFunctions.get(name);
        }

        // //////////////////////////////////////////////////////////////////////
        // Shared Data
        // //////////////////////////////////////////////////////////////////////

        @Override
        public SharedData getSharedData(String key) throws WasmException {
            return sharedDataHandler.getSharedData(key);
        }

        @Override
        public WasmResult setSharedData(String key, byte[] value, int cas) {
            return sharedDataHandler.setSharedData(key, value, cas);
        }

        // //////////////////////////////////////////////////////////////////////
        // Shared Queue
        // //////////////////////////////////////////////////////////////////////

        @Override
        public int registerSharedQueue(QueueName queueName) throws WasmException {
            return sharedQueueHandler.registerSharedQueue(queueName);
        }

        @Override
        public int resolveSharedQueue(QueueName queueName) throws WasmException {
            return sharedQueueHandler.resolveSharedQueue(queueName);
        }

        @Override
        public byte[] dequeueSharedQueue(int queueId) throws WasmException {
            return sharedQueueHandler.dequeueSharedQueue(queueId);
        }

        @Override
        public WasmResult enqueueSharedQueue(int queueId, byte[] value) {
            return sharedQueueHandler.enqueueSharedQueue(queueId, value);
        }
    }
}
