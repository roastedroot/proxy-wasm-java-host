package io.roastedroot.proxywasm.jaxrs;

import static io.roastedroot.proxywasm.Helpers.string;

import io.roastedroot.proxywasm.Action;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.plugin.HttpContext;
import io.roastedroot.proxywasm.plugin.Plugin;
import io.roastedroot.proxywasm.plugin.Pool;
import io.roastedroot.proxywasm.plugin.SendResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class WasmPluginFilter
        implements ContainerRequestFilter, WriterInterceptor, ContainerResponseFilter {
    private static final String FILTER_CONTEXT_PROPERTY_NAME = HttpContext.class.getName() + ":";

    private final List<Pool> pluginPools;

    public WasmPluginFilter(List<Pool> pluginPool) {
        this.pluginPools = List.copyOf(pluginPool);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        for (var pluginPool : pluginPools) {
            filter(requestContext, pluginPool);
        }
    }

    private void filter(ContainerRequestContext requestContext, Pool pluginPool)
            throws IOException {
        Plugin plugin;
        try {
            plugin = pluginPool.borrow();
        } catch (StartException e) {
            requestContext.abortWith(interalServerError());
            return;
        }

        plugin.lock();
        try {
            var requestAdaptor =
                    (JaxrsHttpRequestAdaptor)
                            plugin.getServerAdaptor().httpRequestAdaptor(requestContext);
            var httpContext = plugin.createHttpContext(requestAdaptor);
            requestContext.setProperty(
                    FILTER_CONTEXT_PROPERTY_NAME + pluginPool.name(), httpContext);

            // the plugin may not be interested in the request headers.
            if (httpContext.context().hasOnRequestHeaders()) {

                requestAdaptor.setRequestContext(requestContext);
                var action = httpContext.context().callOnRequestHeaders(false);
                if (action == Action.PAUSE) {
                    httpContext.maybePause();
                }

                // does the plugin want to respond early?
                var sendResponse = httpContext.consumeSentHttpResponse();
                if (sendResponse != null) {
                    requestContext.abortWith(toResponse(sendResponse));
                    return;
                }
            }

            // the plugin may not be interested in the request body.
            if (httpContext.context().hasOnRequestBody()) {

                // TODO: find out if it's more efficient to read the body in chunks and do multiple
                // callOnRequestBody calls.
                byte[] bytes = requestContext.getEntityStream().readAllBytes();

                httpContext.setHttpRequestBody(bytes);
                var action = httpContext.context().callOnRequestBody(true);
                bytes = httpContext.getHttpRequestBody();
                if (action == Action.CONTINUE) {
                    // continue means plugin is done reading the body.
                    httpContext.setHttpRequestBody(null);
                } else {
                    httpContext.maybePause();
                }

                // does the plugin want to respond early?
                var sendResponse = httpContext.consumeSentHttpResponse();
                if (sendResponse != null) {
                    requestContext.abortWith(toResponse(sendResponse));
                    return;
                }

                // plugin may have modified the body
                requestContext.setEntityStream(new java.io.ByteArrayInputStream(bytes));
            }

        } finally {
            plugin.unlock(); // allow another request to use the plugin.
        }
    }

    private static Response interalServerError() {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    @Override
    public void filter(
            ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        for (var pluginPool : pluginPools) {
            filter(requestContext, responseContext, pluginPool);
        }
    }

    private void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext,
            Pool pluginPool)
            throws IOException {
        var httpContext =
                (HttpContext)
                        requestContext.getProperty(
                                FILTER_CONTEXT_PROPERTY_NAME + pluginPool.name());
        if (httpContext == null) {
            throw new WebApplicationException(interalServerError());
        }

        // the plugin may not be interested in the request headers.
        if (httpContext.context().hasOnResponseHeaders()) {
            try {
                httpContext.plugin().lock();

                var requestAdaptor = (JaxrsHttpRequestAdaptor) httpContext.requestAdaptor();
                requestAdaptor.setResponseContext(responseContext);
                var action = httpContext.context().callOnResponseHeaders(false);
                if (action == Action.PAUSE) {
                    httpContext.maybePause();
                }

                // does the plugin want to respond early?
                var sendResponse = httpContext.consumeSentHttpResponse();
                if (sendResponse != null) {
                    Response response = toResponse(sendResponse);
                    responseContext.setStatus(response.getStatus());
                    responseContext.getHeaders().putAll(response.getHeaders());
                    responseContext.setEntity(response.getEntity());
                    return;
                }

                // aroundWriteTo won't be called if there is no entity to send.
                if (responseContext.getEntity() == null
                        && httpContext.context().hasOnResponseBody()) {

                    byte[] bytes = new byte[0];
                    httpContext.setHttpResponseBody(bytes);
                    action = httpContext.context().callOnResponseBody(true);
                    bytes = httpContext.getHttpResponseBody();
                    if (action == Action.CONTINUE) {
                        // continue means plugin is done reading the body.
                        httpContext.setHttpResponseBody(null);
                    } else {
                        httpContext.maybePause();
                    }

                    // does the plugin want to respond early?
                    sendResponse = httpContext.consumeSentHttpResponse();
                    if (sendResponse != null) {
                        Response response = toResponse(sendResponse);
                        responseContext.setStatus(response.getStatus());
                        responseContext.getHeaders().putAll(response.getHeaders());
                        responseContext.setEntity(response.getEntity());
                        return;
                    }
                }

            } finally {
                // allow other request to use the plugin.
                httpContext.plugin().unlock();
            }
        }
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext ctx)
            throws IOException, WebApplicationException {

        try {

            var original = ctx.getOutputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ctx.setOutputStream(baos);
            ctx.proceed();

            byte[] bytes = baos.toByteArray();

            for (var pluginPool : pluginPools) {
                var httpContext =
                        (HttpContext)
                                ctx.getProperty(FILTER_CONTEXT_PROPERTY_NAME + pluginPool.name());
                if (httpContext == null) {
                    throw new WebApplicationException(interalServerError());
                }

                httpContext.plugin().lock();

                // the plugin may not be interested in the request body.
                if (!httpContext.context().hasOnResponseBody()) {
                    ctx.proceed();
                }

                httpContext.setHttpResponseBody(bytes);
                var action = httpContext.context().callOnResponseBody(true);
                bytes = httpContext.getHttpResponseBody();
                if (action == Action.CONTINUE) {
                    // continue means plugin is done reading the body.
                    httpContext.setHttpResponseBody(null);
                } else {
                    httpContext.maybePause();
                }

                // does the plugin want to respond early?
                var sendResponse = httpContext.consumeSentHttpResponse();
                if (sendResponse != null) {
                    throw new WebApplicationException(toResponse(sendResponse));
                }
            }

            // plugin may have modified the body
            original.write(bytes);

        } finally {
            for (var pluginPool : pluginPools) {
                var httpContext =
                        (HttpContext)
                                ctx.getProperty(FILTER_CONTEXT_PROPERTY_NAME + pluginPool.name());

                // allow other request to use the plugin.
                httpContext.context().close();
                httpContext.plugin().unlock();

                // TODO: will aroundWriteTo always get called so that we can avoid leaking the
                // plugin?
                pluginPool.release(httpContext.plugin());
            }
        }
    }

    public Response toResponse(SendResponse other) {
        Response.ResponseBuilder builder =
                Response.status(other.statusCode(), string(other.statusCodeDetails()));
        if (other.headers() != null) {
            for (var entry : other.headers().entries()) {
                builder = builder.header(entry.getKey(), entry.getValue());
            }
        }
        builder.entity(other.body());
        return builder.build();
    }
}
