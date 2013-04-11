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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPromiseAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * This is the gateway between the accepted TCP channels that are used to communicate with the client
 * ends of the http tunnel and the virtual server accepted tunnel. As a tunnel can last for longer than
 * the lifetime of the client channels that are used to service it, this layer of abstraction is
 * necessary.
 */
class ServerMessageSwitch implements ServerMessageSwitchUpstreamInterface,
        ServerMessageSwitchDownstreamInterface {

    private static final InternalLogger LOG = InternalLoggerFactory
            .getInstance(ServerMessageSwitch.class.getName());

    private final String tunnelIdPrefix;

    private final HttpTunnelAcceptedChannelFactory newChannelFactory;

    private final ConcurrentHashMap<String, TunnelInfo> tunnelsById;

    public ServerMessageSwitch(
            HttpTunnelAcceptedChannelFactory newChannelFactory) {
        this.newChannelFactory = newChannelFactory;
        tunnelIdPrefix = Long.toHexString(new Random().nextLong());
        tunnelsById = new ConcurrentHashMap<String, TunnelInfo>();
    }

    @Override
    public String createTunnel(InetSocketAddress remoteAddress) {
        String newTunnelId =
                String.format("%s_%s", tunnelIdPrefix,
                        newChannelFactory.generateTunnelId());
        TunnelInfo newTunnel = new TunnelInfo();
        newTunnel.tunnelId = newTunnelId;
        tunnelsById.put(newTunnelId, newTunnel);
        newTunnel.localChannel =
                newChannelFactory.newChannel(newTunnelId, remoteAddress);
        return newTunnelId;
    }

    @Override
    public boolean isOpenTunnel(String tunnelId) {
        TunnelInfo tunnel = tunnelsById.get(tunnelId);
        return tunnel != null;
    }

    @Override
    public void pollOutboundData(String tunnelId, Channel channel) {
        TunnelInfo tunnel = tunnelsById.get(tunnelId);
        if (tunnel == null) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Poll request for tunnel " + tunnelId +
                        " which does not exist or already closed");
            }
            respondAndClose(channel, HttpTunnelMessageUtils.createRejection(
                    null, "Unknown tunnel, possibly already closed"));
            return;
        }

        if (!tunnel.responseChannel.compareAndSet(null, channel)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Duplicate poll request detected for tunnel " +
                        tunnelId);
            }
            respondAndClose(channel, HttpTunnelMessageUtils.createRejection(
                    null, "Only one poll request at a time per tunnel allowed"));
            return;
        }

        sendQueuedData(tunnel);
    }

    private static void respondAndClose(Channel channel, HttpResponse response) {
        channel.write(response).addListener(
                ChannelFutureListener.CLOSE);
    }

    private static void sendQueuedData(TunnelInfo state) {
        Queue<QueuedResponse> queuedData = state.queuedResponses;
        Channel responseChannel = state.responseChannel.getAndSet(null);
        if (responseChannel == null) {
            // no response channel, or another thread has already used it
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("sending response for tunnel id " + state.tunnelId +
                    " to " + responseChannel.remoteAddress());
        }
        QueuedResponse messageToSend = queuedData.poll();
        if (messageToSend == null) {
            // no data to send, restore the response channel and bail out
            state.responseChannel.set(responseChannel);
            return;
        }

        HttpResponse response =
                HttpTunnelMessageUtils
                        .createRecvDataResponse(messageToSend.data);
        final ChannelPromise originalPromise = messageToSend.writePromise;
        responseChannel.write(response, originalPromise);
    }

    @Override
    public TunnelStatus routeInboundData(String tunnelId,
            ByteBuf inboundData) {
        TunnelInfo tunnel = tunnelsById.get(tunnelId);
        if (tunnel == null) {
            return TunnelStatus.CLOSED;
        }

        if (tunnel.closing.get()) {
            // client has now been notified, forget the tunnel
            tunnelsById.remove(tunnelId);
            return TunnelStatus.CLOSED;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("routing inbound data for tunnel " + tunnelId);
        }
        tunnel.localChannel.dataReceived(inboundData);
        return TunnelStatus.ALIVE;
    }

    @Override
    public void clientCloseTunnel(String tunnelId) {
        TunnelInfo tunnel = tunnelsById.get(tunnelId);
        if (tunnel == null) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("attempt made to close tunnel id " +
                        tunnelId + " which is unknown or closed");
            }

            return;
        }

        tunnel.localChannel.clientClosed();
        tunnelsById.remove(tunnelId);
    }

    @Override
    public void serverCloseTunnel(String tunnelId) {
        TunnelInfo tunnel = tunnelsById.get(tunnelId);
        if (tunnel == null) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("attempt made to close tunnel id " +
                        tunnelId + " which is unknown or closed");
            }

            return;
        }

        tunnel.closing.set(true);

        Channel responseChannel = tunnel.responseChannel.getAndSet(null);
        if (responseChannel == null) {
            // response channel is already in use, client will be notified
            // of close at next opportunity
            return;
        }

        respondAndClose(responseChannel,
                HttpTunnelMessageUtils.createTunnelCloseResponse());
        // client has been notified, forget the tunnel
        tunnelsById.remove(tunnelId);
    }

    @Override
    public void routeOutboundData(String tunnelId, ByteBuf data,
            ChannelPromise writePromise) {
        TunnelInfo tunnel = tunnelsById.get(tunnelId);
        if (tunnel == null) {
            // tunnel is closed
            if (LOG.isWarnEnabled()) {
                LOG.warn("attempt made to send data out on tunnel id " +
                        tunnelId + " which is unknown or closed");
            }
            return;
        }

        ChannelPromiseAggregator aggregator =
                new ChannelPromiseAggregator(writePromise);
        List<ByteBuf> fragments =
                WriteSplitter.split(data, HttpTunnelMessageUtils.MAX_BODY_SIZE);

        if (LOG.isDebugEnabled()) {
            LOG.debug("routing outbound data for tunnel " + tunnelId);
        }
        for (ByteBuf fragment: fragments) {
            ChannelPromise fragmentedPromise = writePromise.channel().newPromise();
            aggregator.add(fragmentedPromise);
            tunnel.queuedResponses.offer(new QueuedResponse(fragment,
                    fragmentedPromise));
        }

        sendQueuedData(tunnel);
    }

    private static final class TunnelInfo {
        TunnelInfo() {
        }

        public String tunnelId;

        public HttpTunnelAcceptedChannelReceiver localChannel;

        public final AtomicReference<Channel> responseChannel =
                new AtomicReference<Channel>(null);

        public final Queue<QueuedResponse> queuedResponses =
                new ConcurrentLinkedQueue<QueuedResponse>();

        public final AtomicBoolean closing = new AtomicBoolean(false);
    }

    private static final class QueuedResponse {
        final ByteBuf data;

        final ChannelPromise writePromise;

        QueuedResponse(ByteBuf data, ChannelPromise writePromise) {
            this.data = data;
            this.writePromise = writePromise;
        }
    }
}
