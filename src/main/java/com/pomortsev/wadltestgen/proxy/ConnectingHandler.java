package com.pomortsev.wadltestgen.proxy;

import org.apache.http.*;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpClientHandler;
import org.apache.http.params.DefaultedHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ConnectingHandler implements NHttpClientHandler {

    private final HttpProcessor httpProcessor;
    private final ConnectionReuseStrategy connStrategy;
    private final HttpParams params;

    public ConnectingHandler(
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy connStrategy,
            final HttpParams params) {
        super();
        this.httpProcessor = httpProcessor;
        this.connStrategy = connStrategy;
        this.params = params;
    }

    public void connected(final NHttpClientConnection conn, final Object attachment) {
        System.out.println(conn + " [proxy->origin] conn open");

        // The shared state object is expected to be passed as an attachment
        ProxyTask proxyTask = (ProxyTask) attachment;

        synchronized (proxyTask) {
            ConnState connState = proxyTask.getOriginState();
            if (connState != ConnState.IDLE) {
                throw new IllegalStateException("Illegal target connection state: " + connState);
            }

            // Set origin IO control handle
            proxyTask.setOriginIOControl(conn);
            // Store the state object in the context
            HttpContext context = conn.getContext();
            context.setAttribute(ProxyTask.ATTRIB, proxyTask);
            // Update connection state
            proxyTask.setOriginState(ConnState.CONNECTED);

            if (proxyTask.getRequest() != null) {
                conn.requestOutput();
            }
        }
    }

    public void requestReady(final NHttpClientConnection conn) {
        System.out.println(conn + " [proxy->origin] request ready");

        HttpContext context = conn.getContext();
        ProxyTask proxyTask = (ProxyTask) context.getAttribute(ProxyTask.ATTRIB);

        synchronized (proxyTask) {
            ConnState connState = proxyTask.getOriginState();
            if (connState == ConnState.REQUEST_SENT
                    || connState == ConnState.REQUEST_BODY_DONE) {
                // Request sent but no response available yet
                return;
            }

            if (connState != ConnState.IDLE
                    && connState != ConnState.CONNECTED) {
                throw new IllegalStateException("Illegal target connection state: " + connState);
            }

            HttpRequest request = proxyTask.getRequest();
            if (request == null) {
                throw new IllegalStateException("HTTP request is null");
            }

            // Remove hop-by-hop headers
            request.removeHeaders(HTTP.CONTENT_LEN);
            request.removeHeaders(HTTP.TRANSFER_ENCODING);
            request.removeHeaders(HTTP.CONN_DIRECTIVE);
            request.removeHeaders("Keep-Alive");
            request.removeHeaders("Proxy-Authenticate");
            request.removeHeaders("Proxy-Authorization");
            request.removeHeaders("TE");
            request.removeHeaders("Trailers");
            request.removeHeaders("Upgrade");
            // Remove host header
            request.removeHeaders(HTTP.TARGET_HOST);

            HttpHost targetHost = proxyTask.getTarget();

            try {

                request.setParams(
                        new DefaultedHttpParams(request.getParams(), this.params));

                // Pre-process HTTP request
                context.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);
                context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, targetHost);

                this.httpProcessor.process(request, context);
                // and send it to the origin server
                conn.submitRequest(request);
                // Update connection state
                proxyTask.setOriginState(ConnState.REQUEST_SENT);

                System.out.println(conn + " [proxy->origin] >> " + request.getRequestLine().toString());

            } catch (IOException ex) {
                shutdownConnection(conn);
            } catch (HttpException ex) {
                shutdownConnection(conn);
            }

        }
    }

    public void outputReady(final NHttpClientConnection conn, final ContentEncoder encoder) {
        System.out.println(conn + " [proxy->origin] output ready");

        HttpContext context = conn.getContext();
        ProxyTask proxyTask = (ProxyTask) context.getAttribute(ProxyTask.ATTRIB);

        synchronized (proxyTask) {
            ConnState connState = proxyTask.getOriginState();
            if (connState != ConnState.REQUEST_SENT
                    && connState != ConnState.REQUEST_BODY_STREAM) {
                throw new IllegalStateException("Illegal target connection state: " + connState);
            }

            try {

                ByteBuffer src = proxyTask.getInBuffer();
                src.flip();
                int bytesWritten = encoder.write(src);
                System.out.println(conn + " [proxy->origin] " + bytesWritten + " bytes written");
                System.out.println(conn + " [proxy->origin] " + encoder);
                src.compact();

                if (src.position() == 0) {
                    if (proxyTask.getClientState() == ConnState.REQUEST_BODY_DONE) {
                        encoder.complete();
                    } else {
                        // Input buffer is empty. Wait until the client fills up
                        // the buffer
                        conn.suspendOutput();
                    }
                }
                // Update connection state
                if (encoder.isCompleted()) {
                    System.out.println(conn + " [proxy->origin] request body sent");
                    proxyTask.setOriginState(ConnState.REQUEST_BODY_DONE);
                } else {
                    proxyTask.setOriginState(ConnState.REQUEST_BODY_STREAM);
                    // Make sure client input is active
                    proxyTask.getClientIOControl().requestInput();
                }

            } catch (IOException ex) {
                shutdownConnection(conn);
            }
        }
    }

    public void responseReceived(final NHttpClientConnection conn) {
        System.out.println(conn + " [proxy<-origin] response received");

        HttpContext context = conn.getContext();
        ProxyTask proxyTask = (ProxyTask) context.getAttribute(ProxyTask.ATTRIB);

        synchronized (proxyTask) {
            ConnState connState = proxyTask.getOriginState();
            if (connState != ConnState.REQUEST_SENT
                    && connState != ConnState.REQUEST_BODY_DONE) {
                throw new IllegalStateException("Illegal target connection state: " + connState);
            }

            HttpResponse response = conn.getHttpResponse();
            HttpRequest request = proxyTask.getRequest();

            System.out.println(conn + " [proxy<-origin] << " + response.getStatusLine());

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < HttpStatus.SC_OK) {
                // Ignore 1xx response
                return;
            }
            try {

                // Update connection state
                proxyTask.setResponse(response);
                proxyTask.setOriginState(ConnState.RESPONSE_RECEIVED);

                if (!canResponseHaveBody(request, response)) {
                    conn.resetInput();
                    if (!this.connStrategy.keepAlive(response, context)) {
                        System.out.println(conn + " [proxy<-origin] close connection");
                        proxyTask.setOriginState(ConnState.CLOSING);
                        conn.close();
                    }
                }
                // Make sure client output is active
                proxyTask.getClientIOControl().requestOutput();

            } catch (IOException ex) {
                shutdownConnection(conn);
            }
        }

    }

    private boolean canResponseHaveBody(
            final HttpRequest request, final HttpResponse response) {

        if (request != null && "HEAD".equalsIgnoreCase(request.getRequestLine().getMethod())) {
            return false;
        }

        int status = response.getStatusLine().getStatusCode();
        return status >= HttpStatus.SC_OK
                && status != HttpStatus.SC_NO_CONTENT
                && status != HttpStatus.SC_NOT_MODIFIED
                && status != HttpStatus.SC_RESET_CONTENT;
    }

    public void inputReady(final NHttpClientConnection conn, final ContentDecoder decoder) {
        System.out.println(conn + " [proxy<-origin] input ready");

        HttpContext context = conn.getContext();
        ProxyTask proxyTask = (ProxyTask) context.getAttribute(ProxyTask.ATTRIB);

        synchronized (proxyTask) {
            ConnState connState = proxyTask.getOriginState();
            if (connState != ConnState.RESPONSE_RECEIVED
                    && connState != ConnState.RESPONSE_BODY_STREAM) {
                throw new IllegalStateException("Illegal target connection state: " + connState);
            }

            HttpResponse response = proxyTask.getResponse();
            try {

                ByteBuffer dst = proxyTask.getOutBuffer();
                int bytesRead = decoder.read(dst);
                System.out.println(conn + " [proxy<-origin] " + bytesRead + " bytes read");
                System.out.println(conn + " [proxy<-origin] " + decoder);
                if (!dst.hasRemaining()) {
                    // Output buffer is full. Suspend origin input until
                    // the client handler frees up some space in the buffer
                    conn.suspendInput();
                }
                // If there is some content in the buffer make sure client output
                // is active
                if (dst.position() > 0) {
                    proxyTask.getClientIOControl().requestOutput();
                }

                if (decoder.isCompleted()) {
                    System.out.println(conn + " [proxy<-origin] response body received");
                    proxyTask.setOriginState(ConnState.RESPONSE_BODY_DONE);

                    if (!this.connStrategy.keepAlive(response, context)) {
                        System.out.println(conn + " [proxy<-origin] close connection");
                        proxyTask.setOriginState(ConnState.CLOSING);
                        conn.close();
                    }
                } else {
                    proxyTask.setOriginState(ConnState.RESPONSE_BODY_STREAM);
                }

            } catch (IOException ex) {
                shutdownConnection(conn);
            }
        }
    }

    public void closed(final NHttpClientConnection conn) {
        System.out.println(conn + " [proxy->origin] conn closed");
        HttpContext context = conn.getContext();
        ProxyTask proxyTask = (ProxyTask) context.getAttribute(ProxyTask.ATTRIB);

        if (proxyTask != null) {
            synchronized (proxyTask) {
                proxyTask.setOriginState(ConnState.CLOSED);
            }
        }
    }

    public void exception(final NHttpClientConnection conn, final HttpException ex) {
        shutdownConnection(conn);
        System.out.println(conn + " [proxy->origin] HTTP error: " + ex.getMessage());
    }

    public void exception(final NHttpClientConnection conn, final IOException ex) {
        shutdownConnection(conn);
        System.out.println(conn + " [proxy->origin] I/O error: " + ex.getMessage());
    }

    public void timeout(final NHttpClientConnection conn) {
        System.out.println(conn + " [proxy->origin] timeout");
        closeConnection(conn);
    }

    private void shutdownConnection(final HttpConnection conn) {
        try {
            conn.shutdown();
        } catch (IOException ignore) {
        }
    }

    private void closeConnection(final HttpConnection conn) {
        try {
            conn.shutdown();
        } catch (IOException ignore) {
        }
    }

}
