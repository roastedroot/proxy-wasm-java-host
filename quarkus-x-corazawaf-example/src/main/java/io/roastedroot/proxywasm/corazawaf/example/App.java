package io.roastedroot.proxywasm.corazawaf.example;

import com.dylibso.chicory.experimental.aot.AotMachine;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.proxywasm.LogHandler;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.plugin.Plugin;
import io.roastedroot.proxywasm.plugin.PluginFactory;
import io.roastedroot.proxywasm.plugin.SimpleMetricsHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class App {

    private static WasmModule module =
            Parser.parse(App.class.getResourceAsStream("coraza-proxy-wasm.wasm"));

    static final String CONFIG;

    static {
        try (InputStream is = App.class.getResourceAsStream("waf-config.json")) {
            CONFIG = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final boolean DEBUG = "true".equals(System.getenv("DEBUG"));

    @Produces
    public PluginFactory waf() throws StartException {
        return () ->
                Plugin.builder()
                        .withName("waf")
                        .withLogger(DEBUG ? LogHandler.SYSTEM : null)
                        .withPluginConfig(CONFIG)
                        .withMetricsHandler(new SimpleMetricsHandler())
                        .withMachineFactory(AotMachine::new)
                        .build(module);
    }
}
