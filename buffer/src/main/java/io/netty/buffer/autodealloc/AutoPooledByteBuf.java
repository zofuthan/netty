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

package io.netty.buffer.autodealloc;

import io.netty.buffer.AbstractReferenceCountedByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorStats;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

abstract class AutoPooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    private static final ReferenceQueue<AutoPooledByteBuf> refQueue = new ReferenceQueue<AutoPooledByteBuf>();

    // Keep the linked list of all buffer reference.  Otherwise, the references themselves will be GC'd and we will
    // never notified when the buffer becomes unreachable.S
    private static final BufRef head = new BufRef(null);
    private static final BufRef tail = new BufRef(null);

    static {
        head.next = tail;
        tail.prev = head;
    }

    private static final class BufRef extends PhantomReference<AutoPooledByteBuf> {

        private volatile AutoPoolChunk chunk;
        private volatile long handle;
        private final AtomicBoolean freed;
        private BufRef prev;
        private BufRef next;

        public BufRef(AutoPooledByteBuf referent) {
            super(referent, referent != null? refQueue : null);

            if (referent != null) {
                // TODO: Use CAS to update the list.
                synchronized (head) {
                    prev = head;
                    next = head.next;
                    head.next.prev = this;
                    head.next = this;
                }
                freed = new AtomicBoolean();
            } else {
                freed = new AtomicBoolean(true); // head and tail
            }
        }

        @Override
        public void clear() {
            super.clear();
            if (freed.compareAndSet(false, true)) {
                synchronized (head) {
                    prev.next = next;
                    next.prev = prev;
                    prev = null;
                    next = null;
                }
                chunk.arena.free(chunk, handle);
                ByteBufAllocatorStats.nDeallocs ++;
            }
        }
    }

    private BufRef ref = new BufRef(this);
    protected AutoPoolChunk<T> chunk;
    protected long handle;
    protected T memory;
    protected int offset;
    protected int length;
    private int maxLength;

    private ByteBuffer tmpNioBuf;
    private Queue<Allocation<T>> suspendedDeallocations;

    protected AutoPooledByteBuf(int maxCapacity) {
        super(maxCapacity);
        ByteBufAllocatorStats.nAllocs ++;

        // Return the memory region of the GC'd buffers to the pool.
        for (;;) {
            BufRef ref = (BufRef) refQueue.poll();
            if (ref == null) {
                break;
            }
            ref.clear();
        }
    }

    void init(AutoPoolChunk<T> chunk, long handle, int offset, int length, int maxLength) {
        assert handle >= 0;
        assert chunk != null;

        this.chunk = chunk;
        this.handle = handle;
        memory = chunk.memory;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
        setIndex(0, 0);
        tmpNioBuf = null;

        ref.chunk = chunk;
        ref.handle = handle;
    }

    void initUnpooled(AutoPoolChunk<T> chunk, int length) {
        assert chunk != null;

        this.chunk = chunk;
        handle = 0;
        memory = chunk.memory;
        offset = 0;
        this.length = maxLength = length;
        setIndex(0, 0);
        tmpNioBuf = null;

        ref.chunk = chunk;
        ref.handle = 0;
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        ensureAccessible();

        // If the request capacity does not require reallocation, just update the length of the memory.
        if (chunk.unpooled) {
            if (newCapacity == length) {
                return this;
            }
        } else {
            if (newCapacity > length) {
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            } else if (newCapacity < length) {
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {
                return this;
            }
        }

        // Reallocation required.
        if (suspendedDeallocations == null) {
            chunk.arena.reallocate(this, newCapacity, true);
        } else {
            Allocation<T> old = new Allocation<T>(chunk, handle);
            chunk.arena.reallocate(this, newCapacity, false);
            suspendedDeallocations.add(old);
        }
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return chunk.arena.parent;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    public final ByteBuf suspendIntermediaryDeallocations() {
        ensureAccessible();
        if (suspendedDeallocations == null) {
            suspendedDeallocations = new ArrayDeque<Allocation<T>>(2);
        }
        return this;
    }

    @Override
    public final ByteBuf resumeIntermediaryDeallocations() {
        if (suspendedDeallocations == null) {
            return this;
        }

        Queue<Allocation<T>> suspendedDeallocations = this.suspendedDeallocations;
        this.suspendedDeallocations = null;

        if (suspendedDeallocations.isEmpty()) {
            return this;
        }

        for (Allocation<T> a: suspendedDeallocations) {
            a.chunk.arena.free(a.chunk, a.handle);
        }
        return this;
    }

    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            resumeIntermediaryDeallocations();
            handle = -1;
            memory = null;

            // Do not free immediately. Always leave it to GC.
            // chunk.arena.free(chunk, handle);
        }
    }

    protected final int idx(int index) {
        return offset + index;
    }

    private static final class Allocation<T> {
        final AutoPoolChunk<T> chunk;
        final long handle;

        Allocation(AutoPoolChunk<T> chunk, long handle) {
            this.chunk = chunk;
            this.handle = handle;
        }
    }
}
