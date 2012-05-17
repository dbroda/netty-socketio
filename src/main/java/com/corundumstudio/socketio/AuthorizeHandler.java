/**
 * Copyright 2012 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio;

import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.messages.AuthorizeMessage;
import com.corundumstudio.socketio.parser.Packet;
import com.corundumstudio.socketio.parser.PacketType;

@Sharable
public class AuthorizeHandler extends SimpleChannelUpstreamHandler implements Disconnectable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final CancelableScheduler<UUID> disconnectScheduler;
    private final Set<UUID> authorizedSessionIds =
    							Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    private final String connectPath;

    private final Configuration configuration;
    private final SocketIOListener socketIOListener;

    public AuthorizeHandler(String connectPath, SocketIOListener socketIOListener, CancelableScheduler<UUID> scheduler, Configuration configuration) {
        super();
        this.connectPath = connectPath;
        this.socketIOListener = socketIOListener;
        this.configuration = configuration;
        this.disconnectScheduler = scheduler;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            Channel channel = ctx.getChannel();
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.getUri());
            if (!configuration.isAllowCustomRequests()
            		&& !queryDecoder.getPath().startsWith(connectPath)) {
                HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
                ChannelFuture f = channel.write(res);
                f.addListener(ChannelFutureListener.CLOSE);
            	return;
            }
            if (HttpMethod.GET.equals(req.getMethod()) && queryDecoder.getPath().equals(connectPath)) {
                authorize(channel, req, queryDecoder.getParameters());
                return;
            }
        }
        ctx.sendUpstream(e);
    }

    private void authorize(Channel channel, HttpRequest req, Map<String, List<String>> params)
            throws IOException {
        final UUID sessionId = UUID.randomUUID();
        authorizedSessionIds.add(sessionId);

        scheduleDisconnect(channel, sessionId);

        String transports = "xhr-polling,websocket";
        //String transports = "websocket";
        String heartbeatTimeoutVal = String.valueOf(configuration.getHeartbeatTimeout());
        if (configuration.getHeartbeatTimeout() == 0) {
            heartbeatTimeoutVal = "";
        }

        String msg = sessionId + ":" + heartbeatTimeoutVal + ":" + configuration.getCloseTimeout() + ":" + transports;

        List<String> jsonpParams = params.get("jsonp");
        String jsonpParam = null;
        if (jsonpParams != null) {
            jsonpParam = jsonpParams.get(0);
        }
        String origin = req.getHeader(HttpHeaders.Names.ORIGIN);
        channel.write(new AuthorizeMessage(msg, jsonpParam, origin, sessionId));
        log.debug("New sessionId: {} authorized", sessionId);
    }

	private void scheduleDisconnect(Channel channel, final UUID sessionId) {
		ChannelFuture future = channel.getCloseFuture();
        future.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				disconnectScheduler.schedule(sessionId, new Runnable() {
					@Override
					public void run() {
						authorizedSessionIds.remove(sessionId);
						log.debug("Authorized sessionId: {} removed due to connection timeout", sessionId);
					}
				}, configuration.getCloseTimeout(), TimeUnit.SECONDS);
			}
		});
	}

    public boolean isSessionAuthorized(UUID sessionId) {
        return authorizedSessionIds.contains(sessionId);
    }

    public void connect(SocketIOClient client) {
    	disconnectScheduler.cancel(client.getSessionId());
        client.send(new Packet(PacketType.CONNECT));
        socketIOListener.onConnect(client);
    }

    @Override
    public void onDisconnect(SocketIOClient client) {
    	authorizedSessionIds.remove(client.getSessionId());
    }

}
