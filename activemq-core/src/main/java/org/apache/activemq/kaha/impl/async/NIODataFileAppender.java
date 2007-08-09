/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.kaha.impl.async;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * An AsyncDataFileAppender that uses NIO ByteBuffers and File chanels to more
 * efficently copy data to files.
 * 
 * @version $Revision: 1.1.1.1 $
 */
class NIODataFileAppender extends DataFileAppender {

    public NIODataFileAppender(AsyncDataManager fileManager) {
        super(fileManager);
    }

    /**
     * The async processing loop that writes to the data files and does the
     * force calls.
     * 
     * Since the file sync() call is the slowest of all the operations, this
     * algorithm tries to 'batch' or group together several file sync() requests
     * into a single file sync() call. The batching is accomplished attaching
     * the same CountDownLatch instance to every force request in a group.
     * 
     */
    protected void processQueue() {
        DataFile dataFile = null;
        RandomAccessFile file = null;
        FileChannel channel = null;

        try {

            ByteBuffer header = ByteBuffer.allocateDirect(AsyncDataManager.ITEM_HEAD_SPACE);
            ByteBuffer footer = ByteBuffer.allocateDirect(AsyncDataManager.ITEM_FOOT_SPACE);
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxWriteBatchSize);

            // Populate the static parts of the headers and footers..
            header.putInt(0); // size
            header.put((byte)0); // type
            header.put(RESERVED_SPACE); // reserved
            header.put(AsyncDataManager.ITEM_HEAD_SOR);
            footer.put(AsyncDataManager.ITEM_HEAD_EOR);

            while (true) {

                Object o = null;

                // Block till we get a command.
                synchronized (enqueueMutex) {
                    while (true) {
                        if (shutdown) {
                            o = SHUTDOWN_COMMAND;
                            break;
                        }
                        if (nextWriteBatch != null) {
                            o = nextWriteBatch;
                            nextWriteBatch = null;
                            break;
                        }
                        enqueueMutex.wait();
                    }
                    enqueueMutex.notify();
                }

                if (o == SHUTDOWN_COMMAND) {
                    break;
                }

                WriteBatch wb = (WriteBatch)o;
                if (dataFile != wb.dataFile) {
                    if (file != null) {
                        dataFile.closeRandomAccessFile(file);
                    }
                    dataFile = wb.dataFile;
                    file = dataFile.openRandomAccessFile(true);
                    channel = file.getChannel();
                }

                WriteCommand write = wb.first;

                // Write all the data.
                // Only need to seek to first location.. all others
                // are in sequence.
                file.seek(write.location.getOffset());

                // 
                // is it just 1 big write?
                if (wb.size == write.location.getSize()) {

                    header.clear();
                    header.putInt(write.location.getSize());
                    header.put(write.location.getType());
                    header.clear();
                    transfer(header, channel);
                    ByteBuffer source = ByteBuffer.wrap(write.data.getData(), write.data.getOffset(),
                                                        write.data.getLength());
                    transfer(source, channel);
                    footer.clear();
                    transfer(footer, channel);

                } else {

                    // Combine the smaller writes into 1 big buffer
                    while (write != null) {

                        header.clear();
                        header.putInt(write.location.getSize());
                        header.put(write.location.getType());
                        header.clear();
                        copy(header, buffer);
                        assert !header.hasRemaining();

                        ByteBuffer source = ByteBuffer.wrap(write.data.getData(), write.data.getOffset(),
                                                            write.data.getLength());
                        copy(source, buffer);
                        assert !source.hasRemaining();

                        footer.clear();
                        copy(footer, buffer);
                        assert !footer.hasRemaining();

                        write = (WriteCommand)write.getNext();
                    }

                    // Fully write out the buffer..
                    buffer.flip();
                    transfer(buffer, channel);
                    buffer.clear();
                }

                file.getChannel().force(false);

                WriteCommand lastWrite = (WriteCommand)wb.first.getTailNode();
                dataManager.setLastAppendLocation(lastWrite.location);

                // Signal any waiting threads that the write is on disk.
                if (wb.latch != null) {
                    wb.latch.countDown();
                }

                // Now that the data is on disk, remove the writes from the in
                // flight
                // cache.
                write = wb.first;
                while (write != null) {
                    if (!write.sync) {
                        inflightWrites.remove(new WriteKey(write.location));
                    }
                    write = (WriteCommand)write.getNext();
                }
            }

        } catch (IOException e) {
            synchronized (enqueueMutex) {
                firstAsyncException = e;
            }
        } catch (InterruptedException e) {
        } finally {
            try {
                if (file != null) {
                    dataFile.closeRandomAccessFile(file);
                }
            } catch (IOException e) {
            }
            shutdownDone.countDown();
        }
    }

    /**
     * Copy the bytes in header to the channel.
     * 
     * @param header - source of data
     * @param channel - destination where the data will be written.
     * @throws IOException
     */
    private void transfer(ByteBuffer header, FileChannel channel) throws IOException {
        while (header.hasRemaining()) {
            channel.write(header);
        }
    }

    private int copy(ByteBuffer src, ByteBuffer dest) {
        int rc = Math.min(dest.remaining(), src.remaining());
        if (rc > 0) {
            // Adjust our limit so that we don't overflow the dest buffer.
            int limit = src.limit();
            src.limit(src.position() + rc);
            dest.put(src);
            // restore the limit.
            src.limit(limit);
        }
        return rc;
    }

}
