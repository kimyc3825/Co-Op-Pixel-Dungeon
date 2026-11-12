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
 * WEB-PORT: shadows org.teavm.classlib.java.util.zip.TInflater from teavm-classlib 0.15.0.
 * Same implementation, except that zlib's Z_BUF_ERROR (-5, "no progress possible") returns 0
 * instead of throwing DataFormatException, matching the JDK. GZIPInputStream can call inflate()
 * when no more input is available, which made loading compressed saves fail.
 * Remove this file once TeaVM fixes it upstream.
 */
package org.teavm.classlib.java.util.zip;

import com.jcraft.jzlib.GZIPException;
import java.util.Arrays;
import org.teavm.classlib.java.lang.TAutoCloseable;

public class TInflater implements TAutoCloseable {
    private static final int Z_OK = 0;
    private static final int Z_STREAM_END = 1;
    private static final int Z_NEED_DICT = 2;
    private static final int Z_BUF_ERROR = -5;

    private boolean finished;
    private boolean nowrap;
    int inLength;
    int inRead;
    private boolean needsDictionary;
    private com.jcraft.jzlib.Inflater impl;

    public TInflater() {
        this(false);
    }

    public TInflater(boolean noHeader) {
        nowrap = noHeader;
        try {
            impl = new com.jcraft.jzlib.Inflater(noHeader);
        } catch (GZIPException e) {
            // do nothing
        }
    }

    public void end() {
        inRead = 0;
        inLength = 0;
        impl = null;
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

    public long getBytesRead() {
        if (impl == null) {
            throw new IllegalStateException();
        }
        return impl.getTotalIn();
    }

    public long getBytesWritten() {
        if (impl == null) {
            throw new IllegalStateException();
        }
        return impl.getTotalOut();
    }

    public int getRemaining() {
        return inLength - inRead;
    }

    public int getTotalIn() {
        return (int) getBytesRead();
    }

    public int getTotalOut() {
        return (int) getBytesWritten();
    }

    public int inflate(byte[] buf) throws TDataFormatException {
        return inflate(buf, 0, buf.length);
    }

    public int inflate(byte[] buf, int off, int nbytes) throws TDataFormatException {
        if (off > buf.length || nbytes < 0 || off < 0 || buf.length - off < nbytes) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (impl == null) {
            throw new IllegalStateException();
        }

        long lastInSize = impl.total_in;
        long lastOutSize = impl.total_out;
        boolean neededDict = needsDictionary;
        needsDictionary = false;
        impl.setOutput(buf, off, nbytes);
        int errCode = impl.inflate(0);
        switch (errCode) {
            case Z_OK:
            case Z_BUF_ERROR:
                break;
            case Z_NEED_DICT:
                needsDictionary = true;
                break;
            case Z_STREAM_END:
                finished = true;
                break;
            default:
                throw new TDataFormatException("Error occurred: " + errCode);
        }
        if (needsDictionary && neededDict) {
            throw new TDataFormatException();
        }

        inRead += (int) (impl.total_in - lastInSize);
        return (int) (impl.total_out - lastOutSize);
    }

    public boolean needsDictionary() {
        return needsDictionary;
    }

    public boolean needsInput() {
        return inRead == inLength;
    }

    public void reset() {
        if (impl == null) {
            throw new NullPointerException();
        }
        finished = false;
        needsDictionary = false;
        inLength = 0;
        inRead = 0;
        impl.init(nowrap);
    }

    public void setDictionary(byte[] buf) {
        setDictionary(buf, 0, buf.length);
    }

    public void setDictionary(byte[] buf, int off, int nbytes) {
        if (impl == null) {
            throw new IllegalStateException();
        }
        if (off <= buf.length && nbytes >= 0 && off >= 0 && buf.length - off >= nbytes) {
            if (off > 0) {
                buf = Arrays.copyOfRange(buf, off, buf.length);
            }
            impl.setDictionary(buf, nbytes);
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
            inRead = 0;
            inLength = nbytes;
            impl.setInput(buf, off, nbytes, false);
        } else {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    @Override
    public void close() throws Exception {
        end();
    }
}
