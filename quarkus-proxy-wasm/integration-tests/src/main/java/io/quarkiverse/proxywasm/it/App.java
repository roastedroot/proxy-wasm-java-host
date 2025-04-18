package io.quarkiverse.proxywasm.it;

import com.dylibso.chicory.experimental.aot.AotMachine;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.google.gson.Gson;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.plugin.Plugin;
import io.roastedroot.proxywasm.plugin.PluginFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.nio.file.Path;
import java.util.Map;

@ApplicationScoped
public class App {

    public static final String EXAMPLES_DIR = "../../proxy-wasm-java-host/src/test";
    private static final Gson gson = new Gson();

    public static WasmModule parseTestModule(String file) {
        return Parser.parse(Path.of(EXAMPLES_DIR + file));
    }

    @Produces
    public PluginFactory headerTests() throws StartException {
        return () ->
                Plugin.builder()
                        .withName("headerTests")
                        .withLogger(new MockLogger("headerTests"))
                        .withPluginConfig(gson.toJson(Map.of("type", "headerTests")))
                        .build(parseTestModule("/go-examples/unit_tester/main.wasm"));
    }

    @Produces
    public PluginFactory headerTestsNotShared() throws StartException {
        return () ->
                Plugin.builder()
                        .withName("headerTestsNotShared")
                        .withShared(false)
                        .withLogger(new MockLogger("headerTestsNotShared"))
                        .withPluginConfig(gson.toJson(Map.of("type", "headerTests")))
                        .withMachineFactory(AotMachine::new)
                        .build(parseTestModule("/go-examples/unit_tester/main.wasm"));
    }

    @Produces
    public PluginFactory tickTests() throws StartException {
        return () ->
                Plugin.builder()
                        .withName("tickTests")
                        .withLogger(new MockLogger("tickTests"))
                        .withPluginConfig(gson.toJson(Map.of("type", "tickTests")))
                        .withMachineFactory(AotMachine::new)
                        .build(parseTestModule("/go-examples/unit_tester/main.wasm"));
    }

    @Produces
    public PluginFactory ffiTests() throws StartException {
        return () ->
                Plugin.builder()
                        .withName("ffiTests")
                        .withLogger(new MockLogger("ffiTests"))
                        .withPluginConfig(
                                gson.toJson(Map.of("type", "ffiTests", "function", "reverse")))
                        .withForeignFunctions(Map.of("reverse", App::reverse))
                        .withMachineFactory(AotMachine::new)
                        .build(parseTestModule("/go-examples/unit_tester/main.wasm"));
    }

    public static byte[] reverse(byte[] data) {
        byte[] reversed = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            reversed[i] = data[data.length - 1 - i];
        }
        return reversed;
    }

    @Produces
    public PluginFactory httpCallTests() throws StartException {
        return () ->
                Plugin.builder()
                        .withName("httpCallTests")
                        .withLogger(new MockLogger("httpCallTests"))
                        .withPluginConfig(
                                gson.toJson(
                                        Map.of(
                                                "type", "httpCallTests",
                                                "upstream", "web_service",
                                                "path", "/ok")))
                        .withUpstreams(Map.of("web_service", "localhost:8081"))
                        .withMachineFactory(AotMachine::new)
                        .build(parseTestModule("/go-examples/unit_tester/main.wasm"));
    }
}
