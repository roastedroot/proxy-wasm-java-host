package io.roastedroot.proxywasm.jaxrs.example;

import io.roastedroot.proxywasm.jaxrs.WasmPlugin;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/")
public class Resources {

    @Path("/test")
    @GET
    @WasmPlugin("example") // filter with example wasm plugin
    public String example() {
        return "Hello World";
    }
}
