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

import java.net.SocketAddress;
import java.util.Map;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.SocketChannelConfig;


/**
 * Configuration for the client end of an HTTP tunnel. Any socket channel properties set here
 * will be applied uniformly to the underlying send and poll channels, created from the channel
 * factory provided to the {@link HttpTunnelClientChannelFactory}.
 * <p>
 * HTTP tunnel clients have the following additional options:
 *
 * <table border="1" cellspacing="0" cellpadding="6">
 * <tr>
 * <th>Name</th><th>Associated setter method</th>
 * </tr>
 * <tr><td>{@code "proxyAddress"}</td><td>{@link #setProxyAddress(SocketAddress)}</td></tr>
 * </table>
 */
public class HttpTunnelClientChannelConfig extends HttpTunnelChannelConfig {

    static final String PROXY_ADDRESS_OPTION = "proxyAddress";

    private final SocketChannelConfig sendChannelConfig;

    private final SocketChannelConfig pollChannelConfig;

    private volatile SocketAddress proxyAddress;

    HttpTunnelClientChannelConfig(SocketChannel sendChannel,
                                  SocketChannel pollChannel) {
        super(pollChannel);
        sendChannelConfig = sendChannel.config();
        pollChannelConfig = sendChannel.config();
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                HttpTunnelChannelOption.PROXY_ADRESS);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == HttpTunnelChannelOption.PROXY_ADRESS) {
            return (T) getProxyAddress();
        }
        return super.getOption(option);
    }

    // TODO Support all options in the old tunnel (see HttpTunnelingSocketChannelConfig)
    //      Mostly SSL, virtual host, and URL prefix
    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == HttpTunnelChannelOption.PROXY_ADRESS) {
            setProxyAddress((SocketAddress) value);
        } else {
            return super.setOption(option, value);
        }

        return true;
    }
    /**
     * @return the address of the http proxy. If this is null, then no proxy
     * should be used.
     */
    public SocketAddress getProxyAddress() {
        return proxyAddress;
    }

    /**
     * Specify a proxy to be used for the http tunnel. If this is null, then
     * no proxy should be used, otherwise this should be a directly accessible IPv4/IPv6
     * address and port.
     */
    public HttpTunnelClientChannelConfig setProxyAddress(SocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
        return this;
    }


    @Override
    public HttpTunnelClientChannelConfig setKeepAlive(boolean keepAlive) {
        pollChannelConfig.setKeepAlive(keepAlive);
        sendChannelConfig.setKeepAlive(keepAlive);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setPerformancePreferences(int connectionTime, int latency,
            int bandwidth) {
        pollChannelConfig.setPerformancePreferences(connectionTime, latency,
                bandwidth);
        sendChannelConfig.setPerformancePreferences(connectionTime, latency,
                bandwidth);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setReceiveBufferSize(int receiveBufferSize) {
        pollChannelConfig.setReceiveBufferSize(receiveBufferSize);
        sendChannelConfig.setReceiveBufferSize(receiveBufferSize);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setReuseAddress(boolean reuseAddress) {
        pollChannelConfig.setReuseAddress(reuseAddress);
        sendChannelConfig.setReuseAddress(reuseAddress);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setSendBufferSize(int sendBufferSize) {
        pollChannelConfig.setSendBufferSize(sendBufferSize);
        sendChannelConfig.setSendBufferSize(sendBufferSize);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setSoLinger(int soLinger) {
        pollChannelConfig.setSoLinger(soLinger);
        sendChannelConfig.setSoLinger(soLinger);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setTcpNoDelay(boolean tcpNoDelay) {
        pollChannelConfig.setTcpNoDelay(true);
        sendChannelConfig.setTcpNoDelay(true);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setTrafficClass(int trafficClass) {
        pollChannelConfig.setTrafficClass(1);
        sendChannelConfig.setTrafficClass(1);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setAllowHalfClosure(boolean allowHalfClosure) {
        pollChannelConfig.setAllowHalfClosure(allowHalfClosure);
        sendChannelConfig.setAllowHalfClosure(allowHalfClosure);
        return this;
    }

    @Override
    public boolean isTcpNoDelay() {
        return pollChannelConfig.isTcpNoDelay();
    }

    @Override
    public int getSoLinger() {
        return pollChannelConfig.getSoLinger();
    }

    @Override
    public int getSendBufferSize() {
        return pollChannelConfig.getSendBufferSize();
    }

    @Override
    public int getReceiveBufferSize() {
        return pollChannelConfig.getReceiveBufferSize();
    }

    @Override
    public boolean isKeepAlive() {
        return pollChannelConfig.isKeepAlive();
    }

    @Override
    public int getTrafficClass() {
        return pollChannelConfig.getTrafficClass();
    }

    @Override
    public boolean isReuseAddress() {
        return pollChannelConfig.isKeepAlive();
    }

    @Override
    public boolean isAllowHalfClosure() {
        return pollChannelConfig.isAllowHalfClosure();
    }

    @Override
    public HttpTunnelClientChannelConfig setWriteSpinCount(int writeSpinCount) {
        super.setWriteSpinCount(writeSpinCount);
        sendChannelConfig.setWriteSpinCount(writeSpinCount);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        super.setConnectTimeoutMillis(connectTimeoutMillis);
        sendChannelConfig.setConnectTimeoutMillis(connectTimeoutMillis);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setAutoRead(boolean autoRead) {
        super.setAutoRead(autoRead);
        sendChannelConfig.setAutoRead(autoRead);
        return this;
    }


    @Override
    public HttpTunnelClientChannelConfig setDefaultHandlerByteBufType(ChannelHandlerByteBufType handlerByteBufType) {
        super.setDefaultHandlerByteBufType(handlerByteBufType);
        sendChannelConfig.setDefaultHandlerByteBufType(handlerByteBufType);
        return this;
    }

    @Override
    public HttpTunnelClientChannelConfig setAllocator(ByteBufAllocator allocator) {
        super.setAllocator(allocator);
        sendChannelConfig.setAllocator(allocator);
        return this;
    }
}
