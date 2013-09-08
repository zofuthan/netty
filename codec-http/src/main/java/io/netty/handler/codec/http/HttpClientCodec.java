/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.PrematureChannelClosureException;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A combination of {@link HttpRequestEncoder} and {@link HttpResponseDecoder}
 * which enables easier client side HTTP implementation. {@link HttpClientCodec}
 * provides additional state management for <tt>HEAD</tt> and <tt>CONNECT</tt>
 * requests, which {@link HttpResponseDecoder} lacks.  Please refer to
 * {@link HttpResponseDecoder} to learn what additional state management needs
 * to be done for <tt>HEAD</tt> and <tt>CONNECT</tt> and why
 * {@link HttpResponseDecoder} can not handle it by itself.
 *
 * If the {@link Channel} is closed and there are missing responses,
 * a {@link PrematureChannelClosureException} is thrown.
 *
 */
public final class HttpClientCodec
        implements ChannelHandler {

    /** A queue that is used for correlating a request and a response. */
    private final Queue<HttpMethod> queue = new ArrayDeque<HttpMethod>();

    /** If true, decoding stops (i.e. pass-through) */
    private boolean done;

    private final AtomicLong requestResponseCounter = new AtomicLong();
    private final boolean failOnMissingResponse;
    private final Decoder decoder;
    private final Encoder encoder;

    /**
     * Creates a new instance with the default decoder options
     * ({@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}).
     */
    public HttpClientCodec() {
        this(4096, 8192, 8192, false);
    }

    public void setSingleDecode(boolean singleDecode) {
        decoder.setSingleDecode(singleDecode);
    }

    public boolean isSingleDecode() {
        return decoder.isSingleDecode();
    }

    /**
     * Creates a new instance with the specified decoder options.
     */
    public HttpClientCodec(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, false);
    }

    public HttpClientCodec(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean failOnMissingResponse) {
        decoder = new Decoder(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        encoder = new Encoder();
        this.failOnMissingResponse = failOnMissingResponse;
    }

    private final class Encoder extends HttpRequestEncoder {

        @Override
        protected void encode(
                ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
            if (msg instanceof HttpRequest && !done) {
                queue.offer(((HttpRequest) msg).getMethod());
            }

            super.encode(ctx, msg, out);

            if (failOnMissingResponse) {
                // check if the request is chunked if so do not increment
                if (msg instanceof LastHttpContent) {
                    // increment as its the last chunk
                    requestResponseCounter.incrementAndGet();
                }
            }
        }
    }

    private final class Decoder extends HttpResponseDecoder {
        Decoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        protected void decode(
                ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
            if (done) {
                int readable = actualReadableBytes();
                if (readable == 0) {
                    // if non is readable just return null
                    // https://github.com/netty/netty/issues/1159
                    return;
                }
                out.add(buffer.readBytes(readable));
            } else {
                int oldSize = out.size();
                super.decode(ctx, buffer, out);
                if (failOnMissingResponse) {
                    int size = out.size();
                    for (int i = oldSize; i < size; i++) {
                        decrement(out.get(i));
                    }
                }
            }
        }

        private void decrement(Object msg) {
            if (msg == null) {
                return;
            }

            // check if it's an Header and its transfer encoding is not chunked.
            if (msg instanceof LastHttpContent) {
                requestResponseCounter.decrementAndGet();
            }
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage msg) {
            final int statusCode = ((HttpResponse) msg).getStatus().code();
            if (statusCode == 100) {
                // 100-continue response should be excluded from paired comparison.
                return true;
            }

            // Get the getMethod of the HTTP request that corresponds to the
            // current response.
            HttpMethod method = queue.poll();

            char firstChar = method.name().charAt(0);
            switch (firstChar) {
            case 'H':
                // According to 4.3, RFC2616:
                // All responses to the HEAD request getMethod MUST NOT include a
                // message-body, even though the presence of entity-header fields
                // might lead one to believe they do.
                if (HttpMethod.HEAD.equals(method)) {
                    return true;

                    // The following code was inserted to work around the servers
                    // that behave incorrectly.  It has been commented out
                    // because it does not work with well behaving servers.
                    // Please note, even if the 'Transfer-Encoding: chunked'
                    // header exists in the HEAD response, the response should
                    // have absolutely no content.
                    //
                    //// Interesting edge case:
                    //// Some poorly implemented servers will send a zero-byte
                    //// chunk if Transfer-Encoding of the response is 'chunked'.
                    ////
                    //// return !msg.isChunked();
                }
                break;
            case 'C':
                // Successful CONNECT request results in a response with empty body.
                if (statusCode == 200) {
                    if (HttpMethod.CONNECT.equals(method)) {
                        // Proxy connection established - Not HTTP anymore.
                        done = true;
                        queue.clear();
                        return true;
                    }
                }
                break;
            }

            return super.isContentAlwaysEmpty(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx)
                throws Exception {
            super.channelInactive(ctx);

            if (failOnMissingResponse) {
                long missingResponses = requestResponseCounter.get();
                if (missingResponses > 0) {
                    ctx.fireExceptionCaught(new PrematureChannelClosureException(
                            "channel gone inactive with " + missingResponses +
                            " missing response(s)"));
                }
            }
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        try {
            decoder.handlerAdded(ctx);
        } finally {
            encoder.handlerAdded(ctx);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        try {
            decoder.handlerRemoved(ctx);
        } finally {
            encoder.handlerRemoved(ctx);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        decoder.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        decoder.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        decoder.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        decoder.channelRead(ctx, msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        decoder.channelReadComplete(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        decoder.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        decoder.channelWritabilityChanged(ctx);
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        encoder.bind(ctx, localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {
        encoder.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        encoder.disconnect(ctx, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        encoder.close(ctx, promise);
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        encoder.read(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        encoder.write(ctx, msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        encoder.flush(ctx);
    }
}
