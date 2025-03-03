/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.handler.sockjs.impl;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
class RawWebSocketTransport {

  private static class RawWSSockJSSocket extends SockJSSocketBase {

    final ServerWebSocket ws;
    MultiMap headers;
    boolean closed;

    RawWSSockJSSocket(Vertx vertx, RoutingContext rc, SockJSHandlerOptions options, ServerWebSocket ws) {
      super(vertx, rc, options);
      this.ws = ws;
      ws.closeHandler(v -> {
        // Make sure the writeHandler gets unregistered
        synchronized (RawWSSockJSSocket.this) {
          closed = true;
        }
        RawWSSockJSSocket.super.close();
      });
    }

    public SockJSSocket handler(Handler<Buffer> handler) {
      ws.binaryMessageHandler(handler);
      ws.textMessageHandler(textMessage -> handler.handle(Buffer.buffer(textMessage)));
      return this;
    }

    public SockJSSocket pause() {
      ws.pause();
      return this;
    }

    public SockJSSocket resume() {
      ws.resume();
      return this;
    }

    @Override
    public SockJSSocket fetch(long amount) {
      ws.fetch(amount);
      return this;
    }

    private synchronized boolean canWrite(Handler<AsyncResult<Void>> handler) {
      if (closed) {
        if (handler != null) {
          vertx.runOnContext(v -> handler.handle(Future.failedFuture(ConnectionBase.CLOSED_EXCEPTION)));
        }
        return false;
      }
      return true;
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
      if (canWrite(handler)) {
        ws.writeBinaryMessage(data, handler);
      }
    }

    @Override
    public void write(String data, Handler<AsyncResult<Void>> handler) {
      if (canWrite(handler)) {
        ws.writeTextMessage(data, handler);
      }
    }

    public SockJSSocket setWriteQueueMaxSize(int maxQueueSize) {
      ws.setWriteQueueMaxSize(maxQueueSize);
      return this;
    }

    public boolean writeQueueFull() {
      return ws.writeQueueFull();
    }

    public SockJSSocket drainHandler(Handler<Void> handler) {
      ws.drainHandler(handler);
      return this;
    }

    public SockJSSocket exceptionHandler(Handler<Throwable> handler) {
      ws.exceptionHandler(handler);
      return this;
    }

    public SockJSSocket endHandler(Handler<Void> endHandler) {
      ws.endHandler(endHandler);
      return this;
    }

    @Override
    public SockJSSocket closeHandler(Handler<Void> closeHandler) {
      ws.closeHandler(closeHandler);
      return this;
    }

    public void close() {
      synchronized (this) {
        if (closed) {
          return;
        }
        closed = true;
      }
      super.close();
      ws.close();
    }

    public void closeAfterSessionExpired() {
      this.close((short) 1001, "Session expired");
    }

    public void close(int statusCode, String reason) {
      synchronized (this) {
        if (closed) {
          return;
        }
        closed = true;
      }
      super.close();
      ws.close((short) statusCode, reason);
    }

    @Override
    public SocketAddress remoteAddress() {
      return ws.remoteAddress();
    }

    @Override
    public SocketAddress localAddress() {
      return ws.localAddress();
    }

    @Override
    public MultiMap headers() {
      if (headers == null) {
        headers = BaseTransport.removeCookieHeaders(ws.headers());
      }
      return headers;
    }

    @Override
    public String uri() {
      return ws.uri();
    }
  }

  RawWebSocketTransport(Vertx vertx, Router router, SockJSHandlerOptions options, Handler<SockJSSocket> sockHandler) {

    String wsRE = "/websocket";

    router.get(wsRE).handler(rc -> {
      // we're about to upgrade the connection, which means an asynchronous
      // operation. We have to pause the request otherwise we will loose the
      // body of the request once the upgrade completes
      final boolean parseEnded = rc.request().isEnded();
      if (!parseEnded) {
        rc.request().pause();
      }
      // upgrade
      rc.request().toWebSocket(toWebSocket -> {
        if (toWebSocket.succeeded()) {
          // resume the parsing
          if (!parseEnded) {
            rc.request().resume();
          }
          // handle the sockjs session as usual
          SockJSSocket sock = new RawWSSockJSSocket(vertx, rc, options, toWebSocket.result());
          sockHandler.handle(sock);
        } else {
          // the upgrade failed
          rc.fail(toWebSocket.cause());
        }
      });
    });

    router.get(wsRE).handler(rc -> rc.response().setStatusCode(400).end("Can \"Upgrade\" only to \"WebSocket\"."));

    router.get(wsRE).handler(rc -> rc.response().putHeader(HttpHeaders.ALLOW, "GET").setStatusCode(405).end());
  }

}
