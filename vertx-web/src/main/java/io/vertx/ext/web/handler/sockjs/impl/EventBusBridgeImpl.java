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
import io.vertx.core.eventbus.*;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.sockjs.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.vertx.core.buffer.Buffer.buffer;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class EventBusBridgeImpl implements Handler<SockJSSocket> {

  private static final Logger LOG = LoggerFactory.getLogger(EventBusBridgeImpl.class);

  private final Map<SockJSSocket, SockInfo> sockInfos = new HashMap<>();
  private final List<PermittedOptions> inboundPermitted;
  private final List<PermittedOptions> outboundPermitted;
  private final int maxAddressLength;
  private final int maxHandlersPerSocket;
  private final long pingTimeout;
  private final long replyTimeout;
  private final Vertx vertx;
  private final EventBus eb;
  private final Map<String, Message<?>> messagesAwaitingReply = new HashMap<>();
  private final Map<String, Pattern> compiledREs = new HashMap<>();
  private final Handler<BridgeEvent> bridgeEventHandler;
  private final AuthorizationProvider authzProvider;

  public EventBusBridgeImpl(Vertx vertx, AuthorizationProvider authzProvider, SockJSBridgeOptions options, Handler<BridgeEvent> bridgeEventHandler) {
    this.vertx = vertx;
    this.eb = vertx.eventBus();
    this.authzProvider = authzProvider;
    this.inboundPermitted = options.getInboundPermitteds() == null ? new ArrayList<>() : options.getInboundPermitteds();
    this.outboundPermitted = options.getOutboundPermitteds() == null ? new ArrayList<>() : options.getOutboundPermitteds();
    this.maxAddressLength = options.getMaxAddressLength();
    this.maxHandlersPerSocket = options.getMaxHandlersPerSocket();
    this.pingTimeout = options.getPingTimeout();
    this.replyTimeout = options.getReplyTimeout();
    this.bridgeEventHandler = bridgeEventHandler;
  }

  private void handleSocketData(SockJSSocket sock, Buffer data, Map<String, MessageConsumer<?>> registrations) {
    JsonObject msg;

    try {
      msg = new JsonObject(data.toString());
    } catch (DecodeException e) {
      replyError(sock, "invalid_json");
      return;
    }

    String type = msg.getString("type");
    if (type == null) {
      replyError(sock, "missing_type");
      return;
    }

    if (type.equals("ping")) {
      internalHandlePing(sock);
    } else {
      String address = msg.getString("address");
      if (address == null) {
        replyError(sock, "missing_address");
        return;
      }
      switch (type) {
        case "send":
          internalHandleSendOrPub(sock, true, msg);
          break;
        case "publish":
          internalHandleSendOrPub(sock, false, msg);
          break;
        case "register":
          internalHandleRegister(sock, msg, registrations);
          break;
        case "unregister":
          internalHandleUnregister(sock, msg, registrations);
          break;
        default:
          LOG.error("Invalid type in incoming message: " + type);
          replyError(sock, "invalid_type");
      }
    }

  }

  private void checkCallHook(Supplier<BridgeEventImpl> eventSupplier) {
    checkCallHook(eventSupplier, null, null);
  }

  private void checkCallHook(Supplier<BridgeEventImpl> eventSupplier, Runnable okAction, Runnable rejectAction) {
    if (bridgeEventHandler == null) {
      if (okAction != null) {
        okAction.run();
      }
    } else {
      final BridgeEventImpl event = eventSupplier.get();
      final boolean before = sockInfos.containsKey(event.socket());
      bridgeEventHandler.handle(event);
      event.future()
        .onFailure(err -> LOG.error("Failure in bridge event handler", err))
        .onSuccess(ok -> {
          if (ok) {
            final boolean after = sockInfos.containsKey(event.socket());
            if (before != after) {
              // even though the event check is valid, the socket info isn't valid anymore
              if (rejectAction != null) {
                rejectAction.run();
              } else {
                LOG.debug("SockJSSocket state change prevented send or pub");
              }
            } else {
              if (okAction != null) {
                okAction.run();
              }
            }
          } else {
            if (rejectAction != null) {
              rejectAction.run();
            } else {
              LOG.debug("Bridge handler prevented send or pub");
            }
          }
        });
    }
  }

  private void internalHandleSendOrPub(SockJSSocket sock, boolean send, JsonObject msg) {
    checkCallHook(() -> new BridgeEventImpl(send ? BridgeEventType.SEND : BridgeEventType.PUBLISH, msg, sock),
      () -> {
        String address = msg.getString("address");
        if (address == null) {
          replyError(sock, "missing_address");
          return;
        }
        doSendOrPub(send, sock, address, msg);
      }, () -> replyError(sock, "rejected"));
  }

  private boolean checkMaxHandlers(SockJSSocket sock, SockInfo info) {
    if (info.handlerCount < maxHandlersPerSocket) {
      return true;
    } else {
      LOG.warn("Refusing to register as max_handlers_per_socket reached already");
      replyError(sock, "max_handlers_reached");
      return false;
    }
  }

  private void internalHandleRegister(SockJSSocket sock, JsonObject rawMsg, Map<String, MessageConsumer<?>> registrations) {
    final SockInfo info = sockInfos.get(sock);
    if (!checkMaxHandlers(sock, info)) {
      return;
    }
    checkCallHook(() -> new BridgeEventImpl(BridgeEventType.REGISTER, rawMsg, sock),
      () -> {
        final boolean debug = LOG.isDebugEnabled();
        final String address = rawMsg.getString("address");
        if (address == null) {
          replyError(sock, "missing_address");
          return;
        } else if (address.length() > maxAddressLength) {
          LOG.warn("Refusing to register as address length > max_address_length");
          replyError(sock, "max_address_length_reached");
          return;
        }
        Match match = checkMatches(false, address, null);
        if (match.doesMatch) {
          Handler<Message<Object>> handler = msg -> {
            Match curMatch = checkMatches(false, address, msg.body());
            if (curMatch.doesMatch) {
              if (curMatch.requiredAuthority != null) {
                authorise(curMatch, sock.webUser(), res -> {
                  if (res.succeeded()) {
                    if (res.result()) {
                      checkAddAccceptedReplyAddress(msg);
                      deliverMessage(sock, address, msg);
                    } else {
                      if (debug) {
                        LOG.debug("Outbound message for address " + address + " rejected because auth is required and socket is not authed");
                      }
                    }
                  } else {
                    LOG.error(res.cause());
                  }
                });

              } else {
                checkAddAccceptedReplyAddress(msg);
                deliverMessage(sock, address, msg);
              }
            } else {
              // outbound match failed
              if (debug) {
                LOG.debug("Outbound message for address " + address + " rejected because there is no inbound match");
              }
            }
          };
          MessageConsumer<?> reg = eb.consumer(address).handler(handler);
          registrations.put(address, reg);
          info.handlerCount++;
          // Notify registration completed
          checkCallHook(() -> new BridgeEventImpl(BridgeEventType.REGISTERED, rawMsg, sock));
        } else {
          // inbound match failed
          if (debug) {
            LOG.debug("Cannot register handler for address " + address + " because there is no inbound match");
          }
          replyError(sock, "access_denied");
        }
      }, () -> replyError(sock, "rejected"));
  }

  private void internalHandleUnregister(SockJSSocket sock, JsonObject rawMsg, Map<String, MessageConsumer<?>> registrations) {
    checkCallHook(() -> new BridgeEventImpl(BridgeEventType.UNREGISTER, rawMsg, sock),
      () -> {
        String address = rawMsg.getString("address");
        if (address == null) {
          replyError(sock, "missing_address");
          return;
        }
        Match match = checkMatches(false, address, null);
        if (match.doesMatch) {
          MessageConsumer<?> reg = registrations.remove(address);
          if (reg != null) {
            reg.unregister();
            SockInfo info = sockInfos.get(sock);
            info.handlerCount--;
          }
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Cannot unregister handler for address " + address + " because there is no inbound match");
          }
          replyError(sock, "access_denied");
        }
      }, () -> replyError(sock, "rejected"));
  }

  private void internalHandlePing(final SockJSSocket sock) {
    Session webSession = sock.webSession();
    if (webSession != null) {
      webSession.setAccessed();
    }
    SockInfo info = sockInfos.get(sock);
    if (info != null) {
      info.pingInfo.lastPing = System.currentTimeMillis();
      // Trigger an event to allow custom behavior after updating lastPing
      checkCallHook(() -> new BridgeEventImpl(BridgeEventType.SOCKET_PING, null, sock));
    }
  }

  public void handle(final SockJSSocket sock) {
    checkCallHook(() -> new BridgeEventImpl(BridgeEventType.SOCKET_CREATED, null, sock),
      () -> {
        Map<String, MessageConsumer<?>> registrations = new HashMap<>();

        sock
          .handler(data -> handleSocketData(sock, data, registrations))
          .exceptionHandler(err -> handleSocketException(sock, err, registrations))
          .closeHandler(v -> handleSocketClosed(sock, registrations));

        // Start a checker to check for pings
        PingInfo pingInfo = new PingInfo();
        pingInfo.timerID = vertx.setPeriodic(pingTimeout, id -> {
          if (System.currentTimeMillis() - pingInfo.lastPing >= pingTimeout) {
            // Trigger an event to allow custom behavior before disconnecting client.
            checkCallHook(() -> new BridgeEventImpl(BridgeEventType.SOCKET_IDLE, null, sock),
              // We didn't receive a ping in time so close the socket
              ((SockJSSocketBase) sock)::closeAfterSessionExpired,
              () -> replyError(sock, "rejected"));
          }
        });
        SockInfo sockInfo = new SockInfo();
        sockInfo.pingInfo = pingInfo;
        sockInfos.put(sock, sockInfo);
      }, sock::close);
  }

  private void handleSocketClosed(SockJSSocket sock, Map<String, MessageConsumer<?>> registrations) {
    clearSocketState(sock, registrations);
    checkCallHook(() -> new BridgeEventImpl(BridgeEventType.SOCKET_CLOSED, null, sock));
  }

  private void handleSocketException(SockJSSocket sock, Throwable err, Map<String, MessageConsumer<?>> registrations) {
    LOG.error("SockJSSocket exception", err);
    clearSocketState(sock, registrations);
    final JsonObject msg = new JsonObject().put("type", "err").put("failureType", "socketException");
    if (err != null) {
      msg.put("message", err.getMessage());
    }
     checkCallHook(() -> new BridgeEventImpl(BridgeEventType.SOCKET_ERROR, msg, sock));
  }

  private void clearSocketState(SockJSSocket sock, Map<String, MessageConsumer<?>> registrations) {
    // On close or exception unregister any handlers that haven't been unregistered
    registrations.forEach((key, value) -> {
      value.unregister();
      checkCallHook(() ->
        new BridgeEventImpl(
          BridgeEventType.UNREGISTER,
          new JsonObject().put("type", "unregister").put("address", value.address()),
          sock));
    });
    // ensure that no timers remain active
    SockInfo info = sockInfos.remove(sock);
    if (info != null) {
      PingInfo pingInfo = info.pingInfo;
      if (pingInfo != null) {
        vertx.cancelTimer(pingInfo.timerID);
      }
    }
  }

  private void checkAddAccceptedReplyAddress(Message<?> message) {
    String replyAddress = message.replyAddress();
    if (replyAddress != null) {
      // This message has a reply address
      // When the reply comes through we want to accept it irrespective of its address
      // Since all replies are implicitly accepted if the original message was accepted
      // So we cache the reply address, so we can check against it
      // We also need to cache the message so we can actually call reply() on it - we need the actual message
      // as the original sender could be on a different node so we need the replyDest (serverID) too otherwise
      // the message won't be routed to the node.
      messagesAwaitingReply.put(replyAddress, message);
      // And we remove after timeout in case the reply never comes
      vertx.setTimer(replyTimeout, tid -> messagesAwaitingReply.remove(replyAddress));
    }
  }

  private void deliverMessage(SockJSSocket sock, String address, Message<?> message) {
    JsonObject envelope = new JsonObject().put("type", "rec").put("address", address).put("body", message.body());
    if (message.replyAddress() != null) {
      envelope.put("replyAddress", message.replyAddress());
    }
    if (message.headers() != null && !message.headers().isEmpty()) {
      JsonObject headersCopy = new JsonObject();
      for (String name : message.headers().names()) {
        List<String> values = message.headers().getAll(name);
        if (values.size() == 1) {
          headersCopy.put(name, values.get(0));
        } else {
          headersCopy.put(name, values);
        }
      }
      envelope.put("headers", headersCopy);
    }
    checkCallHook(() -> new BridgeEventImpl(BridgeEventType.RECEIVE, envelope, sock),
      () -> sock.write(buffer(envelope.encode())),
      () -> LOG.debug("outbound message rejected by bridge event handler"));
  }

  private void doSendOrPub(boolean send, SockJSSocket sock, String address,
                           JsonObject message) {
    Object body = message.getValue("body");
    JsonObject headers = message.getJsonObject("headers");
    String replyAddress = message.getString("replyAddress");
    // Sanity check reply address is not too big, to avoid DoS
    if (replyAddress != null && replyAddress.length() > 36) {
      // vertx-eventbus.js ids are always 36 chars
      LOG.error("Will not send message, reply address is > 36 chars");
      replyError(sock, "invalid_reply_address");
      return;
    }
    final boolean debug = LOG.isDebugEnabled();
    if (debug) {
      LOG.debug("Received msg from client in bridge. address:" + address + " message:" + body);
    }
    final Message<?> awaitingReply = messagesAwaitingReply.remove(address);
    Match curMatch;
    if (awaitingReply != null) {
      curMatch = new Match(true);
    } else {
      curMatch = checkMatches(true, address, body);
    }
    if (curMatch.doesMatch) {
      if (curMatch.requiredAuthority != null) {
        User webUser = sock.webUser();
        if (webUser != null) {
          authorise(curMatch, webUser, res -> {
            if (res.succeeded()) {
              if (res.result()) {
                checkAndSend(send, address, body, headers, sock, replyAddress, awaitingReply);
              } else {
                replyError(sock, "access_denied");
                if (debug) {
                  LOG.debug("Inbound message for address " + address + " rejected because is not authorised");
                }
              }
            } else {
              replyError(sock, "auth_error");
              LOG.error("Error in performing authorization", res.cause());
            }
          });
        } else {
          // no web session
          replyError(sock, "not_logged_in");
          if (debug) {
            LOG.debug("Inbound message for address " + address +
              " rejected because it requires auth and user is not authenticated");
          }
        }
      } else {
        checkAndSend(send, address, body, headers, sock, replyAddress, awaitingReply);
      }
    } else {
      // inbound match failed
      replyError(sock, "access_denied");
      if (debug) {
        LOG.debug("Inbound message for address " + address + " rejected because there is no match");
      }
    }
  }

  private void checkAndSend(boolean send, String address, Object body,
                            JsonObject headers,
                            SockJSSocket sock,
                            String replyAddress,
                            Message<?> awaitingReply) {
    SockInfo info = sockInfos.get(sock);
    if (replyAddress != null && !checkMaxHandlers(sock, info)) {
      return;
    }
    Handler<AsyncResult<Message<Object>>> replyHandler;
    if (replyAddress != null) {
      replyHandler = result -> {
        if (result.succeeded()) {
          Message<?> message = result.result();
          // Note we don't check outbound matches for replies
          // Replies are always let through if the original message
          // was approved

          // Now - the reply message might itself be waiting for a reply - which would be inbound -so we need
          // to add the message to the messages awaiting reply so it can be let through
          checkAddAccceptedReplyAddress(message);
          deliverMessage(sock, replyAddress, message);
        } else {
          ReplyException cause = (ReplyException) result.cause();
          JsonObject envelope =
            new JsonObject()
              .put("type", "err")
              .put("address", replyAddress)
              .put("failureCode", cause.failureCode())
              .put("failureType", cause.failureType().name())
              .put("message", cause.getMessage());
          sock.write(buffer(envelope.encode()));
        }
        info.handlerCount--;
      };
    } else {
      replyHandler = null;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Forwarding message to address " + address + " on event bus");
    }
    MultiMap mHeaders;
    if (headers != null) {
      mHeaders = HttpHeaders.headers();
      headers.forEach(entry -> mHeaders.add(entry.getKey(), entry.getValue().toString()));
    } else {
      mHeaders = null;
    }
    if (send) {
      if (awaitingReply != null) {
        if (replyAddress != null) {
          awaitingReply.replyAndRequest(body, new DeliveryOptions().setSendTimeout(replyTimeout).setHeaders(mHeaders), replyHandler);
        } else {
          awaitingReply.reply(body, new DeliveryOptions().setSendTimeout(replyTimeout).setHeaders(mHeaders));
        }
      } else {
        if (replyAddress != null) {
          eb.request(address, body, new DeliveryOptions().setSendTimeout(replyTimeout).setHeaders(mHeaders), replyHandler);
        } else {
          eb.send(address, body, new DeliveryOptions().setSendTimeout(replyTimeout).setHeaders(mHeaders));
        }
      }
      if (replyAddress != null) {
        info.handlerCount++;
      }
    } else {
      eb.publish(address, body, new DeliveryOptions().setHeaders(mHeaders));
    }
  }

  private void authorise(Match curMatch, User webUser, Handler<AsyncResult<Boolean>> handler) {
    // step 1: match against the raw user, if a AuthZ handler is in the path it could have already
    //         loaded the authorizations
    if (curMatch.requiredAuthority.match(webUser)) {
      handler.handle(Future.succeededFuture(true));
      return;
    }

    if (authzProvider == null) {
      // can't load, there's no provider
      handler.handle(Future.succeededFuture(false));
      return;
    }
    // step 2: load authorizations
    authzProvider.getAuthorizations(webUser, res -> {
      if (res.succeeded()) {
        if (curMatch.requiredAuthority.match(webUser)) {
          handler.handle(Future.succeededFuture(true));
        } else {
          handler.handle(Future.succeededFuture(false));
        }
      } else {
        handler.handle(Future.failedFuture(res.cause()));
      }
    });
  }

  /*
  Empty inboundPermitted means reject everything - this is the default.
  If at least one match is supplied and all the fields of any match match then the message inboundPermitted,
  this means that specifying one match with a JSON empty object means everything is accepted
   */
  private Match checkMatches(boolean inbound, String address, Object body) {

    List<PermittedOptions> matches = inbound ? inboundPermitted : outboundPermitted;

    for (PermittedOptions matchHolder : matches) {
      String matchAddress = matchHolder.getAddress();
      String matchRegex;
      if (matchAddress == null) {
        matchRegex = matchHolder.getAddressRegex();
      } else {
        matchRegex = null;
      }

      boolean addressOK;
      if (matchAddress == null) {
        addressOK = matchRegex == null || regexMatches(matchRegex, address);
      } else {
        addressOK = matchAddress.equals(address);
      }

      if (addressOK) {
        boolean matched = structureMatches(matchHolder.getMatch(), body);
        if (matched) {
          String requiredAuthority = matchHolder.getRequiredAuthority();
          return new Match(true, requiredAuthority);
        }
      }
    }
    return new Match(false);
  }

  private boolean regexMatches(String matchRegex, String address) {
    Pattern pattern = compiledREs.computeIfAbsent(matchRegex, Pattern::compile);
    Matcher m = pattern.matcher(address);
    return m.matches();
  }

  private static void replyError(SockJSSocket sock, String err) {
    JsonObject envelope = new JsonObject().put("type", "err").put("body", err);
    sock.write(buffer(envelope.encode()));
  }

  private static boolean structureMatches(JsonObject match, Object bodyObject) {
    if (match == null || bodyObject == null) return true;

    // Can send message other than JSON too - in which case we can't do deep matching on structure of message
    if (bodyObject instanceof JsonObject) {
      JsonObject body = (JsonObject) bodyObject;
      for (String fieldName : match.fieldNames()) {
        Object mv = match.getValue(fieldName);
        Object bv = body.getValue(fieldName);
        // Support deep matching
        if (mv instanceof JsonObject) {
          if (!structureMatches((JsonObject) mv, bv)) {
            return false;
          }
        } else if (!match.getValue(fieldName).equals(body.getValue(fieldName))) {
          return false;
        }
      }
      return true;
    }

    return false;
  }

  private static class Match {
    public final boolean doesMatch;
    public final Authorization requiredAuthority;

    Match(boolean doesMatch, String requiredAuthority) {
      this.doesMatch = doesMatch;
      this.requiredAuthority = requiredAuthority == null ? null : PermissionBasedAuthorization.create(requiredAuthority);
    }

    Match(boolean doesMatch) {
      this.doesMatch = doesMatch;
      this.requiredAuthority = null;
    }

  }

  private static final class PingInfo {
    long lastPing;
    long timerID;
  }

  private static final class SockInfo {
    int handlerCount;
    PingInfo pingInfo;
  }


}
