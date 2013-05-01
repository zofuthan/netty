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
package org.jboss.netty.channel.socket.nio;

import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.internal.SystemPropertyUtil;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class SelectorUtil {
    private static final InternalLogger logger =
        InternalLoggerFactory.getInstance(SelectorUtil.class);

    static final int DEFAULT_IO_THREADS = Runtime.getRuntime().availableProcessors() * 2;
    static final long DEFAULT_SELECT_TIMEOUT = 500;
    static final long SELECT_TIMEOUT =
            SystemPropertyUtil.getLong("org.jboss.netty.selectTimeout", DEFAULT_SELECT_TIMEOUT);
    static final long SELECT_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(SELECT_TIMEOUT);
    static final boolean EPOLL_BUG_WORKAROUND =
            SystemPropertyUtil.getBoolean("org.jboss.netty.epollBugWorkaround", false);

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        String key = "sun.nio.ch.bugLevel";
        try {
            String buglevel = System.getProperty(key);
            if (buglevel == null) {
                System.setProperty(key, "");
            }
        } catch (SecurityException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to get/set System Property '" + key + '\'', e);
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Using select timeout of " + SELECT_TIMEOUT);
            logger.debug("Epoll-bug workaround enabled = " + EPOLL_BUG_WORKAROUND);
        }
    }

    static void wakeup(Selector selector) {
        if (logger.isDebugEnabled()) {
            logger.debug("Selector[" + System.identityHashCode(selector) + "].wakeup()");
        }
        selector.wakeup();
    }

    static int select(Selector selector) throws IOException {
        try {
            long startTime = System.nanoTime();
            int selectedKeys = selector.select(SELECT_TIMEOUT);
            long endTime = System.nanoTime();

            if (logger.isDebugEnabled()) {

                StringBuilder buf = new StringBuilder();
                buf.append("Selector[");
                buf.append(System.identityHashCode(selector));
                buf.append("].select(int) returned ");
                buf.append(selectedKeys);
                buf.append(" out of ");
                buf.append(selector.keys().size());
                buf.append(" after ");
                buf.append(endTime - startTime);
                buf.append(" ns");
                logger.debug(buf.toString());
                buf.setLength(0);

                Set<SelectionKey> selectedKeySet = selector.selectedKeys();
                buf.append("Selector[");
                buf.append(System.identityHashCode(selector));
                buf.append("].selectedKeys() = ");
                buf.append(selectedKeySet.size());
                for (SelectionKey k: selectedKeySet) {
                    buf.append(", (");
                    buf.append(k.attachment());
                    buf.append(": ");
                    if (k.isValid()) {
                        buf.append(k.readyOps());
                        buf.append('/');
                        buf.append(k.interestOps());
                    } else {
                        buf.append("invalid");
                    }
                    buf.append(')');
                }
                logger.debug(buf.toString());
                buf.setLength(0);
            }

            return selectedKeys;
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        CancelledKeyException.class.getSimpleName() +
                        " raised by a Selector - JDK bug?", e);
            }
            // Harmless exception - log anyway
        }

        return -1;
    }

    static int selectWithoutTimeout(Selector selector) throws IOException {
        try {
            long startTime = System.nanoTime();
            int selectedKeys = selector.select();
            long endTime = System.nanoTime();

            if (logger.isDebugEnabled()) {

                StringBuilder buf = new StringBuilder();
                buf.append("Selector[");
                buf.append(System.identityHashCode(selector));
                buf.append("].select() returned ");
                buf.append(selectedKeys);
                buf.append(" out of ");
                buf.append(selector.keys().size());
                buf.append(" after ");
                buf.append(endTime - startTime);
                buf.append(" ns");
                logger.debug(buf.toString());
                buf.setLength(0);

                Set<SelectionKey> selectedKeySet = selector.selectedKeys();
                buf.append("Selector[");
                buf.append(System.identityHashCode(selector));
                buf.append("].selectedKeys() = ");
                buf.append(selectedKeySet.size());
                for (SelectionKey k: selectedKeySet) {
                    buf.append(", (");
                    buf.append(k.attachment());
                    buf.append(": ");
                    if (k.isValid()) {
                        buf.append(k.readyOps());
                        buf.append('/');
                        buf.append(k.interestOps());
                    } else {
                        buf.append("invalid");
                    }
                    buf.append(')');
                }
                logger.debug(buf.toString());
                buf.setLength(0);
            }

            return selectedKeys;
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(
                        CancelledKeyException.class.getSimpleName() +
                                " raised by a Selector - JDK bug?", e);
            }
            // Harmless exception - log anyway
        }

        return -1;
    }

    private SelectorUtil() {
        // Unused
    }
}
