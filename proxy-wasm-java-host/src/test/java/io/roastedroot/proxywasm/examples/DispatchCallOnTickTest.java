package io.roastedroot.proxywasm.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.internal.ProxyWasm;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Java port of https://github.com/proxy-wasm/proxy-wasm-go-sdk/blob/ab4161dcf9246a828008b539a82a1556cf0f2e24/examples/dispatch_call_on_tick/main_test.go
 */
public class DispatchCallOnTickTest {
    private static final WasmModule module =
            Parser.parse(Path.of("./src/test/go-examples/dispatch_call_on_tick/main.wasm"));

    static int tickMilliseconds = 100;

    @Test
    public void testOnTick() throws StartException {
        var handler = new MockHandler();
        ProxyWasm.Builder builder =
                ProxyWasm.builder()
                        .withPluginHandler(handler)
                        .withMachineFactory(MachineFactoryCompiler::compile);
        try (var host = builder.build(module)) {
            assertEquals(tickMilliseconds, handler.getTickPeriodMilliseconds());

            for (int i = 1; i <= 10; i++) {
                host.tick(); // call OnTick
            }

            assertEquals(10, handler.getHttpCalls().size());
            handler.getHttpCalls().entrySet().stream()
                    .forEach(
                            entry -> {
                                host.sendHttpCallResponse(
                                        entry.getKey(), Map.of(), Map.of(), new byte[0]);
                            });

            // Check Envoy logs.
            for (int i = 1; i <= 10; i++) {
                handler.assertLogsContain(
                        String.format("called %d for contextID=%d", i, host.contextId()));
            }
        }
    }
}
