/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Pipeline component which controls the client poll loop to the server.
 */
class HttpTunnelClientPollHandler extends ChannelInboundMessageHandlerAdapter<FullHttpResponse> {

    public static final String NAME = "server2client";

    private static final InternalLogger LOG = InternalLoggerFactory
            .getInstance(HttpTunnelClientPollHandler.class);

    private String tunnelId;

    private final HttpTunnelClientWorkerOwner tunnelChannel;

    private long pollTime;

    public HttpTunnelClientPollHandler(HttpTunnelClientWorkerOwner tunnelChannel) {
        this.tunnelChannel = tunnelChannel;
    }

    public void setTunnelId(String tunnelId) {
        this.tunnelId = tunnelId;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx)
            throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Poll channel for tunnel " + tunnelId + " established");
        }
        tunnelChannel.fullyEstablished();
        sendPoll(ctx);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, FullHttpResponse response)
            throws Exception {
        if (HttpTunnelMessageUtils.isOKResponse(response)) {
            long rtTime = System.nanoTime() - pollTime;
            if (LOG.isDebugEnabled()) {
                LOG.debug("OK response received for poll on tunnel " +
                        tunnelId + " after " + rtTime + " ns");
            }
            tunnelChannel.onMessageReceived(response.data());
            sendPoll(ctx);
        } else {
            if (LOG.isWarnEnabled()) {
                LOG.warn("non-OK response received for poll on tunnel " +
                        tunnelId);
            }
        }
    }

    private void sendPoll(ChannelHandlerContext ctx) {
        pollTime = System.nanoTime();
        if (LOG.isDebugEnabled()) {
            LOG.debug("sending poll request for tunnel " + tunnelId);
        }
        FullHttpRequest request =
                HttpTunnelMessageUtils.createReceiveDataRequest(
                        tunnelChannel.getServerHostName(), tunnelId);
        ctx.write(request);
    }
}
