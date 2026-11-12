/*
 *  Copyright 2025 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 * WEB-PORT: shadows org.teavm.classlib.java.util.zip.TDeflater from teavm-classlib 0.15.0.
 * Same implementation, except that zlib's Z_BUF_ERROR (-5, "no progress possible") is not
 * turned into a RuntimeException. The JDK (and zlib itself) treat it as a normal condition, and
 * GZIPOutputStream hits it when it is closed, so every save of the game (Bundle.write) failed.
 * Remove this file once TeaVM fixes it upstream.
 */
package org.teavm.classlib.java.util.zip;

import com.jcraft.jzlib.GZIPException;
import java.util.Arrays;
import org.teavm.classlib.java.lang.TAutoCloseable;

public class TDeflater implements TAutoCloseable {
    public static final int BEST_COMPRESSION = 9;
    public static final int BEST_SPEED = 1;
    public static final int DEFAULT_COMPRESSION = -1;
    public static final int DEFAULT_STRATEGY = 0;
    public static final int DEFLATED = 8;
    public static final int FILTERED = 1;
    public static final int HUFFMAN_ONLY = 2;
    public static final int NO_COMPRESSION = 0;
    static final int Z_NO_FLUSH = 0;
    static final int Z_SYNC_FLUSH = 2;
    static final int Z_FINISH = 4;

    private static final int Z_OK = 0;
    private static final int Z_STREAM_END = 1;
    private static final int Z_BUF_ERROR = -5;

    private int flushParm = Z_NO_FLUSH;
    private boolean finished;
    private int compressLevel = DEFAULT_COMPRESSION;
    private int strategy = DEFAULT_STRATEGY;
    private com.jcraft.jzlib.Deflater impl;
    private int inRead;
    private int inLength;
    private boolean nowrap;

    public TDeflater() {
        this(DEFAULT_COMPRESSION, false);
    }

    public TDeflater(int level) {
        this(level, false);
    }

    public TDeflater(int level, boolean noHeader) {
        super();
        if (level < DEFAULT_COMPRESSION || level > BEST_COMPRESSION) {
            throw new IllegalArgumentException();
        }
        compressLevel = level;
        try {
            impl = new com.jcraft.jzlib.Deflater(compressLevel, noHeader);
        } catch (GZIPException e) {
            // do nothing
        }
        nowrap = noHeader;
    }

    public int deflate(byte[] buf) {
        return deflate(buf, 0, buf.length);
    }

    public int deflate(byte[] buf, int off, int nbytes) {
        return deflate(buf, off, nbytes, flushParm);
    }

    int deflate(byte[] buf, int off, int nbytes, int flush) {
        if (impl == null) {
            throw new IllegalStateException();
        }
        if (off > buf.length || nbytes < 0 || off < 0 || buf.length - off < nbytes) {
            throw new ArrayIndexOutOfBoundsException();
        }
        long lastInSize = impl.total_in;
        long lastOutSize = impl.total_out;
        impl.setOutput(buf, off, nbytes);
        int err = impl.deflate(flush);
        switch (err) {
            case Z_OK:
            case Z_BUF_ERROR:
                break;
            case Z_STREAM_END:
                finished = true;
                break;
            default:
                throw new RuntimeException("Error: " + err);
        }
        inRead += (int) (impl.total_in - lastInSize);
        return (int) (impl.total_out - lastOutSize);
    }

    public void end() {
        impl = null;
    }

    public void finish() {
        flushParm = Z_FINISH;
    }

    public boolean finished() {
        return finished;
    }

    public int getAdler() {
        if (impl == null) {
            throw new IllegalStateException();
        }
        return (int) impl.getAdler();
    }

    public int getTotalIn() {
        if (impl == null) {
            throw new IllegalStateException();
        }
        return (int) impl.getTotalIn();
    }

    public int getTotalOut() {
        if (impl == null) {
            throw new IllegalStateException();
        }
        return (int) impl.getTotalOut();
    }

    public boolean needsInput() {
        return inRead == inLength;
    }

    public void reset() {
        if (impl == null) {
            throw new NullPointerException();
        }
        flushParm = Z_NO_FLUSH;
        finished = false;
        impl.init(compressLevel, 15, nowrap);
        impl.params(compressLevel, strategy);
    }

    public void setDictionary(byte[] buf) {
        setDictionary(buf, 0, buf.length);
    }

    public void setDictionary(byte[] buf, int off, int nbytes) {
        if (impl == null) {
            throw new IllegalStateException();
        }
        if (off <= buf.length && nbytes >= 0 && off >= 0 && buf.length - off >= nbytes) {
            impl.setDictionary(Arrays.copyOfRange(buf, off, buf.length), nbytes);
        } else {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    public void setInput(byte[] buf) {
        setInput(buf, 0, buf.length);
    }

    public void setInput(byte[] buf, int off, int nbytes) {
        if (impl == null) {
            throw new IllegalStateException();
        }
        if (off <= buf.length && nbytes >= 0 && off >= 0 && buf.length - off >= nbytes) {
            inLength = nbytes;
            inRead = 0;
            if (impl.next_in == null) {
                impl.init(compressLevel, 15, nowrap);
            }
            impl.setInput(buf, off, nbytes, false);
        } else {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    public void setLevel(int level) {
        if (level < DEFAULT_COMPRESSION || level > BEST_COMPRESSION) {
            throw new IllegalArgumentException();
        }
        compressLevel = level;
    }

    public void setStrategy(int strategy) {
        if (strategy < DEFAULT_STRATEGY || strategy > HUFFMAN_ONLY) {
            throw new IllegalArgumentException();
        }
        this.strategy = strategy;
    }

    public long getBytesRead() {
        if (impl == null) {
            throw new NullPointerException();
        }
        return impl.getTotalIn();
    }

    public long getBytesWritten() {
        if (impl == null) {
            throw new NullPointerException();
        }
        return impl.getTotalOut();
    }

    @Override
    public void close() throws Exception {
        end();
    }
}
