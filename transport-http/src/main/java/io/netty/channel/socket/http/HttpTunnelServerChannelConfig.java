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


import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.ServerSocketChannelConfig;

import java.util.Map;

import static io.netty.channel.ChannelOption.SO_BACKLOG;
import static io.netty.channel.ChannelOption.SO_RCVBUF;
import static io.netty.channel.ChannelOption.SO_REUSEADDR;

/**
 */
public final class HttpTunnelServerChannelConfig extends DefaultChannelConfig implements ServerSocketChannelConfig {

    private volatile ChannelInitializer<? extends Channel> channelInitializer;

    private final ServerSocketChannel realChannel;

    private TunnelIdGenerator tunnelIdGenerator =
            new DefaultTunnelIdGenerator();

    public HttpTunnelServerChannelConfig(ServerSocketChannel realChannel) {
        super(realChannel);
        this.realChannel = realChannel;
    }

    private ServerSocketChannelConfig getWrappedConfig() {
        return realChannel.config();
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(super.getOptions(), HttpTunnelChannelOption.TUNNEL_ID_GENERATOR,
                HttpTunnelChannelOption.CHANNEL_INITIALIZER, SO_RCVBUF, SO_REUSEADDR, SO_BACKLOG);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == HttpTunnelChannelOption.TUNNEL_ID_GENERATOR) {
            return (T) getTunnelIdGenerator();
        }
        if (option == HttpTunnelChannelOption.CHANNEL_INITIALIZER) {
            return (T) getChannelInitializer();
        }
        if (option == SO_RCVBUF) {
            return (T) Integer.valueOf(getReceiveBufferSize());
        }
        if (option == SO_REUSEADDR) {
            return (T) Boolean.valueOf(isReuseAddress());
        }
        if (option == SO_BACKLOG) {
            return (T) Integer.valueOf(getBacklog());
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == HttpTunnelChannelOption.TUNNEL_ID_GENERATOR) {
            setTunnelIdGenerator((TunnelIdGenerator) value);
        } else if (option == HttpTunnelChannelOption.CHANNEL_INITIALIZER) {
            setChannelInitializer((ChannelInitializer<? extends Channel>) value);
        } else if (option == SO_RCVBUF) {
            setReceiveBufferSize((Integer) value);
        } else if (option == SO_REUSEADDR) {
            setReuseAddress((Boolean) value);
        } else if (option == SO_BACKLOG) {
            setBacklog((Integer) value);
        } else {
            return super.setOption(option, value);
        }

        return true;
    }

    @Override
    public int getBacklog() {
        return getWrappedConfig().getBacklog();
    }

    @Override
    public int getReceiveBufferSize() {
        return getWrappedConfig().getReceiveBufferSize();
    }

    @Override
    public boolean isReuseAddress() {
        return getWrappedConfig().isReuseAddress();
    }

    @Override
    public HttpTunnelServerChannelConfig setBacklog(int backlog) {
        getWrappedConfig().setBacklog(backlog);
        return this;
    }

    @Override
    public HttpTunnelServerChannelConfig setPerformancePreferences(int connectionTime, int latency,
            int bandwidth) {
        getWrappedConfig().setPerformancePreferences(connectionTime, latency,
                bandwidth);
        return this;
    }

    @Override
    public HttpTunnelServerChannelConfig setReceiveBufferSize(int receiveBufferSize) {
        getWrappedConfig().setReceiveBufferSize(receiveBufferSize);
        return this;
    }

    @Override
    public HttpTunnelServerChannelConfig setReuseAddress(boolean reuseAddress) {
        getWrappedConfig().setReuseAddress(reuseAddress);
        return this;
    }

    @Override
    public int getConnectTimeoutMillis() {
        return getWrappedConfig().getConnectTimeoutMillis();
    }

    @Override
    public HttpTunnelServerChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        getWrappedConfig().setConnectTimeoutMillis(connectTimeoutMillis);
        return this;
    }

    public void setChannelInitializer(ChannelInitializer<? extends Channel> channelInitializer) {
        this.channelInitializer = channelInitializer;
    }

    public ChannelInitializer<? extends Channel> getChannelInitializer() {
        return channelInitializer;
    }

    public void setTunnelIdGenerator(TunnelIdGenerator tunnelIdGenerator) {
        this.tunnelIdGenerator = tunnelIdGenerator;
    }

    public TunnelIdGenerator getTunnelIdGenerator() {
        return tunnelIdGenerator;
    }

    @Override
    public int getWriteSpinCount() {
        return getWrappedConfig().getWriteSpinCount();
    }

    @Override
    public HttpTunnelServerChannelConfig setWriteSpinCount(int writeSpinCount) {
        getWrappedConfig().setWriteSpinCount(writeSpinCount);
        return this;
    }

    @Override
    public ByteBufAllocator getAllocator() {
        return getWrappedConfig().getAllocator();
    }

    @Override
    public HttpTunnelServerChannelConfig setAllocator(ByteBufAllocator allocator) {
        getWrappedConfig().setAllocator(allocator);
        return this;
    }

    @Override
    public boolean isAutoRead() {
        return getWrappedConfig().isAutoRead();
    }

    @Override
    public HttpTunnelServerChannelConfig setAutoRead(boolean autoRead) {
        getWrappedConfig().setAutoRead(autoRead);
        return this;
    }

    @Override
    public ChannelHandlerByteBufType getDefaultHandlerByteBufType() {
        return getWrappedConfig().getDefaultHandlerByteBufType();
    }

    @Override
    public HttpTunnelServerChannelConfig setDefaultHandlerByteBufType(ChannelHandlerByteBufType handlerByteBufType) {
        getWrappedConfig().setDefaultHandlerByteBufType(handlerByteBufType);
        return this;
    }
}
