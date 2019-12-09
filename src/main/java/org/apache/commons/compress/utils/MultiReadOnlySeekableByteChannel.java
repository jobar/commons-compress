/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.commons.compress.utils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Read-Only Implementation of {@link SeekableByteChannel} that
 * concatenates a collection of other {@link SeekableByteChannel}s.
 *
 * <p>This is a lose port of <a
 * href="https://github.com/frugalmechanic/fm-common/blob/master/jvm/src/main/scala/fm/common/MultiReadOnlySeekableByteChannel.scala">MultiReadOnlySeekableByteChannel</a>
 * by Tim Underwood.</p>
 *
 * @since 1.19
 */
public class MultiReadOnlySeekableByteChannel implements SeekableByteChannel {

    private final List<SeekableByteChannel> channels;
    private long globalPosition;
    private int currentChannelIdx;

    /**
     * Concatenates the given channels.
     *
     * @param channels the channels to concatenate
     * @throws NullPointerException if channels is null
     */
    public MultiReadOnlySeekableByteChannel(List<SeekableByteChannel> channels) {
        this.channels = Collections.unmodifiableList(new ArrayList<>(
            Objects.requireNonNull(channels, "channels must not be null")));
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        if (!dst.hasRemaining()) {
            return 0;
        }

        int totalBytesRead = 0;
        while (dst.hasRemaining() && currentChannelIdx < channels.size()) {
            final SeekableByteChannel currentChannel = channels.get(currentChannelIdx);
            final int newBytesRead = currentChannel.read(dst);
            if (newBytesRead == -1) {
                // EOF for this channel -- advance to next channel idx
                currentChannelIdx += 1;
                continue;
            }
            if (currentChannel.position() >= currentChannel.size()) {
                // we are at the end of the current channel
                currentChannelIdx++;
            }
            totalBytesRead += newBytesRead;
        }
        if (totalBytesRead > 0) {
            globalPosition += totalBytesRead;
            return totalBytesRead;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (SeekableByteChannel ch : channels) {
            try {
                ch.close();
            } catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        if (first != null) {
            throw new IOException("failed to close wrapped channel", first);
        }
    }

    @Override
    public boolean isOpen() {
        for (SeekableByteChannel ch : channels) {
            if (!ch.isOpen()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long position() {
        return globalPosition;
    }

    /**
     * set the position based on the given channel number and relative offset
     *
     * @param channelNumber  the channel number
     * @param relativeOffset the relative offset in the corresponding channel
     * @return global position of all channels as if they are a single channel
     * @throws IOException
     */
    public synchronized SeekableByteChannel position(long channelNumber, long relativeOffset) throws IOException {
        long globalPosition = relativeOffset;
        for (int i = 0; i < channelNumber; i++) {
            globalPosition += channels.get(i).size();
        }

        return position(globalPosition);
    }

    @Override
    public long size() throws IOException {
        long acc = 0;
        for (SeekableByteChannel ch : channels) {
            acc += ch.size();
        }
        return acc;
    }

    /**
     * @throws NonWritableChannelException since this implementation is read-only.
     */
    @Override
    public SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    /**
     * @throws NonWritableChannelException since this implementation is read-only.
     */
    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position: " + newPosition);
        }
        if (!isOpen()) {
            throw new ClosedChannelException();
        }

        globalPosition = newPosition;

        long pos = newPosition;

        for (int i = 0; i < channels.size(); i++) {
            final SeekableByteChannel currentChannel = channels.get(i);
            final long size = currentChannel.size();

            final long newChannelPos;
            if (pos == -1L) {
                // Position is already set for the correct channel,
                // the rest of the channels get reset to 0
                newChannelPos = 0;
            } else if (pos <= size) {
                // This channel is where we want to be
                currentChannelIdx = i;
                long tmp = pos;
                pos = -1L; // Mark pos as already being set
                newChannelPos = tmp;
            } else {
                // newPosition is past this channel.  Set channel
                // position to the end and substract channel size from
                // pos
                pos -= size;
                newChannelPos = size;
            }

            currentChannel.position(newChannelPos);
        }
        return this;
    }

    /**
     * Concatenates the given channels.
     *
     * @param channels the channels to concatenate
     * @throws NullPointerException if channels is null
     * @return SeekableByteChannel that concatenates all provided channels
     */
    public static SeekableByteChannel forSeekableByteChannels(SeekableByteChannel... channels) {
        if (Objects.requireNonNull(channels, "channels must not be null").length == 1) {
            return channels[0];
        }
        return new MultiReadOnlySeekableByteChannel(Arrays.asList(channels));
    }

    /**
     * Concatenates the given files.
     *
     * @param files the files to concatenate
     * @throws NullPointerException if files is null
     * @throws IOException if opening a channel for one of the files fails
     * @return SeekableByteChannel that concatenates all provided files
     */
    public static SeekableByteChannel forFiles(File... files) throws IOException {
        List<SeekableByteChannel> channels = new ArrayList<>();
        for (File f : Objects.requireNonNull(files, "files must not be null")) {
            channels.add(Files.newByteChannel(f.toPath(), StandardOpenOption.READ));
        }
        if (channels.size() == 1) {
            return channels.get(0);
        }
        return new MultiReadOnlySeekableByteChannel(channels);
    }

}
