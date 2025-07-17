package io.roastedroot.proxywasm.internal;

import com.dylibso.chicory.annotations.Buffer;
import com.dylibso.chicory.annotations.HostModule;
import com.dylibso.chicory.annotations.WasmExport;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import io.roastedroot.proxywasm.LogLevel;
import io.roastedroot.proxywasm.WasmException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Implements a restricted subset of the WASI ABI, specifically so that a WASM module can
 * be linked with WASI.  Only the functions documented by the proxy-wasm spec will work.  All other
 * function will result in errors.
 */
@HostModule("wasi_snapshot_preview1")
public class ABI_WASI {

    // We only need this field so we can delegate a few function calls.  Maybe we should just copy
    // over those
    // implementations.
    final WasiPreview1 wasi =
            WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();

    private final Handler handler;

    public ABI_WASI(Handler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    ////////////////////////////////////////////////////////////////////////////
    // These functions are documented by the proxy-wasm spec. Implement them
    // as described there.
    ////////////////////////////////////////////////////////////////////////////

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1fd_write
     */
    @WasmExport
    public int fdWrite(Memory memory, int fd, int iovs, int iovsLen, int nwrittenPtr) {
        LogLevel level = null;
        switch (fd) {
            case 1:
                // stdout: log at info level
                level = LogLevel.INFO;
                break;
            case 2:
                // stderr: log at error level
                level = LogLevel.ERROR;
                break;
            default:
                // BADF
                return 8;
        }

        var totalWritten = 0;
        for (var i = 0; i < iovsLen; i++) {
            var base = iovs + (i * 8);
            var iovBase = memory.readInt(base);
            var iovLen = memory.readInt(base + 4);
            var data = memory.readBytes(iovBase, iovLen);
            try {
                handler.log(level, new String(data, StandardCharsets.UTF_8));
                totalWritten += iovLen;
            } catch (WasmException e) {
                return 29; // EIO
            }
        }

        memory.writeI32(nwrittenPtr, totalWritten);
        return 0; // ESUCCESS
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1clock_time_get
     */
    @WasmExport
    public int clockTimeGet(Memory memory, int clockId, long precision, int resultPtr) {
        return wasi.clockTimeGet(memory, clockId, precision, resultPtr);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1random_get
     */
    @WasmExport
    public int randomGet(Memory memory, int buf, int bufLen) {
        return wasi.randomGet(memory, buf, bufLen);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1environ_sizes_get
     */
    @WasmExport
    int environSizesGet(Memory memory, int return_num_elements, int return_buffer_size) {
        return wasi.environSizesGet(memory, return_num_elements, return_buffer_size);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1environ_get
     */
    @WasmExport
    int environGet(Memory memory, int return_array, int return_buffer) {
        return wasi.environGet(memory, return_array, return_buffer);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1args_sizes_get
     */
    @WasmExport
    public int argsSizesGet(Memory memory, int argc, int argvBufSize) {
        return wasi.argsSizesGet(memory, argc, argvBufSize);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1args_get
     */
    @WasmExport
    int argsGet(Memory memory, int return_argv, int return_argv_size) {
        return wasi.argsGet(memory, return_argv, return_argv_size);
    }

    /**
     * implements: https://github.com/proxy-wasm/spec/blob/main/abi-versions/v0.2.1/README.md#wasi_snapshot_preview1proc_exit
     */
    @WasmExport
    void procExit(int exit_code) {
        throw new WasmRuntimeException("unsupported");
    }

    public HostFunction[] toHostFunctions() {
        return ABI_WASI_ModuleFactory.toHostFunctions(this);
    }

    ////////////////////////////////////////////////////////////////////////////
    // The following function should technically not be getting called, but
    // we have seen some go modules that call them, so we implement them so that they
    // will not crash out.
    ////////////////////////////////////////////////////////////////////////////
    @WasmExport
    public int pollOneoff(
            Memory memory, int inPtr, int outPtr, int nsubscriptions, int neventsPtr) {
        memory.writeI32(neventsPtr, 0);
        return 0; // ESUCCESS
    }

    @WasmExport
    public int fdFdstatGet(Memory memory, int fd, int buf) {
        return 8; // EBADF
    }

    @WasmExport
    public int fdPrestatGet(Memory memory, int fd, int buf) {
        return 8; // EBADF
    }

    ////////////////////////////////////////////////////////////////////////////
    // Have not seen these get called so just throw an exception to simplify
    // the implementation.  If you see these getting called, please open an issue
    ////////////////////////////////////////////////////////////////////////////

    @WasmExport
    int fdClose(int fd) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int adapterCloseBadfd(int fd) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int adapterOpenBadfd(int fd) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int clockResGet(Memory memory, int clockId, int resultPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdAdvise(int fd, long offset, long len, int advice) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdAllocate(int fd, long offset, long len) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdDatasync(int fd) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdFdstatSetFlags(int fd, int flags) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdFdstatSetRights(int fd, long rightsBase, long rightsInheriting) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdFilestatGet(Memory memory, int fd, int buf) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdFilestatSetSize(int fd, long size) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdFilestatSetTimes(int fd, long accessTime, long modifiedTime, int fstFlags) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdPread(Memory memory, int fd, int iovs, int iovsLen, long offset, int nreadPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdPrestatDirName(Memory memory, int fd, int path, int pathLen) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdPwrite(
            Memory memory, int fd, int iovs, int iovsLen, long offset, int nwrittenPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdRead(Memory memory, int fd, int iovs, int iovsLen, int nreadPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdReaddir(
            Memory memory, int dirFd, int buf, int bufLen, long cookie, int bufUsedPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdRenumber(int from, int to) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdSeek(Memory memory, int fd, long offset, int whence, int newOffsetPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdSync(int fd) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int fdTell(Memory memory, int fd, int offsetPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathCreateDirectory(int dirFd, @Buffer String rawPath) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathFilestatGet(
            Memory memory, int dirFd, int lookupFlags, @Buffer String rawPath, int buf) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathFilestatSetTimes(
            int fd,
            int lookupFlags,
            @Buffer String rawPath,
            long accessTime,
            long modifiedTime,
            int fstFlags) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathLink(
            int oldFd,
            int oldFlags,
            @Buffer String rawOldPath,
            int newFd,
            @Buffer String rawNewPath) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathOpen(
            Memory memory,
            int dirFd,
            int lookupFlags,
            @Buffer String rawPath,
            int openFlags,
            long rightsBase,
            long rightsInheriting,
            int fdFlags,
            int fdPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathReadlink(
            Memory memory, int dirFd, @Buffer String rawPath, int buf, int bufLen, int bufUsedPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathRemoveDirectory(int dirFd, @Buffer String rawPath) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathRename(
            int oldFd, @Buffer String oldRawPath, int newFd, @Buffer String newRawPath) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathSymlink(@Buffer String oldRawPath, int dirFd, @Buffer String newRawPath) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int pathUnlinkFile(int dirFd, @Buffer String rawPath) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int procRaise(int sig) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int schedYield() {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int sockAccept(int sock, int fdFlags, int roFdPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int sockRecv(
            int sock, int riDataPtr, int riDataLen, int riFlags, int roDataLenPtr, int roFlagsPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int sockSend(int sock, int siDataPtr, int siDataLen, int siFlags, int retDataLenPtr) {
        throw new WasmRuntimeException("unsupported");
    }

    @WasmExport
    public int sockShutdown(int sock, int how) {
        throw new WasmRuntimeException("unsupported");
    }
}
