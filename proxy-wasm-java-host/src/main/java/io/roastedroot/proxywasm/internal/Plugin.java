package io.roastedroot.proxywasm.internal;

import static io.roastedroot.proxywasm.internal.Helpers.bytes;
import static io.roastedroot.proxywasm.internal.WellKnownHeaders.AUTHORITY;
import static io.roastedroot.proxywasm.internal.WellKnownHeaders.METHOD;
import static io.roastedroot.proxywasm.internal.WellKnownHeaders.PATH;
import static io.roastedroot.proxywasm.internal.WellKnownHeaders.SCHEME;
import static io.roastedroot.proxywasm.internal.WellKnownProperties.PLUGIN_NAME;
import static io.roastedroot.proxywasm.internal.WellKnownProperties.PLUGIN_ROOT_ID;
import static io.roastedroot.proxywasm.internal.WellKnownProperties.PLUGIN_VM_ID;

import io.roastedroot.proxywasm.ForeignFunction;
import io.roastedroot.proxywasm.LogHandler;
import io.roastedroot.proxywasm.LogLevel;
import io.roastedroot.proxywasm.MetricType;
import io.roastedroot.proxywasm.MetricsHandler;
import io.roastedroot.proxywasm.QueueName;
import io.roastedroot.proxywasm.SharedData;
import io.roastedroot.proxywasm.SharedDataHandler;
import io.roastedroot.proxywasm.SharedQueueHandler;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.WasmException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class Plugin implements io.roastedroot.proxywasm.Plugin {

    private final ReentrantLock lock = new ReentrantLock();
    final ProxyWasm wasm;
    ServerAdaptor serverAdaptor;
    private final String name;

    private final MetricsHandler metricsHandler;
    private final SharedQueueHandler sharedQueueHandler;
    private final SharedDataHandler sharedDataHandler;

    public Plugin(
            ProxyWasm proxyWasm,
            String name,
            HashMap<String, ForeignFunction> foreignFunctions,
            HashMap<String, URI> upstreams,
            boolean strictUpstreams,
            int minTickPeriodMilliseconds,
            LogHandler logger,
            byte[] vmConfig,
            byte[] pluginConfig,
            MetricsHandler metricsHandler,
            SharedQueueHandler sharedQueueHandler,
            SharedDataHandler sharedDataHandler,
            HashMap<List<String>, byte[]> properties)
            throws StartException {
        Objects.requireNonNull(proxyWasm);
        this.name = Objects.requireNonNullElse(name, "default");
        this.foreignFunctions = Objects.requireNonNullElseGet(foreignFunctions, HashMap::new);
        this.upstreams = Objects.requireNonNullElseGet(upstreams, HashMap::new);
        this.strictUpstreams = strictUpstreams;
        this.minTickPeriodMilliseconds = minTickPeriodMilliseconds;
        this.vmConfig = vmConfig;
        this.pluginConfig = pluginConfig;
        this.logger = Objects.requireNonNullElse(logger, LogHandler.DEFAULT);
        this.metricsHandler = Objects.requireNonNullElse(metricsHandler, MetricsHandler.DEFAULT);
        this.sharedQueueHandler =
                Objects.requireNonNullElse(sharedQueueHandler, SharedQueueHandler.DEFAULT);
        this.sharedDataHandler =
                Objects.requireNonNullElse(sharedDataHandler, SharedDataHandler.DEFAULT);
        this.properties = Objects.requireNonNull(properties);

        this.wasm = proxyWasm;
        this.wasm.setPluginHandler(new HandlerImpl());
        this.wasm.start();
    }

    @Override
    public String name() {
        return name;
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
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

    public PluginHttpContext createHttpContext(HttpRequestAdaptor requestAdaptor) {
        return new PluginHttpContext(this, requestAdaptor);
    }

    public void close() {
        lock();
        try {
            wasm.close();
            if (cancelTick != null) {
                cancelTick.run();
                cancelTick = null;
            }
            for (var cancel : httpCalls.values()) {
                cancel.run();
            }
            httpCalls.clear();
            for (var cancel : grpcCalls.values()) {
                cancel.run();
            }
            grpcCalls.clear();

        } finally {
            unlock();
        }
    }

    public LogHandler logger;
    static final boolean DEBUG = "true".equals(System.getenv("DEBUG"));
    byte[] vmConfig;
    byte[] pluginConfig;
    private final AtomicInteger lastCallId = new AtomicInteger(0);
    private final HashMap<Integer, Runnable> httpCalls = new HashMap<>();
    private final HashMap<Integer, Runnable> grpcCalls = new HashMap<>();
    private final HashMap<String, URI> upstreams;
    boolean strictUpstreams;
    int minTickPeriodMilliseconds;
    private int tickPeriodMilliseconds;
    private Runnable cancelTick;
    private final HashMap<String, ForeignFunction> foreignFunctions;
    private byte[] funcCallData = new byte[0];
    private final HashMap<List<String>, byte[]> properties;

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
            if (PLUGIN_ROOT_ID.equals(path)) {
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

            var connectUri = upstreams.get(upstreamName);
            if (connectUri == null && strictUpstreams) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }
            if (connectUri == null) {
                try {
                    connectUri = new URI(upstreamName);
                } catch (URISyntaxException e) {
                    throw new WasmException(WasmResult.BAD_ARGUMENT);
                }
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
                                (statusCode, respHeaders, respBody) -> {
                                    // todo: add the status
                                    // respHeaders.put(STATUS, "" + statusCode);
                                    lock();
                                    try {
                                        if (httpCalls.remove(id) == null) {
                                            return; // the call could have already been cancelled
                                        }
                                        wasm.sendHttpCallResponse(
                                                id, respHeaders, new ArrayProxyMap(), respBody);
                                    } finally {
                                        unlock();
                                    }
                                });
                httpCalls.put(id, future);
                return id;
            } catch (InterruptedException e) {
                throw new WasmException(WasmResult.INTERNAL_FAILURE);
            } catch (UnsupportedOperationException e) {
                throw new WasmException(WasmResult.UNIMPLEMENTED);
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
        // GRPC calls
        // //////////////////////////////////////////////////////////////////////

        @Override
        public int grpcCall(
                String upstreamName,
                String serviceName,
                String methodName,
                ProxyMap headers,
                byte[] body,
                int timeoutMilliseconds)
                throws WasmException {

            var connectUri = upstreams.get(upstreamName);
            if (connectUri == null && strictUpstreams) {
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }

            if (connectUri == null) {
                try {
                    connectUri = new URI(upstreamName);
                } catch (URISyntaxException e) {
                    throw new WasmException(WasmResult.BAD_ARGUMENT);
                }
            }

            if (!("http".equals(connectUri.getScheme())
                    || "https".equals(connectUri.getScheme()))) {
                logger.log(
                        LogLevel.ERROR,
                        "grpc call upstream not mapped to URL with a http/https scheme: "
                                + upstreamName);
                throw new WasmException(WasmResult.BAD_ARGUMENT);
            }

            var connectHost = connectUri.getHost();
            var connectPort = connectUri.getPort();
            if (connectPort == -1) {
                connectPort = "https".equals(connectUri.getScheme()) ? 443 : 80;
            }

            try {

                var id = lastCallId.incrementAndGet();
                var callHandler =
                        new GrpcCallResponseHandler() {

                            @Override
                            public void onHeaders(ArrayBytesProxyMap headers) {
                                lock();
                                try {
                                    if (grpcCalls.get(id) == null) {
                                        return; // the call could have already been cancelled
                                    }
                                    wasm.sendGrpcReceiveInitialMetadata(id, headers);
                                } finally {
                                    unlock();
                                }
                            }

                            @Override
                            public void onMessage(byte[] data) {
                                lock();
                                try {
                                    if (grpcCalls.get(id) == null) {
                                        return; // the call could have already been cancelled
                                    }
                                    wasm.sendGrpcReceive(id, data);
                                } finally {
                                    unlock();
                                }
                            }

                            @Override
                            public void onTrailers(ArrayBytesProxyMap trailers) {
                                lock();
                                try {
                                    if (grpcCalls.get(id) == null) {
                                        return; // the call could have already been cancelled
                                    }
                                    wasm.sendGrpcReceiveTrailingMetadata(id, trailers);
                                } finally {
                                    unlock();
                                }
                            }

                            @Override
                            public void onClose(int status) {
                                lock();
                                try {
                                    if (grpcCalls.get(id) == null) {
                                        return; // the call could have already been cancelled
                                    }
                                    wasm.sendGrpcClose(id, status);
                                } finally {
                                    unlock();
                                }
                            }
                        };

                var future =
                        serverAdaptor.scheduleGrpcCall(
                                connectHost,
                                connectPort,
                                "http".equals(connectUri.getScheme()),
                                serviceName,
                                methodName,
                                headers,
                                body,
                                timeoutMilliseconds,
                                callHandler);
                grpcCalls.put(id, future);
                return id;
            } catch (InterruptedException e) {
                throw new WasmException(WasmResult.INTERNAL_FAILURE);
            } catch (UnsupportedOperationException e) {
                throw new WasmException(WasmResult.UNIMPLEMENTED);
            }
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
