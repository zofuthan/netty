/*
 * Copyright 2013 The Netty Project
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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;

import java.net.SocketAddress;

public final class HttpTunnelChannelOption<T> extends ChannelOption<T> {

    public static final HttpTunnelChannelOption<SocketAddress> PROXY_ADRESS =
            new HttpTunnelChannelOption<SocketAddress>("PROXY_ADDRESS");

    public static final HttpTunnelChannelOption<TunnelIdGenerator> TUNNEL_ID_GENERATOR
            = new HttpTunnelChannelOption<TunnelIdGenerator>("TUNNEL_ID_GENERATOR");

    public static final HttpTunnelChannelOption<ChannelInitializer<? extends SocketChannel>> CHANNEL_INITIALIZER
            = new HttpTunnelChannelOption<ChannelInitializer<? extends SocketChannel>>("CHANNEL_INITIALIZER");

    private HttpTunnelChannelOption(String name) {
        super(name);
    }
}
