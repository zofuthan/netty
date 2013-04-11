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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.MessageBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * Downstream handler which places an upper bound on the size of written
 * {@link ByteBuf}s. If a buffer
 * is bigger than the specified upper bound, the buffer is broken up
 * into two or more smaller pieces.
 * <p>
 * This is utilised by the http tunnel to smooth out the per-byte latency,
 * by placing an upper bound on HTTP request / response body sizes.
 */
public class WriteFragmenter extends MessageToMessageEncoder<ByteBuf> {

    public static final String NAME = "writeFragmenter";

    private int splitThreshold;

    public WriteFragmenter(int splitThreshold) {
        this.splitThreshold = splitThreshold;
    }

    public void setSplitThreshold(int splitThreshold) {
        this.splitThreshold = splitThreshold;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf data, MessageBuf<Object> out) throws Exception {
        if (data.readableBytes() <= splitThreshold) {
            out.add(data);
        } else {
            out.addAll(WriteSplitter.split(data, splitThreshold));
        }
    }
}
