/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    // 索引增量
    private static final int INDEX_INCREMENT = 4;
    // 索引减量
    private static final int INDEX_DECREMENT = 1;

    // size table
    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        // 向size数组中 添加 16,32,48-----496
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        // 向siz数组中添加: 512,1024,2048 ---一直到int值溢出成为负数
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            // 64 在SIZE_TABLE的下标
            // 65536在SIZE_TABLE的下标
            // 1024固定值

            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            // 计算出size 1024 在SIZE_TABLE的下标
            index = getSizeTableIndex(initial);
            // 表示下一次分配出来的byteBuf容量大小   默认第一次是1024
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 说明读取的数据量与评估的数据量一直,说明ch中可能还有数据未读完,需要继续读
            if (bytes == attemptedBytesRead()) {
                // 要更新nextReceiveBufferSize大小,评估的量被读满了,需要更大的容器
                record(bytes);
            }
            // 总量记录
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        // actualReadBytes 从ch内读取的数据量
        private void record(int actualReadBytes) {
            // 举个例子
            // 假设SIZE_TABLE[index] = 512  SIZE_TABLE[index - 1 ] = 496
            // 如果本地数据读取量 <= 496 说明ch缓冲区数据不是很多,可能不需要那么大的ByteBuf
            // 如果第二次读取的数据量 <= 496 说明ch的缓冲区不多,不需要这么大的ByteBuf
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    // 不能小于初始化的 SIZE_TABLE[minIndex]
                    index = max(index - INDEX_DECREMENT, minIndex);
                    // 获取相对减小BufferSize值,赋值给 nextReceiveBufferSize
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            }
            // 说明ch读请求,已经将ByteBuf容器装满了.. 可能有更多数据,所以这里index右移一位
            // 获取一个更大的nextReceiveBufferSize,下次构建出更大的ByteBuf对象
            else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            // 整个读请求之后计算一遍容量
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        // 64 1024 65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    // 64 1024 65536
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        // 使用二分查找算法 ,minimum size 在数组内的下标(ps: SIZE_TABLE[下标] <= minimum值)
        int minIndex = getSizeTableIndex(minimum);

        // 确保不能小于minimum值,所以右移index
        if (SIZE_TABLE[minIndex] < minimum) {
            // 确保SIZE_TABLE[minIndex] >= minimum 值
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex
                    = minIndex;
        }
        // 使用二分查找算法 ,maximum size 在数组内的下标(ps: SIZE_TABLE[下标] <= maximum值)
        int maxIndex = getSizeTableIndex(maximum);
        // 确保不能超出minimum值,所以左移index
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        // 初始值 1024
        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
