package io.roastedroot.proxywasm.internal;

import static io.roastedroot.proxywasm.internal.Helpers.len;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.roastedroot.proxywasm.StartException;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class ProxyWasm implements Closeable {

    private final ABI abi;

    private final AtomicInteger nextContextID = new AtomicInteger(1);
    private Context pluginContext;
    private Context activeContext;

    private HashMap<Integer, Context> contexts = new HashMap<>();
    private ProxyMap httpCallResponseHeaders;
    private ProxyMap httpCallResponseTrailers;
    private byte[] httpCallResponseBody;
    private Handler pluginHandler;
    private ArrayBytesProxyMap grpcReceiveInitialMetadata;
    private byte[] grpcReceive;
    private ArrayBytesProxyMap grpcReceiveTrailingMetadata;

    private ProxyWasm(Builder other) throws StartException {
        this.pluginHandler = Objects.requireNonNullElse(other.pluginHandler, new Handler() {});
        this.abi = other.abi;
        this.abi.setHandler(createImportsHandler());

        // initialize/start the vm
        if (this.abi.initialize()) {
            this.abi.main(0, 0);
        } else {
            this.abi.start();
        }

        if (other.start) {
            start();
        }
    }

    public Handler getPluginHandler() {
        return pluginHandler;
    }

    public void setPluginHandler(Handler pluginHandler) {
        this.pluginHandler = pluginHandler;
    }

    public void start() throws StartException {
        if (pluginContext != null) {
            return;
        }

        this.pluginContext = new PluginContext(this, pluginHandler);
        registerContext(pluginContext, 0);

        byte[] vmConfig = this.pluginHandler.getVmConfig();
        if (!this.abi.proxyOnVmStart(pluginContext.id(), len(vmConfig))) {
            throw new StartException("proxy_on_vm_start failed");
        }

        byte[] pluginConfig = this.pluginHandler.getPluginConfig();
        if (!this.abi.proxyOnConfigure(pluginContext.id(), len(pluginConfig))) {
            throw new StartException("proxy_on_configure failed");
        }
    }

    private void registerContext(Context context, int parentContextID) {
        contexts.put(context.id(), context);
        activeContext = context;
        this.abi.proxyOnContextCreate(context.id(), parentContextID);
    }

    // Let's implement some of the handler functions to make life easier for the user.
    // The user's handler will be the last handler in the chain.
    private ChainedHandler createImportsHandler() {
        return new ChainedHandler() {
            @Override
            protected Handler next() {
                return activeContext.handler();
            }

            @Override
            public WasmResult setEffectiveContextID(int contextID) {
                Context context = contexts.get(contextID);
                if (context == null) {
                    return WasmResult.BAD_ARGUMENT;
                }
                activeContext = context;
                return WasmResult.OK;
            }

            @Override
            public WasmResult done() {
                return activeContext.done();
            }

            @Override
            public ProxyMap getHttpCallResponseHeaders() {
                return httpCallResponseHeaders;
            }

            @Override
            public ProxyMap getHttpCallResponseTrailers() {
                return httpCallResponseTrailers;
            }

            @Override
            public byte[] getHttpCallResponseBody() {
                return httpCallResponseBody;
            }

            @Override
            public ProxyMap getGrpcReceiveInitialMetaData() {
                return grpcReceiveInitialMetadata;
            }

            @Override
            public byte[] getGrpcReceiveBuffer() {
                return grpcReceive;
            }

            @Override
            public ProxyMap getGrpcReceiveTrailerMetaData() {
                return grpcReceiveTrailingMetadata;
            }
        };
    }

    HashMap<Integer, Context> contexts() {
        return contexts;
    }

    Context getActiveContext() {
        return activeContext;
    }

    void setActiveContext(Context activeContext) {
        if (activeContext == null) {
            // this happens when a context is finishes closing...
            // assuming the current context should be the plugin context after that,
            // but maybe it would be better to error out if a new context is not set.
            activeContext = this.pluginContext;
        }
        this.activeContext = activeContext;
    }

    int nextContextID() {
        return nextContextID.getAndIncrement();
    }

    public HttpContext createHttpContext(Handler handler) {
        HttpContext context = new HttpContext(this, handler);
        registerContext(context, this.pluginContext.id());
        return context;
    }

    public NetworkContext createNetworkContext(Handler handler) {
        NetworkContext context = new NetworkContext(this, handler);
        registerContext(context, this.pluginContext.id());
        return context;
    }

    /**
     * Delivers a tick event to the plugin.
     * <p>
     * tick() should be called in response to a Handler.setTickPeriodMilliseconds(int tick_period) callback.
     */
    public void tick() {
        this.abi.proxyOnTick(pluginContext.id());
    }

    @Override
    public void close() {
        if (this.pluginContext == null) {
            return;
        }
        this.pluginContext.close();
    }

    public static ProxyWasm.Builder builder() {
        return new ProxyWasm.Builder();
    }

    public void sendHttpCallResponse(
            int calloutID, Map<String, String> headers, Map<String, String> trailers, byte[] body) {
        this.sendHttpCallResponse(
                calloutID, ProxyMap.copyOf(headers), ProxyMap.copyOf(trailers), body);
    }

    public void sendHttpCallResponse(
            int calloutID, ProxyMap headers, ProxyMap trailers, byte[] body) {
        this.httpCallResponseHeaders = headers;
        this.httpCallResponseTrailers = trailers;
        this.httpCallResponseBody = body;

        this.abi.proxyOnHttpCallResponse(
                pluginContext.id(), calloutID, len(headers), len(body), len(trailers));

        this.httpCallResponseHeaders = null;
        this.httpCallResponseTrailers = null;
        this.httpCallResponseBody = null;
    }

    public void sendGrpcReceiveInitialMetadata(int calloutID, ArrayBytesProxyMap headers) {
        this.grpcReceiveInitialMetadata = headers;
        this.abi.proxyOnGrpcReceiveInitialMetadata(pluginContext.id(), calloutID, headers.size());
        this.grpcReceiveInitialMetadata = null;
    }

    public void sendGrpcReceive(int calloutID, byte[] body) {
        this.grpcReceive = body;
        this.abi.proxyOnGrpcReceive(pluginContext.id(), calloutID, len(body));
        this.grpcReceive = null;
    }

    public void sendGrpcReceiveTrailingMetadata(int calloutID, ArrayBytesProxyMap headers) {
        this.grpcReceiveTrailingMetadata = headers;
        this.abi.proxyOnGrpcReceiveTrailingMetadata(pluginContext.id(), calloutID, headers.size());
        this.grpcReceiveTrailingMetadata = null;
    }

    public void sendGrpcClose(int calloutID, int statusCode) {
        this.abi.proxyOnGrpcClose(pluginContext.id(), calloutID, statusCode);
    }

    public void sendOnQueueReady(int queueId) {
        this.abi.proxyOnQueueReady(pluginContext.id(), queueId);
    }

    public int contextId() {
        return pluginContext.id();
    }

    ABI abi() {
        return abi;
    }

    public static class Builder implements Cloneable {

        private final ABI abi = new ABI();

        private Handler pluginHandler;
        private ImportMemory memory;
        private boolean start = true;
        private Function<Instance, Machine> machineFactory;

        @Override
        @SuppressWarnings("NoClone")
        protected Builder clone() {
            try {
                return (Builder) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }

        public Builder withStart(boolean start) {
            this.start = start;
            return this;
        }

        public ProxyWasm.Builder withPluginHandler(Handler vmHandler) {
            this.pluginHandler = vmHandler;
            return this;
        }

        public ProxyWasm.Builder withImportMemory(ImportMemory memory) {
            this.memory = memory;
            return this;
        }

        public ProxyWasm.Builder withMachineFactory(Function<Instance, Machine> machineFactory) {
            this.machineFactory = machineFactory;
            return this;
        }

        Builder() {}

        public ProxyWasm build(Instance instance) throws StartException {
            abi.setInstance(instance);
            return new ProxyWasm(this.clone());
        }

        public ProxyWasm build(WasmModule module) throws StartException {
            return this.build(Instance.builder(module));
        }

        public ProxyWasm build(Instance.Builder instanceBuilder) throws StartException {
            var imports = ImportValues.builder();

            if (this.machineFactory != null) {
                instanceBuilder.withMachineFactory(this.machineFactory);
            }

            imports.addMemory(Objects.requireNonNullElseGet(memory, this::defaultImportMemory));
            imports.addFunction(abi.toHostFunctions());

            var instance =
                    instanceBuilder
                            .withStart(false) // we will start it manually
                            .withImportValues(imports.build())
                            .build();

            return build(instance);
        }

        ImportMemory defaultImportMemory() {
            return new ImportMemory(
                    "env",
                    "memory",
                    new ByteArrayMemory(new MemoryLimits(2, MemoryLimits.MAX_PAGES)));
        }
    }
}
