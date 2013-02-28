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

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

abstract class AutoPoolArena<T> {

    final AutoPooledByteBufAllocator parent;

    private final int pageSize;
    private final int maxOrder;
    private final int pageShifts;
    private final int chunkSize;
    private final int subpageOverflowMask;

    private final Deque<AutoPoolSubpage<T>>[] tinySubpagePools;
    private final Deque<AutoPoolSubpage<T>>[] smallSubpagePools;

    private final AutoPoolChunkList<T> q050;
    private final AutoPoolChunkList<T> q025;
    private final AutoPoolChunkList<T> q000;
    private final AutoPoolChunkList<T> qInit;
    private final AutoPoolChunkList<T> q075;
    private final AutoPoolChunkList<T> q100;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected AutoPoolArena(
            AutoPooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        subpageOverflowMask = ~(pageSize - 1);

        tinySubpagePools = newSubpagePoolArray(512 >>> 4);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = new ArrayDeque<AutoPoolSubpage<T>>();
        }

        smallSubpagePools = newSubpagePoolArray(pageShifts - 9);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = new ArrayDeque<AutoPoolSubpage<T>>();
        }

        q100 = new AutoPoolChunkList<T>(this, null, 100, Integer.MAX_VALUE);
        q075 = new AutoPoolChunkList<T>(this, q100, 75, 100);
        q050 = new AutoPoolChunkList<T>(this, q075, 50, 100);
        q025 = new AutoPoolChunkList<T>(this, q050, 25, 75);
        q000 = new AutoPoolChunkList<T>(this, q025, 1, 50);
        qInit = new AutoPoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25);

        q100.prevList = q075;
        q075.prevList = q050;
        q050.prevList = q025;
        q025.prevList = q000;
        q000.prevList = null;
        qInit.prevList = qInit;
    }

    @SuppressWarnings("unchecked")
    private Deque<AutoPoolSubpage<T>>[] newSubpagePoolArray(int size) {
        return new Deque[size];
    }

    AutoPooledByteBuf<T> allocate(AutoPoolThreadCache cache, int reqCapacity, int maxCapacity) {
        AutoPooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    private void allocate(AutoPoolThreadCache cache, AutoPooledByteBuf<T> buf, final int reqCapacity) {
        final int normCapacity = normalizeCapacity(reqCapacity);
        if ((normCapacity & subpageOverflowMask) == 0) { // capacity < pageSize
            int tableIdx;
            Deque<AutoPoolSubpage<T>>[] table;
            if ((normCapacity & 0xFFFFFE00) == 0) { // < 512
                tableIdx = normCapacity >>> 4;
                table = tinySubpagePools;
            } else {
                tableIdx = 0;
                int i = normCapacity >>> 10;
                while (i != 0) {
                    i >>>= 1;
                    tableIdx ++;
                }
                table = smallSubpagePools;
            }

            synchronized (this) {
                Deque<AutoPoolSubpage<T>> subpages = table[tableIdx];
                for (;;) {
                    AutoPoolSubpage<T> s = subpages.peekFirst();
                    if (s == null) {
                        break;
                    }

                    if (!s.doNotDestroy || s.elemSize != normCapacity) {
                        // The subpage has been destroyed or being used for different element size.
                        subpages.removeFirst();
                        continue;
                    }

                    long handle = s.allocate();
                    if (handle < 0) {
                        subpages.removeFirst();
                    } else {
                        s.chunk.initBufWithSubpage(buf, handle, reqCapacity);
                        return;
                    }
                }
            }
        } else if (normCapacity > chunkSize) {
            allocateHuge(buf, reqCapacity);
            return;
        }

        allocateNormal(buf, reqCapacity, normCapacity);
    }

    private synchronized void allocateNormal(AutoPooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        AutoPoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        long handle = c.allocate(normCapacity);
        assert handle > 0;
        c.initBuf(buf, handle, reqCapacity);
        qInit.add(c);
    }

    private void allocateHuge(AutoPooledByteBuf<T> buf, int reqCapacity) {
        buf.initUnpooled(newUnpooledChunk(reqCapacity), reqCapacity);
    }

    synchronized void free(AutoPoolChunk<T> chunk, long handle) {
        if (chunk.unpooled) {
            destroyChunk(chunk);
        } else {
            chunk.parent.free(chunk, handle);
        }
    }

    void addSubpage(AutoPoolSubpage<T> subpage) {
        int tableIdx;
        int elemSize = subpage.elemSize;
        Deque<AutoPoolSubpage<T>>[] table;
        if ((elemSize & 0xFFFFFE00) == 0) { // < 512
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        table[tableIdx].addFirst(subpage);
    }

    private int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }
        if (reqCapacity >= chunkSize) {
            return reqCapacity;
        }

        if ((reqCapacity & 0xFFFFFE00) != 0) { // >= 512
            // Doubled
            int normalizedCapacity = 512;
            while (normalizedCapacity < reqCapacity) {
                normalizedCapacity <<= 1;
            }
            return normalizedCapacity;
        }

        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    void reallocate(AutoPooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        AutoPoolChunk<T> oldChunk = buf.chunk;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;

        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache.get(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset + readerIndex,
                    buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        if (freeOldMemory) {
            free(oldChunk, oldHandle);
        }
    }

    protected abstract AutoPoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract AutoPoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract AutoPooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(AutoPoolChunk<T> chunk);

    public synchronized String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Chunk(s) at 0~25%:");
        buf.append(StringUtil.NEWLINE);
        buf.append(qInit);
        buf.append(StringUtil.NEWLINE);
        buf.append("Chunk(s) at 0~50%:");
        buf.append(StringUtil.NEWLINE);
        buf.append(q000);
        buf.append(StringUtil.NEWLINE);
        buf.append("Chunk(s) at 25~75%:");
        buf.append(StringUtil.NEWLINE);
        buf.append(q025);
        buf.append(StringUtil.NEWLINE);
        buf.append("Chunk(s) at 50~100%:");
        buf.append(StringUtil.NEWLINE);
        buf.append(q050);
        buf.append(StringUtil.NEWLINE);
        buf.append("Chunk(s) at 75~100%:");
        buf.append(StringUtil.NEWLINE);
        buf.append(q075);
        buf.append(StringUtil.NEWLINE);
        buf.append("Chunk(s) at 100%:");
        buf.append(StringUtil.NEWLINE);
        buf.append(q100);
        buf.append(StringUtil.NEWLINE);
        buf.append("tiny subpages:");
        for (int i = 1; i < tinySubpagePools.length; i ++) {
            Deque<AutoPoolSubpage<T>> subpages = tinySubpagePools[i];
            if (subpages.isEmpty()) {
                continue;
            }

            buf.append(StringUtil.NEWLINE);
            buf.append(i);
            buf.append(": ");
            buf.append(subpages);
        }
        buf.append(StringUtil.NEWLINE);
        buf.append("small subpages:");
        for (int i = 1; i < smallSubpagePools.length; i ++) {
            Deque<AutoPoolSubpage<T>> subpages = smallSubpagePools[i];
            if (subpages.isEmpty()) {
                continue;
            }

            buf.append(StringUtil.NEWLINE);
            buf.append(i);
            buf.append(": ");
            buf.append(subpages);
        }
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    static final class HeapArena extends AutoPoolArena<byte[]> {

        HeapArena(AutoPooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected AutoPoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new AutoPoolChunk<byte[]>(this, new byte[chunkSize], pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected AutoPoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new AutoPoolChunk<byte[]>(this, new byte[capacity], capacity);
        }

        @Override
        protected void destroyChunk(AutoPoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected AutoPooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return new AutoPooledHeapByteBuf(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends AutoPoolArena<ByteBuffer> {

        private static final boolean USE_UNSAFE_DIRECTBUF =
                PlatformDependent.isUnaligned() && PlatformDependent.unsafeHasCopyMethods();

        DirectArena(AutoPooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected AutoPoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new AutoPoolChunk<ByteBuffer>(
                    this, ByteBuffer.allocateDirect(chunkSize), pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected AutoPoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            return new AutoPoolChunk<ByteBuffer>(this, ByteBuffer.allocateDirect(capacity), capacity);
        }

        @Override
        protected void destroyChunk(AutoPoolChunk<ByteBuffer> chunk) {
            PlatformDependent.freeDirectBuffer(chunk.memory);
        }

        @Override
        protected AutoPooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (USE_UNSAFE_DIRECTBUF) {
                return new AutoPooledUnsafeDirectByteBuf(maxCapacity);
            } else {
                return new AutoPooledDirectByteBuf(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (PlatformDependent.isUnaligned() && PlatformDependent.unsafeHasCopyMethods()) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
