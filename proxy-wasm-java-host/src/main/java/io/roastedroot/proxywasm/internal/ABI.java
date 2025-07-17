package io.roastedroot.proxywasm.internal;

import static io.roastedroot.proxywasm.internal.Helpers.bytes;
import static io.roastedroot.proxywasm.internal.Helpers.replaceBytes;
import static io.roastedroot.proxywasm.internal.Helpers.split;
import static io.roastedroot.proxywasm.internal.Helpers.string;

import com.dylibso.chicory.annotations.HostModule;
import com.dylibso.chicory.annotations.WasmExport;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasm.InvalidException;
import io.roastedroot.proxywasm.LogLevel;
import io.roastedroot.proxywasm.MetricType;
import io.roastedroot.proxywasm.QueueName;
import io.roastedroot.proxywasm.SharedData;
import io.roastedroot.proxywasm.WasmException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@HostModule("env")
class ABI {

    private final Handler handler;
    private Memory memory;
    ExportFunction initializeFn;
    ExportFunction mainFn;
    ExportFunction startFn;
    ExportFunction proxyOnContextCreateFn;
    ExportFunction proxyOnDoneFn;
    ExportFunction mallocFn;
    ExportFunction proxyOnLogFn;
    ExportFunction proxyOnDeleteFn;
    ExportFunction proxyOnVmStartFn;
    ExportFunction proxyOnConfigureFn;
    ExportFunction proxyOnTickFn;
    ExportFunction proxyOnNewConnectionFn;
    ExportFunction proxyOnDownstreamDataFn;
    ExportFunction proxyOnDownstreamConnectionCloseFn;
    ExportFunction proxyOnUpstreamDataFn;
    ExportFunction proxyOnUpstreamConnectionCloseFn;
    ExportFunction proxyOnRequestHeadersFn;
    ExportFunction proxyOnRequestBodyFn;
    ExportFunction proxyOnRequestTrailersFn;
    ExportFunction proxyOnResponseHeadersFn;
    ExportFunction proxyOnResponseBodyFn;
    ExportFunction proxyOnResponseTrailersFn;
    ExportFunction proxyOnHttpCallResponseFn;
    ExportFunction proxyOnGrpcReceiveInitialMetadataFn;
    ExportFunction proxyOnGrpcReceiveFn;
    ExportFunction proxyOnGrpcReceiveTrailingMetadataFn;
    ExportFunction proxyOnGrpcCloseFn;
    ExportFunction proxyOnQueueReadyFn;
    ExportFunction proxyOnForeignFunctionFn;

    ABI(Handler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    void setInstance(Instance instance) {
        this.memory = instance.memory();
        var exports = instance.exports();

        // Since 0_2_0, prefer proxy_on_memory_allocate over malloc
        this.mallocFn = lookupFunction(exports, "proxy_on_memory_allocate");
        if (this.mallocFn == null) {
            this.mallocFn = lookupFunction(exports, "malloc");
        }
        if (this.mallocFn == null) {
            throw new WasmRuntimeException("malloc function not found");
        }

        this.initializeFn = lookupFunction(exports, "_initialize");
        this.mainFn = lookupFunction(exports, "main");
        this.startFn = lookupFunction(exports, "_start");

        // All callbacks proxyOn* are optional, and will only be called if exposed by the Wasm
        // module.
        this.proxyOnContextCreateFn = lookupFunction(exports, "proxy_on_context_create");
        this.proxyOnDoneFn = lookupFunction(exports, "proxy_on_done");
        this.proxyOnLogFn = lookupFunction(exports, "proxy_on_log");
        this.proxyOnDeleteFn = lookupFunction(exports, "proxy_on_delete");
        this.proxyOnVmStartFn = lookupFunction(exports, "proxy_on_vm_start");
        this.proxyOnConfigureFn = lookupFunction(exports, "proxy_on_configure");
        this.proxyOnTickFn = lookupFunction(exports, "proxy_on_tick");
        this.proxyOnNewConnectionFn = lookupFunction(exports, "proxy_on_new_connection");
        this.proxyOnDownstreamDataFn = lookupFunction(exports, "proxy_on_downstream_data");
        this.proxyOnDownstreamConnectionCloseFn =
                lookupFunction(exports, "proxy_on_downstream_connection_close");
        this.proxyOnUpstreamDataFn = lookupFunction(exports, "proxy_on_upstream_data");
        this.proxyOnUpstreamConnectionCloseFn =
                lookupFunction(exports, "proxy_on_upstream_connection_close");
        this.proxyOnRequestHeadersFn = lookupFunction(exports, "proxy_on_request_headers");
        this.proxyOnRequestBodyFn = lookupFunction(exports, "proxy_on_request_body");
        this.proxyOnRequestTrailersFn = lookupFunction(exports, "proxy_on_request_trailers");
        this.proxyOnResponseHeadersFn = lookupFunction(exports, "proxy_on_response_headers");
        this.proxyOnResponseBodyFn = lookupFunction(exports, "proxy_on_response_body");
        this.proxyOnResponseTrailersFn = lookupFunction(exports, "proxy_on_response_trailers");
        this.proxyOnHttpCallResponseFn = lookupFunction(exports, "proxy_on_http_call_response");
        this.proxyOnGrpcReceiveInitialMetadataFn =
                lookupFunction(exports, "proxy_on_grpc_receive_initial_metadata");
        this.proxyOnGrpcReceiveFn = lookupFunction(exports, "proxy_on_grpc_receive");
        this.proxyOnGrpcReceiveTrailingMetadataFn =
                lookupFunction(exports, "proxy_on_grpc_receive_trailing_metadata");
        this.proxyOnGrpcCloseFn = lookupFunction(exports, "proxy_on_grpc_close");
        this.proxyOnQueueReadyFn = lookupFunction(exports, "proxy_on_queue_ready");
        this.proxyOnForeignFunctionFn = lookupFunction(exports, "proxy_on_foreign_function");
    }

    // //////////////////////////////////////////////////////////////////////
    // Common Helpers
    // //////////////////////////////////////////////////////////////////////

    // Size of a 32-bit integer in bytes
    static final int U32_LEN = 4;

    private ExportFunction lookupFunction(Instance.Exports exports, String name) {
        try {
            return exports.function(name);
        } catch (InvalidException e) {
            return null;
        }
    }

    /**
     * Write a 32-bit unsigned integer to memory().
     *
     * @param address The address to write to
     * @param value   The value to write
     * @throws WasmException if the memory access is invalid
     */
    private void putUint32(int address, int value) throws WasmException {
        try {
            memory.writeI32(address, value);
        } catch (RuntimeException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    /**
     * Read a 32-bit unsigned integer to memory().
     *
     * @param address The address to read from
     * @throws WasmException if the memory access is invalid
     */
    private long getUint32(int address) throws WasmException {
        try {
            return memory.readU32(address);
        } catch (RuntimeException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    /**
     * Write a byte to memory().
     *
     * @param address The address to write to
     * @param value   The value to write
     * @throws WasmException if the memory access is invalid
     */
    private void putByte(int address, byte value) throws WasmException {
        try {
            memory.writeByte(address, value);
        } catch (RuntimeException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    /**
     * Write bytes to memory().
     *
     * @param address The address to write to
     * @param data    The data to write
     * @throws WasmException if the memory access is invalid
     */
    private void putMemory(int address, byte[] data) throws WasmException {
        try {
            memory.write(address, data);
        } catch (RuntimeException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    /**
     * Write bytes to memory().
     *
     * @param address The address to write to
     * @param data    The data to write
     * @throws WasmException if the memory access is invalid
     */
    private void putMemory(int address, ByteBuffer data) throws WasmException {
        try {
            if (data.hasArray()) {
                var array = data.array();
                memory.write(address, array, data.position(), data.remaining());
            } else {
                // This could likely be optimized by extending the memory interface to accept
                // ByteBuffer
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                memory.write(address, bytes);
            }
        } catch (RuntimeException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    /**
     * Read bytes from memory.
     *
     * @param address The address to read from
     * @param len     The number of bytes to read
     * @return The value read
     * @throws WasmException if the memory access is invalid
     */
    private byte[] readMemory(int address, int len) throws WasmException {
        try {
            return memory.readBytes(address, len);
        } catch (RuntimeException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    private void copyIntoInstance(byte[] value, int retPtr, int retSize) throws WasmException {
        try {
            if (value.length != 0) {
                int addr = malloc(value.length);
                putMemory(addr, value);
                putUint32(retPtr, addr);
            } else {
                putUint32(retPtr, 0);
            }
            putUint32(retSize, value.length);
        } catch (WasmException e) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
    }

    // //////////////////////////////////////////////////////////////////////
    // Integration
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#_initialize
     */
    boolean initialize() {
        if (this.initializeFn == null) {
            return false;
        }
        this.initializeFn.apply();
        return true;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#main
     */
    boolean main(int arg0, int arg1) {
        if (mainFn == null) {
            return false;
        }
        mainFn.apply(arg0, arg1);
        return true;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#_start
     */
    boolean start() {
        if (startFn == null) {
            return false;
        }
        startFn.apply();
        return true;
    }

    // //////////////////////////////////////////////////////////////////////
    // Memory management
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements:
     * * https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#malloc
     * * https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_memory_allocate
     */
    int malloc(int size) throws WasmException {
        // I've noticed guests fail on malloc(0) so lets avoid that
        assert size > 0 : "malloc size must be greater than zero";
        long ptr = mallocFn.apply(size)[0];
        if (ptr == 0) {
            throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
        }
        return (int) ptr;
    }

    // //////////////////////////////////////////////////////////////////////
    // Context lifecycle
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_context_create
     */
    void proxyOnContextCreate(int contextID, int parentContextID) {
        if (proxyOnContextCreateFn == null) {
            return;
        }
        proxyOnContextCreateFn.apply(contextID, parentContextID);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_done
     */
    boolean proxyOnDone(int context_id) {
        if (proxyOnDoneFn == null) {
            return true;
        }
        long result = proxyOnDoneFn.apply(context_id)[0];
        return result != 0;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_log
     */
    void proxyOnLog(int context_id) {
        if (proxyOnLogFn == null) {
            return;
        }
        proxyOnLogFn.apply(context_id);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_delete
     */
    void proxyOnDelete(int context_id) {
        if (proxyOnDeleteFn == null) {
            return;
        }
        proxyOnDeleteFn.apply(context_id);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_done
     */
    @WasmExport
    int proxyDone() {
        return handler.done().getValue();
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_set_effective_context
     */
    @WasmExport
    int proxySetEffectiveContext(int contextId) {
        return handler.setEffectiveContextID(contextId).getValue();
    }

    // //////////////////////////////////////////////////////////////////////
    // Configuration
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_vm_start
     */
    boolean proxyOnVmStart(int arg0, int arg1) {
        if (proxyOnVmStartFn == null) {
            return true;
        }
        long result = proxyOnVmStartFn.apply(arg0, arg1)[0];
        return result != 0;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_configure
     */
    boolean proxyOnConfigure(int arg0, int arg1) {
        if (proxyOnConfigureFn == null) {
            return true;
        }
        long result = proxyOnConfigureFn.apply(arg0, arg1)[0];
        return result != 0;
    }

    // //////////////////////////////////////////////////////////////////////
    // Logging
    // //////////////////////////////////////////////////////////////////////

    // wasi_snapshot_preview1.fd_write :
    // https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#wasi_snapshot_preview1fd_write

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_log
     */
    @WasmExport
    int proxyLog(int logLevel, int messageData, int messageSize) {
        try {
            var msg = memory.readBytes(messageData, messageSize);
            handler.log(LogLevel.fromInt(logLevel), string(msg));
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_log_level
     */
    @WasmExport
    int proxyGetLogLevel(int returnLogLevel) {
        try {
            LogLevel level = handler.getLogLevel();
            putUint32(returnLogLevel, level.value());
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    // //////////////////////////////////////////////////////////////////////
    // Clocks
    // //////////////////////////////////////////////////////////////////////

    // wasi_snapshot_preview1.clock_time_get

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_current_time_nanoseconds
     */
    @WasmExport
    int proxyGetCurrentTimeNanoseconds(int returnTime) {
        try {
            int currentTimeNanoseconds = handler.getCurrentTimeNanoseconds();
            putUint32(returnTime, currentTimeNanoseconds);
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    // //////////////////////////////////////////////////////////////////////
    // Timers
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_set_tick_period_milliseconds
     */
    @WasmExport
    int proxySetTickPeriodMilliseconds(int tick_period) {
        return handler.setTickPeriodMilliseconds(tick_period).getValue();
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_tick
     */
    void proxyOnTick(int arg0) {
        if (proxyOnTickFn == null) {
            return;
        }
        proxyOnTickFn.apply(arg0);
    }

    // //////////////////////////////////////////////////////////////////////
    // Randomness
    // //////////////////////////////////////////////////////////////////////

    // wasi_snapshot_preview1.random_get

    // //////////////////////////////////////////////////////////////////////
    // Environment variables
    // //////////////////////////////////////////////////////////////////////

    // wasi_snapshot_preview1.environ_sizes_get
    // wasi_snapshot_preview1.environ_get

    // //////////////////////////////////////////////////////////////////////
    // Buffers
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_buffer_bytes
     *
     * @param bufferType       The type of buffer to get
     * @param start            The start index in the buffer
     * @param length           The number of bytes to get
     * @param returnBufferData Pointer to where the buffer data address should be stored
     * @param returnBufferSize Pointer to where the buffer size should be stored
     * @return WasmResult status code
     */
    @WasmExport
    int proxyGetBufferBytes(
            int bufferType,
            int start,
            int chunkLength,
            int returnBufferData,
            int returnBufferSize) {

        try {
            // Get the buffer based on the buffer type
            byte[] b = getBuffer(bufferType);
            if (b == null || b.length == 0) {
                return WasmResult.NOT_FOUND.getValue();
            }

            if (start < 0) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            int maxChunkLength = b.length - start;
            if (chunkLength < 0 || chunkLength > maxChunkLength) {
                chunkLength = maxChunkLength;
            }

            ByteBuffer buffer = ByteBuffer.wrap(b);
            if (start + chunkLength > buffer.capacity()) {
                chunkLength = buffer.capacity() - start;
            }

            try {
                buffer.position(start);
                buffer.limit(start + chunkLength);
            } catch (IllegalArgumentException e) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // Allocate memory in the WebAssembly instance
            int addr = malloc(chunkLength);
            putMemory(addr, buffer);
            // Write the address to the return pointer
            putUint32(returnBufferData, addr);
            // Write the length to the return size pointer
            putUint32(returnBufferSize, chunkLength);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_set_buffer_bytes
     *
     * @param bufferType The type of buffer to modify
     * @param start      The start index in the buffer
     * @param length     The number of bytes to set
     * @param dataPtr    Pointer to the data in WebAssembly memory
     * @param dataSize   Size of the data
     * @return WasmResult status code
     */
    @WasmExport
    int proxySetBufferBytes(int bufferType, int start, int length, int dataPtr, int dataSize) {
        try {

            // Get the buffer based on the buffer type
            var buf = getBuffer(bufferType);
            if (buf == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            // Get content from WebAssembly memory
            byte[] content = memory.readBytes(dataPtr, dataSize);

            content = replaceBytes(buf, content, start, length);

            // Set the buffer using the appropriate handler method
            WasmResult result = setBuffer(bufferType, content);
            return result.getValue();

        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        }
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_buffer_status
     */
    @WasmExport
    int proxyGetBufferStatus(int bufferType, int returnBufferSize, int returnUnused) {
        try {
            // Get the buffer based on the buffer type
            byte[] b = getBuffer(bufferType);
            if (b == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            // Write the buffer size to the return size pointer
            putUint32(returnBufferSize, b.length);
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Get a buffer based on the buffer type.
     *
     * @param bufferType The type of buffer to get
     * @return The buffer, or null if not found
     */
    private byte[] getBuffer(int bufferType) {
        // Return the appropriate buffer based on the buffer type
        var knownType = BufferType.fromInt(bufferType);
        if (knownType == null) {
            return handler.getCustomBuffer(bufferType);
        }
        switch (knownType) {
            case HTTP_REQUEST_BODY:
                return handler.getHttpRequestBody();
            case HTTP_RESPONSE_BODY:
                return handler.getHttpResponseBody();
            case DOWNSTREAM_DATA:
                return handler.getDownStreamData();
            case UPSTREAM_DATA:
                return handler.getUpstreamData();
            case HTTP_CALL_RESPONSE_BODY:
                return handler.getHttpCallResponseBody();
            case GRPC_RECEIVE_BUFFER:
                return handler.getGrpcReceiveBuffer();
            case PLUGIN_CONFIGURATION:
                return handler.getPluginConfig();
            case VM_CONFIGURATION:
                return handler.getVmConfig();
            case CALL_DATA:
                return handler.getFuncCallData();
        }
        return null;
    }

    /**
     * Set a buffer based on the buffer type.
     *
     * @param bufferType The type of buffer to set
     * @param buffer     The buffer to set
     * @return WasmResult indicating success or failure
     */
    private WasmResult setBuffer(int bufferType, byte[] buffer) {
        // Set the appropriate buffer based on the buffer type
        var knownType = BufferType.fromInt(bufferType);
        if (knownType == null) {
            return handler.setCustomBuffer(bufferType, buffer);
        }

        // TODO: check if the buffers that can be set may depend on the ABI version.
        switch (knownType) {
            case HTTP_REQUEST_BODY:
                return handler.setHttpRequestBody(buffer);
            case HTTP_RESPONSE_BODY:
                return handler.setHttpResponseBody(buffer);
            case DOWNSTREAM_DATA:
                return handler.setDownStreamData(buffer);
            case UPSTREAM_DATA:
                return handler.setUpstreamData(buffer);
            case HTTP_CALL_RESPONSE_BODY:
                return handler.setHttpCallResponseBody(buffer);
            case GRPC_RECEIVE_BUFFER:
                return handler.setGrpcReceiveBuffer(buffer);
            case CALL_DATA:
                return handler.setFuncCallData(buffer);
        }
        return WasmResult.NOT_FOUND;
    }

    // //////////////////////////////////////////////////////////////////////
    // HTTP fields
    // //////////////////////////////////////////////////////////////////////

    /**
     * Retrieves serialized size of all key-value pairs from the map mapType
     * <p>
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_header_map_size
     *
     * @param mapType    The type of map to set
     * @param returnSize Pointer to ruturn the size of the map data
     * @return WasmResult status code
     */
    @WasmExport
    int proxyGetHeaderMapSize(int mapType, int returnSize) {
        try {

            // Get the header map based on the map type
            ProxyMap header = getMap(mapType);
            if (header == null) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // to clone the headers so that they don't change on while we process them in the loop
            var cloneMap = new ArrayProxyMap(header);
            int totalBytesLen = U32_LEN; // Start with space for the count

            for (Map.Entry<String, String> entry : cloneMap.entries()) {
                String key = entry.getKey();
                String value = entry.getValue();
                totalBytesLen += U32_LEN + U32_LEN; // keyLen + valueLen
                totalBytesLen += key.length() + 1 + value.length() + 1; // key + \0 + value + \0
            }

            // Write the total size to the return size pointer
            putUint32(returnSize, totalBytesLen);

            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Get header map pairs and format them for WebAssembly memory().
     * <p>
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_header_map_pairs
     *
     * @param mapType        The type of map to get
     * @param returnDataPtr  Pointer to where the data address should be stored
     * @param returnDataSize Pointer to where the data size should be stored
     * @return WasmResult status code
     */
    @WasmExport
    int proxyGetHeaderMapPairs(int mapType, int returnDataPtr, int returnDataSize) {
        try {

            // Get the header map based on the map type
            ProxyMap header = getMap(mapType);
            if (header == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            var cloneMap = new ArrayList<Map.Entry<byte[], byte[]>>();
            int totalBytesLen = U32_LEN; // Start with space for the count

            totalBytesLen +=
                    header.streamBytes()
                            .mapToInt(
                                    entry -> {
                                        var key = entry.getKey();
                                        var value = entry.getValue();
                                        cloneMap.add(Map.entry(key, value));
                                        return U32_LEN
                                                + U32_LEN // keyLen + valueLen
                                                + key.length
                                                + 1
                                                + value.length
                                                + 1; // key + \0 + value + \0
                                    })
                            .sum();

            // Allocate memory in the WebAssembly instance
            int addr = malloc(totalBytesLen);

            // Write the number of entries to the allocated memory
            putUint32(addr, cloneMap.size());

            // Calculate pointers for lengths and data
            int lenPtr = addr + U32_LEN;
            int dataPtr = lenPtr + ((U32_LEN + U32_LEN) * cloneMap.size());

            // Write each key-value pair to memory
            for (Map.Entry<byte[], byte[]> entry : cloneMap) {
                var key = entry.getKey();
                var value = entry.getValue();

                // Write key length
                putUint32(lenPtr, key.length);
                lenPtr += U32_LEN;

                // Write value length
                putUint32(lenPtr, value.length);
                lenPtr += U32_LEN;

                // Write key bytes
                putMemory(dataPtr, key);
                dataPtr += key.length;

                // Write null terminator for key
                putByte(dataPtr, (byte) 0);
                dataPtr++;

                // Write value bytes
                putMemory(dataPtr, value);
                dataPtr += value.length;

                // Write null terminator for value
                putByte(dataPtr, (byte) 0);
                dataPtr++;
            }

            // Write the address to the return pointer
            putUint32(returnDataPtr, addr);

            // Write the total size to the return size pointer
            putUint32(returnDataSize, totalBytesLen);

            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Set header map pairs from WebAssembly memory().
     * <p>
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_set_header_map_pairs
     *
     * @param mapType The type of map to set
     * @param ptr     Pointer to the map data in WebAssembly memory
     * @param size    Size of the map data
     * @return WasmResult status code
     */
    @WasmExport
    int proxySetHeaderMapPairs(int mapType, int ptr, int size) {

        try {
            // Get the header map based on the map type
            ProxyMap headerMap = getMap(mapType);
            if (headerMap == null) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // Decode the map content and set each key-value pair
            ProxyMap newMap = decodeMap(ptr, size);
            for (Map.Entry<String, String> entry : newMap.entries()) {
                headerMap.put(entry.getKey(), entry.getValue());
            }

            return WasmResult.OK.getValue();
        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Get a value from a header map by key.
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_header_map_value
     *
     * @param mapType      The type of map to get from
     * @param keyDataPtr   Pointer to the key data in WebAssembly memory
     * @param keySize      Size of the key data
     * @param valueDataPtr Pointer to where the value data should be stored
     * @param valueSize    Size of the value data buffer
     * @return WasmResult status code
     */
    @WasmExport
    int proxyGetHeaderMapValue(
            int mapType, int keyDataPtr, int keySize, int valueDataPtr, int valueSize) {
        try {
            // Get the header map based on the map type
            ProxyMap headerMap = getMap(mapType);
            if (headerMap == null) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // Get key from memory
            String key = string(readMemory(keyDataPtr, keySize));

            // Get value from map
            String value = headerMap.get(key);
            if (value == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            // Copy value into WebAssembly memory
            copyIntoInstance(bytes(value), valueDataPtr, valueSize);
            return WasmResult.OK.getValue();

        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Add a value to a header map.
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_add_header_map_value
     *
     * @param mapType      The type of map to modify
     * @param keyDataPtr   Pointer to the key data in WebAssembly memory
     * @param keySize      Size of the key data
     * @param valueDataPtr Pointer to the value data in WebAssembly memory
     * @param valueSize    Size of the value data
     * @return WasmResult status code
     */
    @WasmExport
    int proxyAddHeaderMapValue(
            int mapType, int keyDataPtr, int keySize, int valueDataPtr, int valueSize) {
        try {
            // Get the header map based on the map type
            ProxyMap headerMap = getMap(mapType);
            if (headerMap == null) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // Get key from memory
            String key = string(readMemory(keyDataPtr, keySize));

            // Get value from memory
            String value = string(readMemory(valueDataPtr, valueSize));

            // Add value in map
            headerMap.add(key, value);

            return WasmResult.OK.getValue();

        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Replace a value in a header map.
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_replace_header_map_value
     *
     * @param mapType      The type of map to modify
     * @param keyDataPtr   Pointer to the key data in WebAssembly memory
     * @param keySize      Size of the key data
     * @param valueDataPtr Pointer to the value data in WebAssembly memory
     * @param valueSize    Size of the value data
     * @return WasmResult status code
     */
    @WasmExport
    int proxyReplaceHeaderMapValue(
            int mapType, int keyDataPtr, int keySize, int valueDataPtr, int valueSize) {
        try {
            // Get the header map based on the map type
            ProxyMap headerMap = getMap(mapType);
            if (headerMap == null) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // Get key from memory
            String key = string(readMemory(keyDataPtr, keySize));

            // Get value from memory
            String value = string(readMemory(valueDataPtr, valueSize));

            // Replace value in map
            headerMap.put(key, value);

            return WasmResult.OK.getValue();

        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Remove a value from a header map.
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_remove_header_map_value
     *
     * @param mapType    The type of map to modify
     * @param keyDataPtr Pointer to the key data in WebAssembly memory
     * @param keySize    Size of the key data
     * @return WasmResult status code
     */
    @WasmExport
    int proxyRemoveHeaderMapValue(int mapType, int keyDataPtr, int keySize) {
        try {
            // Get the header map based on the map type
            ProxyMap headerMap = getMap(mapType);
            if (headerMap == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            // Get key from memory
            String key = string(readMemory(keyDataPtr, keySize));
            if (key.isEmpty()) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            // Remove key from map
            headerMap.remove(key);

            return WasmResult.OK.getValue();

        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Get a header map based on the map type.
     *
     * @param mapType The type of map to get
     * @return The header map
     */
    private ProxyMap getMap(int mapType) {

        var knownType = MapType.fromInt(mapType);
        if (knownType == null) {
            return handler.getCustomHeaders(mapType);
        }

        switch (knownType) {
            case HTTP_REQUEST_HEADERS:
                return handler.getHttpRequestHeaders();
            case HTTP_REQUEST_TRAILERS:
                return handler.getHttpRequestTrailers();
            case HTTP_RESPONSE_HEADERS:
                return handler.getHttpResponseHeaders();
            case HTTP_RESPONSE_TRAILERS:
                return handler.getHttpResponseTrailers();
            case HTTP_CALL_RESPONSE_HEADERS:
                return handler.getHttpCallResponseHeaders();
            case HTTP_CALL_RESPONSE_TRAILERS:
                return handler.getHttpCallResponseTrailers();
            case GRPC_RECEIVE_INITIAL_METADATA:
                return handler.getGrpcReceiveInitialMetaData();
            case GRPC_RECEIVE_TRAILING_METADATA:
                return handler.getGrpcReceiveTrailerMetaData();
        }
        return null;
    }

    /**
     * Decodes a byte array containing map data into a Map of String key-value pairs.
     * <p>
     * The format is:
     * - First 4 bytes: number of entries (headerSize) in little endian
     * - For each entry:
     * - 4 bytes: key size in little endian
     * - 4 bytes: value size in little endian
     * - Then the actual data:
     * - key bytes (null terminated)
     * - value bytes (null terminated)
     *
     * @param addr     The memory address to read from
     * @param mem_size The size of memory to read
     * @return The decoded map containing string keys and values
     * @throws WasmException if there is an error accessing memory
     */
    private ProxyMap decodeMap(int addr, int mem_size) throws WasmException {
        if (mem_size < U32_LEN) {
            return new ArrayProxyMap();
        }

        // Read header size (number of entries)
        var mapSize = getUint32(addr);

        // Calculate start of data section
        // mapSize + (key1_size + value1_size) * mapSize
        long dataOffset = U32_LEN + (U32_LEN + U32_LEN) * mapSize;
        if (dataOffset >= mem_size) {
            return new ArrayProxyMap();
        }

        // Create result map with initial capacity
        var result = new ArrayProxyMap((int) mapSize);

        // Process each entry
        for (int i = 0; i < mapSize; i++) {

            // Calculate index for length values
            int keySizeOffset = U32_LEN + (U32_LEN + U32_LEN) * i;
            int valueSizeOffset = keySizeOffset + U32_LEN;

            // Read key and value sizes
            long keySize = getUint32(addr + keySizeOffset);
            long valueSize = getUint32(addr + valueSizeOffset);

            // Check if we have enough data for the key/value
            if (dataOffset >= mem_size || dataOffset + keySize + valueSize + 2 > mem_size) {
                break;
            }

            // Extract key
            String key = string(readMemory((int) (addr + dataOffset), (int) keySize));
            dataOffset += keySize + 1; // Skip null terminator

            // Extract value
            String value = string(readMemory((int) (addr + dataOffset), (int) valueSize));
            dataOffset += valueSize + 1;

            // Add to result map
            result.add(key, value);
        }

        return result;
    }

    // //////////////////////////////////////////////////////////////////////
    // Common HTTP and TCP stream operations
    // //////////////////////////////////////////////////////////////////////

    /**
     * Resumes processing of paused stream_type.
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/v0.2.0#proxy_continue_stream
     */
    @WasmExport
    int proxyContinueStream(int arg) {
        var streamType = StreamType.fromInt(arg);
        if (streamType == null) {
            return WasmResult.BAD_ARGUMENT.getValue();
        }
        WasmResult result = handler.setAction(streamType, Action.CONTINUE);
        return result.getValue();
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/v0.2.1#proxy_close_stream
     */
    @WasmExport
    int proxyCloseStream(int proxyStreamType) {
        // TODO: implement
        return WasmResult.UNIMPLEMENTED.getValue();
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/v0.2.1#proxy_get_status
     */
    @WasmExport
    int proxyGetStatus(
            int returnStatusCode, int returnStatusMessageData, int returnStatusMessageSize) {
        // TODO: implement
        return WasmResult.UNIMPLEMENTED.getValue();
    }

    // //////////////////////////////////////////////////////////////////////
    // TCP streams
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_new_connection
     */
    int proxyOnNewConnection(int arg0) {
        if (proxyOnNewConnectionFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnNewConnectionFn.apply(arg0)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_downstream_data
     */
    int proxyOnDownstreamData(int contextId, int dataSize, int endOfStream) {
        if (proxyOnDownstreamDataFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnDownstreamDataFn.apply(contextId, dataSize, endOfStream)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_downstream_connection_close
     */
    void proxyOnDownstreamConnectionClose(int arg0, int arg1) {
        if (proxyOnDownstreamConnectionCloseFn == null) {
            return;
        }
        proxyOnDownstreamConnectionCloseFn.apply(arg0, arg1);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_upstream_data
     */
    int proxyOnUpstreamData(int arg0, int arg1, int arg2) {
        if (proxyOnUpstreamDataFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnUpstreamDataFn.apply(arg0, arg1, arg2)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_upstream_connection_close
     */
    void proxyOnUpstreamConnectionClose(int arg0, int arg1) {
        if (proxyOnUpstreamConnectionCloseFn == null) {
            return;
        }
        proxyOnUpstreamConnectionCloseFn.apply(arg0, arg1);
    }

    // //////////////////////////////////////////////////////////////////////
    // HTTP streams
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_request_headers
     */
    int proxyOnRequestHeaders(int contextID, int headers, int endOfStream) {
        if (proxyOnRequestHeadersFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnRequestHeadersFn.apply(contextID, headers, endOfStream)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_request_body
     */
    int proxyOnRequestBody(int contextId, int bodySize, int arg2) {
        if (proxyOnRequestBodyFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnRequestBodyFn.apply(contextId, bodySize, arg2)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_request_trailers
     */
    int proxyOnRequestTrailers(int arg0, int arg1) {
        if (proxyOnRequestTrailersFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnRequestTrailersFn.apply(arg0, arg1)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_response_headers
     */
    int proxyOnResponseHeaders(int arg0, int arg1, int arg2) {
        if (proxyOnResponseHeadersFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnResponseHeadersFn.apply(arg0, arg1, arg2)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_response_body
     */
    int proxyOnResponseBody(int arg0, int arg1, int arg2) {
        if (proxyOnResponseBodyFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnResponseBodyFn.apply(arg0, arg1, arg2)[0];
        return (int) result;
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_response_trailers
     */
    int proxyOnResponseTrailers(int arg0, int arg1) {
        if (proxyOnResponseTrailersFn == null) {
            return Action.CONTINUE.getValue();
        }
        long result = proxyOnResponseTrailersFn.apply(arg0, arg1)[0];
        return (int) result;
    }

    /**
     * Send an HTTP response.
     * implements: https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_send_local_response
     *
     * @param responseCode             The HTTP response code
     * @param responseCodeDetailsData  Pointer to response code details in WebAssembly memory
     * @param responseCodeDetailsSize  Size of response code details
     * @param responseBodyData         Pointer to response body in WebAssembly memory
     * @param responseBodySize         Size of response body
     * @param additionalHeadersMapData Pointer to additional headers map in WebAssembly memory
     * @param additionalHeadersSize    Size of additional headers map
     * @param grpcStatus               The gRPC status code (-1 for non-gRPC responses)
     * @return WasmResult status code
     */
    @WasmExport
    int proxySendLocalResponse(
            int responseCode,
            int responseCodeDetailsData,
            int responseCodeDetailsSize,
            int responseBodyData,
            int responseBodySize,
            int additionalHeadersMapData,
            int additionalHeadersSize,
            int grpcStatus) {
        try {

            // Get response code details from memory
            byte[] responseCodeDetails = null;
            if (responseCodeDetailsSize > 0) {
                responseCodeDetails =
                        memory.readBytes(responseCodeDetailsData, responseCodeDetailsSize);
            }

            // Get response body from memory
            byte[] responseBody = new byte[0];
            if (responseBodySize > 0) {
                responseBody = memory.readBytes(responseBodyData, responseBodySize);
            }

            // Get and decode additional headers from memory
            ProxyMap additionalHeaders = decodeMap(additionalHeadersMapData, additionalHeadersSize);

            // Send the response through the handler
            WasmResult result =
                    handler.sendHttpResponse(
                            responseCode,
                            responseCodeDetails,
                            responseBody,
                            additionalHeaders,
                            grpcStatus);
            return result.getValue();

        } catch (WasmRuntimeException e) {
            return WasmResult.INVALID_MEMORY_ACCESS.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Resumes processing of paused HTTP request.
     * <p>
     * see: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.1.0/README.md#proxy_continue_request
     *
     * @deprecated in 0.2.0
     */
    @WasmExport
    @Deprecated(since = "0.2.0")
    void proxyContinueRequest() {
        handler.setAction(StreamType.REQUEST, Action.CONTINUE);
    }

    /**
     * Resumes processing of paused HTTP response.
     * <p>
     * see: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.1.0/README.md#proxy_continue_response
     *
     * @deprecated in 0.2.0
     */
    @WasmExport
    @Deprecated(since = "0.2.0")
    void proxyContinueResponse() {
        handler.setAction(StreamType.RESPONSE, Action.CONTINUE);
    }

    /**
     * Clears cached HTTP route.
     * <p>
     * see: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.1.0/README.md#proxy_clear_route_cache
     *
     * @deprecated in 0.2.0
     */
    @WasmExport
    @Deprecated(since = "0.2.0")
    void proxyClearRouteCache() {
        handler.clearRouteCache();
    }

    // //////////////////////////////////////////////////////////////////////
    // HTTP calls
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_http_call
     */
    @WasmExport
    int proxyHttpCall(
            int uriData,
            int uriSize,
            int headersData,
            int headersSize,
            int bodyData,
            int bodySize,
            int trailersData,
            int trailersSize,
            int timeout,
            int returnCalloutID) {

        try {
            var uri = string(readMemory(uriData, uriSize));
            var headers = decodeMap(headersData, headersSize);
            var body = readMemory(bodyData, bodySize);
            var trailers = decodeMap(trailersData, trailersSize);

            int calloutId = handler.httpCall(uri, headers, body, trailers, timeout);

            putUint32(returnCalloutID, calloutId);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/api7/wasm-nginx-module/blob/main/proxy_wasm_abi.md#proxy_dispatch_http_call
     */
    @WasmExport
    int proxyDispatchHttpCall(
            int upstreamNameData,
            int upstreamNameSize,
            int headersData,
            int headersSize,
            int bodyData,
            int bodySize,
            int trailersData,
            int trailersSize,
            int timeoutMilliseconds,
            int returnCalloutID) {

        try {
            var upstreamName = string(readMemory(upstreamNameData, upstreamNameSize));
            var headers = decodeMap(headersData, headersSize);
            var body = readMemory(bodyData, bodySize);
            var trailers = decodeMap(trailersData, trailersSize);

            int calloutId =
                    handler.dispatchHttpCall(
                            upstreamName, headers, body, trailers, timeoutMilliseconds);

            putUint32(returnCalloutID, calloutId);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_http_call_response
     */
    void proxyOnHttpCallResponse(int arg0, int arg1, int arg2, int arg3, int arg4) {
        if (proxyOnHttpCallResponseFn == null) {
            return;
        }
        proxyOnHttpCallResponseFn.apply(arg0, arg1, arg2, arg3, arg4);
    }

    // //////////////////////////////////////////////////////////////////////
    // gRPC calls
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_grpc_call
     */
    @WasmExport
    int proxyGrpcCall(
            int upstreamNameData,
            int upstreamNameSize,
            int serviceNameData,
            int serviceNameSize,
            int methodNameData,
            int methodNameSize,
            int serialized_initial_metadataData,
            int serialized_initial_metadataSize,
            int messageData,
            int messageSize,
            int timeout,
            int returnCalloutID) {

        try {
            var upstreamName = string(readMemory(upstreamNameData, upstreamNameSize));
            var serviceName = string(readMemory(serviceNameData, serviceNameSize));
            var methodName = string(readMemory(methodNameData, methodNameSize));
            var initialMetadata =
                    decodeMap(serialized_initial_metadataData, serialized_initial_metadataSize);
            var message = readMemory(messageData, messageSize);

            int callId =
                    handler.grpcCall(
                            upstreamName,
                            serviceName,
                            methodName,
                            initialMetadata,
                            message,
                            timeout);
            putUint32(returnCalloutID, callId);
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_grpc_stream
     */
    @WasmExport
    int proxyGrpcStream(
            int upstreamNameData,
            int upstreamNameSize,
            int serviceNameData,
            int serviceNameSize,
            int methodNameData,
            int methodNameSize,
            int serialized_initial_metadataData,
            int serialized_initial_metadataSize,
            int returnStreamId) {

        try {
            var upstreamName = string(readMemory(upstreamNameData, upstreamNameSize));
            var serviceName = string(readMemory(serviceNameData, serviceNameSize));
            var methodName = string(readMemory(methodNameData, methodNameSize));
            var initialMetadata =
                    decodeMap(serialized_initial_metadataData, serialized_initial_metadataSize);

            int streamId =
                    handler.grpcStream(upstreamName, serviceName, methodName, initialMetadata);
            putUint32(returnStreamId, streamId);
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_grpc_send
     */
    @WasmExport
    int proxyGrpcSend(int streamId, int messageData, int messageSize, int endStream) {
        try {
            byte[] message = readMemory(messageData, messageSize);
            WasmResult result = handler.grpcSend(streamId, message, endStream);
            return result.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_grpc_cancel
     */
    @WasmExport
    int proxyGrpcCancel(int callOrstreamId) {
        WasmResult result = handler.grpcCancel(callOrstreamId);
        return result.getValue();
    }

    /**
     * https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_grpc_close
     */
    @WasmExport
    int proxyGrpcClose(int callOrstreamId) {
        WasmResult result = handler.grpcClose(callOrstreamId);
        return result.getValue();
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_grpc_receive_initial_metadata
     */
    void proxyOnGrpcReceiveInitialMetadata(int contextId, int callId, int numElements) {
        if (proxyOnGrpcReceiveInitialMetadataFn == null) {
            return;
        }
        proxyOnGrpcReceiveInitialMetadataFn.apply(contextId, callId, numElements);
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_grpc_receive
     */
    void proxyOnGrpcReceive(int contextId, int callId, int messageSize) {
        if (proxyOnGrpcReceiveFn == null) {
            return;
        }
        proxyOnGrpcReceiveFn.apply(contextId, callId, messageSize);
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_grpc_receive_trailing_metadata
     */
    void proxyOnGrpcReceiveTrailingMetadata(int contextId, int callId, int numElements) {
        if (proxyOnGrpcReceiveTrailingMetadataFn == null) {
            return;
        }
        proxyOnGrpcReceiveTrailingMetadataFn.apply(contextId, callId, numElements);
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_grpc_close
     */
    void proxyOnGrpcClose(int contextId, int callId, int statusCode) {
        if (proxyOnGrpcCloseFn == null) {
            return;
        }
        proxyOnGrpcCloseFn.apply(contextId, callId, statusCode);
    }

    // //////////////////////////////////////////////////////////////////////
    // Shared Key-Value Store
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_set_shared_data
     */
    @WasmExport
    int proxySetSharedData(int keyDataPtr, int keySize, int valueDataPtr, int valueSize, int cas) {
        try {
            // Get key from memory
            String key = string(readMemory(keyDataPtr, keySize));

            // Get value from memory
            byte[] value = readMemory(valueDataPtr, valueSize);

            // Set shared data value using handler
            WasmResult result = handler.setSharedData(key, value, cas);
            return result.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_shared_data
     */
    @WasmExport
    int proxyGetSharedData(
            int keyDataPtr, int keySize, int returnValueData, int returnValueSize, int returnCas) {
        try {
            // Get key from memory
            String key = string(readMemory(keyDataPtr, keySize));

            // Get shared data value using handler
            SharedData value = handler.getSharedData(key);
            if (value == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            try {
                if (value.data().length != 0) {
                    int addr = malloc(value.data().length);
                    putMemory(addr, value.data());
                    putUint32(returnValueData, addr);
                } else {
                    putUint32(returnValueData, 0);
                }
                putUint32(returnValueSize, value.data().length);
            } catch (WasmException e) {
                throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
            }
            putUint32(returnCas, value.cas());
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    // //////////////////////////////////////////////////////////////////////
    // Shared Queues
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_register_shared_queue
     */
    @WasmExport
    int proxyRegisterSharedQueue(int queueNameDataPtr, int queueNameSize, int returnQueueId) {
        try {
            // Get queue name from memory
            String queueName = string(readMemory(queueNameDataPtr, queueNameSize));

            var vmId = handler.getProperty(List.of("vm_id"));
            if (vmId == null) {
                return WasmResult.INTERNAL_FAILURE.getValue();
            }

            // Register shared queue using handler
            int queueId = handler.registerSharedQueue(new QueueName(string(vmId), queueName));
            putUint32(returnQueueId, queueId);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_resolve_shared_queue
     */
    @WasmExport
    int proxyResolveSharedQueue(
            int vmIdDataPtr,
            int vmIdSize,
            int queueNameDataPtr,
            int queueNameSize,
            int returnQueueId) {
        try {
            // Get vm id from memory
            String vmId = string(readMemory(vmIdDataPtr, vmIdSize));
            // Get queue name from memory
            String queueName = string(readMemory(queueNameDataPtr, queueNameSize));

            // Resolve shared queue using handler
            int queueId = handler.resolveSharedQueue(new QueueName(vmId, queueName));
            putUint32(returnQueueId, queueId);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_enqueue_shared_queue
     */
    @WasmExport
    int proxyEnqueueSharedQueue(int queueId, int valueDataPtr, int valueSize) {
        try {
            // Get value from memory
            byte[] value = readMemory(valueDataPtr, valueSize);

            // Enqueue shared queue using handler
            WasmResult result = handler.enqueueSharedQueue(queueId, value);
            return result.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_dequeue_shared_queue
     */
    @WasmExport
    int proxyDequeueSharedQueue(int queueId, int returnValueData, int returnValueSize) {
        try {
            // Dequeue shared queue using handler
            byte[] value = handler.dequeueSharedQueue(queueId);
            if (value == null) {
                return WasmResult.EMPTY.getValue();
            }

            try {
                if (value.length != 0) {
                    int addr = malloc(value.length);
                    putMemory(addr, value);
                    putUint32(returnValueData, addr);
                } else {
                    putUint32(returnValueData, 0);
                }
                putUint32(returnValueSize, value.length);
            } catch (WasmException e) {
                throw new WasmException(WasmResult.INVALID_MEMORY_ACCESS);
            }
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_on_queue_ready
     */
    void proxyOnQueueReady(int arg0, int arg1) {
        if (proxyOnQueueReadyFn == null) {
            return;
        }
        proxyOnQueueReadyFn.apply(arg0, arg1);
    }

    // //////////////////////////////////////////////////////////////////////
    // Metrics
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_define_metric
     */
    @WasmExport
    int proxyDefineMetric(int metricType, int nameDataPtr, int nameSize, int returnMetricId) {
        try {
            MetricType type = MetricType.fromInt(metricType);
            if (type == null) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            var name = string(readMemory(nameDataPtr, nameSize));
            int metricId = handler.defineMetric(type, name);
            putUint32(returnMetricId, metricId);
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_record_metric
     */
    @WasmExport
    int proxyRecordMetric(int metricId, long value) {
        WasmResult result = handler.recordMetric(metricId, value);
        return result.getValue();
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_increment_metric
     */
    @WasmExport
    int proxyIncrementMetric(int metricId, long value) {
        WasmResult result = handler.incrementMetric(metricId, value);
        return result.getValue();
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_metric
     */
    @WasmExport
    int proxyGetMetric(int metricId, int returnValuePtr) {
        try {
            var result = handler.getMetric(metricId);
            putUint32(returnValuePtr, (int) result);
            return WasmResult.OK.getValue();
        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * Remove a defined metric.
     * <p>
     * Not defined in the spec, but seems useful to have.
     */
    @WasmExport
    int proxyRemoveMetric(int metricId) {
        WasmResult result = handler.removeMetric(metricId);
        return result.getValue();
    }

    // //////////////////////////////////////////////////////////////////////
    // Properties
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_get_property
     */
    @WasmExport
    int proxyGetProperty(int keyPtr, int keySize, int returnValueData, int returnValueSize) {
        try {
            // Get key from memory
            byte[] keyBytes = readMemory(keyPtr, keySize);
            if (keyBytes.length == 0) {
                return WasmResult.BAD_ARGUMENT.getValue();
            }

            var path = split(string(keyBytes), '\u0000');

            // Get property value using handler
            byte[] value = handler.getProperty(path);
            if (value == null) {
                return WasmResult.NOT_FOUND.getValue();
            }

            copyIntoInstance(value, returnValueData, returnValueSize);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_set_property
     */
    @WasmExport
    int proxySetProperty(int pathDataPtr, int pathSize, int valueDataPtr, int valueSize) {
        try {
            // Get key from memory
            var path = split(string(readMemory(pathDataPtr, pathSize)), '\u0000');

            // Get value from memory
            var value = readMemory(valueDataPtr, valueSize);

            // Set property value using handler
            WasmResult result = handler.setProperty(path, value);
            return result.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    // //////////////////////////////////////////////////////////////////////
    // Foreign function interface (FFI)
    // //////////////////////////////////////////////////////////////////////

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_call_foreign_function
     */
    @WasmExport
    int proxyCallForeignFunction(
            int nameDataPtr,
            int nameSize,
            int argumentDataPtr,
            int argumentSize,
            int returnResultsPtr,
            int returnResultsSizePtr) {
        try {
            var name = string(readMemory(nameDataPtr, nameSize));
            var argument = readMemory(argumentDataPtr, argumentSize);

            var func = handler.getForeignFunction(name);
            if (func == null) {
                return WasmResult.NOT_FOUND.getValue();
            }
            var result = func.apply(argument);

            // Allocate memory in the WebAssembly instance
            int addr = malloc(result.length);
            putMemory(addr, result);
            // Write the address to the return pointer
            putUint32(returnResultsPtr, addr);
            // Write the length to the return size pointer
            putUint32(returnResultsSizePtr, result.length);
            return WasmResult.OK.getValue();

        } catch (WasmException e) {
            return e.result().getValue();
        }
    }

    /**
     * implements https://github.com/proxy-wasm/spec/tree/main/abi-versions/vNEXT#proxy_call_foreign_function
     */
    void proxyOnForeignFunction(int contextId, int functionId, int argumentsSize) {
        if (proxyOnForeignFunctionFn == null) {
            return;
        }
        proxyOnForeignFunctionFn.apply(contextId, functionId, argumentsSize);
    }

    @WasmExport
    void emscriptenNotifyMemoryGrowth(int size) {}

    public HostFunction[] toHostFunctions() {
        var functions = new ArrayList<>(List.of(ABI_ModuleFactory.toHostFunctions(this)));

        HostFunction[] wasiFunctions = new ABI_WASI(handler).toHostFunctions();
        functions.addAll(List.of(wasiFunctions));
        functions.addAll(List.of(Helpers.withModuleName(wasiFunctions, "wasi_unstable")));

        return functions.toArray(new HostFunction[0]);
    }
}
