/**
 * Copyright 2014 Alexey Ragozin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gridkit.jvmtool;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class StackTraceCodec {

    static final byte[] MAGIC = "TRACEDUMP_1 ".getBytes();
    static final byte[] MAGIC2 = "TRACEDUMP_2 ".getBytes();
    
    static final byte TAG_STRING = 1;  
    static final byte TAG_FRAME = 2;  
    static final byte TAG_TRACE = 3;  
    static final byte TAG_DYN_STRING = 4;
    
    public static StackTraceWriter newWriter(OutputStream os) throws IOException {
        return new StackTraceWriter(os);
    }

    public static StackTraceReader newReader(InputStream is) throws IOException {
        DataInputStream dis = new DataInputStream(is);
        byte[] magic = new byte[MAGIC.length];
        dis.readFully(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IOException("Unknown magic [" + new String(magic) + "]");
        }
        return new StackTraceReaderV1(is);
    }

    public static StackTraceReader newReader(String... files) throws IOException {
        final List<String> fileList = new ArrayList<String>(Arrays.asList(files));
        return new ChainedStackTraceReader() {

            @Override
            protected StackTraceReader next() {
                while(!fileList.isEmpty()) {
                    String file = fileList.remove(0);
                    File f = new File(file);
                    if (!f.isFile()) {
                        continue;
                    }
                    try {
                        FileInputStream fis = new FileInputStream(file);
                        return newReader(fis);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return null;
            }
        };
    }

    abstract static class ChainedStackTraceReader implements StackTraceReader {

        private StackTraceReader current;

        protected abstract StackTraceReader next();

        @Override
        public boolean isLoaded() {
            if (current == null) {
                current = next();
            }
            return current != null && current.isLoaded();
        }


        @Override
        public long getThreadId() {
            if (current == null) {
                new NoSuchElementException();
            }
            return current.getThreadId();
        }

        @Override
        public long getTimestamp() {
            if (current == null) {
                new NoSuchElementException();
            }
            return current.getTimestamp();
        }

        @Override
        public StackTraceElement[] getTrace() {
            if (current == null) {
                new NoSuchElementException();
            }
            return current.getTrace();
        }

        @Override
        public boolean loadNext() throws IOException {
            if (current == null) {
                current = next();
                if (current == null) {
                    return false;
                }
            }
            if (current.loadNext()) {
                return true;
            }
            else {
                current = null;
                return loadNext();
            }
        }
    }
    
    public static class StackTraceWriter {
        
        private DataOutputStream dos;
        private Map<String, Integer> stringDic = new HashMap<String, Integer>();
        private Map<StackTraceElement, Integer> frameDic = new HashMap<StackTraceElement, Integer>();
        private RotatingStringDictionary dynDic = new RotatingStringDictionary(512);

        public StackTraceWriter(OutputStream os) throws IOException {
            os.write(MAGIC);
            DeflaterOutputStream def = new DeflaterOutputStream(os);
            this.dos = new DataOutputStream(def);
        }
        
        public void write(ThreadShap snap) throws IOException {
            for(StackTraceElement ste: snap.elements) {
                intern(ste);
            }
            int threadNameRef = -1;
            if (snap.threadName != null) {
                threadNameRef = internDyn(snap.threadName);
            }
            dos.writeByte(TAG_TRACE);
            dos.writeLong(threadId);
            dos.writeLong(timestamp);
            writeVarInt(dos, trace.length);
            for(StackTraceElement ste: trace) {
                writeVarInt(dos, intern(ste));
            }
        }

        private int intern(StackTraceElement ste) throws IOException {
            if (!frameDic.containsKey(ste)) {
                String pkg = ste.getClassName();
                int c = pkg.lastIndexOf('.');
                String cn = c < 0 ? pkg : pkg.substring(c + 1);
                pkg = c < 0 ? "" : pkg.substring(0, c);
                String mtd = ste.getMethodName();
                String file = ste.getFileName();
                int line = ste.getLineNumber() + 2;
                if (line < 0) {
                    line = 0;
                }
                int npkg = intern(pkg);
                int ncn = intern(cn);
                int nmtd = intern(mtd);
                int nfile = intern(file);
                dos.writeByte(TAG_FRAME);
                writeVarInt(dos, npkg);
                writeVarInt(dos, ncn);
                writeVarInt(dos, nmtd);
                writeVarInt(dos, nfile);
                writeVarInt(dos, line);
                int n = frameDic.size() + 1;
                frameDic.put(ste, n);
            }
            return frameDic.get(ste);            
        }

        private int intern(String str) throws IOException {
            if (str == null) {
                return 0;
            }
            if (!stringDic.containsKey(str)) {
                dos.write(TAG_STRING);
                dos.writeUTF(str);
                int n = stringDic.size() + 1;
                stringDic.put(str, n);
            }
            return stringDic.get(str);            
        }        
        
        public void close() {
            try {
                dos.close();
            } catch (IOException e) {
                // ignore
            }
            stringDic.clear();
            frameDic.clear();
        }
    }    
    
    static class StackTraceReaderV1 implements StackTraceReader {
        
        private DataInputStream dis;
        private List<String> stringDic = new ArrayList<String>();
        private List<StackTraceElement> frameDic = new ArrayList<StackTraceElement>();
        
        private boolean loaded;
        private long threadId;
        private long timestamp;
        private StackTraceElement[] trace;
        
        public StackTraceReaderV1(InputStream is) {
            this.dis = new DataInputStream(new InflaterInputStream(is));
            stringDic.add(null);
            frameDic.add(null);
            loaded = false;;
        }
        
        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#isLoaded()
         */
        @Override
        public boolean isLoaded() {
            return loaded;
        }
        
        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#getThreadId()
         */
        @Override
        public long getThreadId() {
            if (!isLoaded()) {
                throw new NoSuchElementException();
            }
            return threadId;
        }

        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#getTimestamp()
         */
        @Override
        public long getTimestamp() {
            if (!isLoaded()) {
                throw new NoSuchElementException();
            }
            return timestamp;
        }
        
        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#getTrace()
         */
        @Override
        public StackTraceElement[] getTrace() {
            if (!isLoaded()) {
                throw new NoSuchElementException();
            }
            return trace;            
        }
        
        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#loadNext()
         */
        @Override
        public boolean loadNext() throws IOException {
            loaded = false;
            while(true) {
                int tag = dis.read();
                if (tag < 0) {
                    dis.close();
                    break;
                }
                else if (tag == TAG_STRING) {
                    String str = dis.readUTF();
                    stringDic.add(str);                    
                }
                else if (tag == TAG_FRAME) {
                    StackTraceElement ste = readStackTraceElement();
                    frameDic.add(ste);
                }
                else if (tag == TAG_TRACE) {
                    threadId = dis.readLong();
                    timestamp = dis.readLong();
                    int len = readVarInt(dis);
                    trace = new StackTraceElement[len];
                    for(int i = 0; i != len; ++i) {
                        int ref = readVarInt(dis);
                        trace[i] = frameDic.get(ref);
                    }
                    loaded = true;
                    break;
                }
                else {
                    throw new IOException("Data format error");
                }
            }
            return loaded;
        }

        private StackTraceElement readStackTraceElement() throws IOException {
            int npkg = readVarInt(dis);
            int ncn = readVarInt(dis);
            int nmtd = readVarInt(dis);
            int nfile = readVarInt(dis);
            int line = readVarInt(dis) - 2;
            String cn = stringDic.get(npkg);
            if (cn.length() > 0) {
                cn += ".";
            }
            cn += stringDic.get(ncn);
            String mtd = stringDic.get(nmtd);
            String file = stringDic.get(nfile);
            StackTraceElement e = new StackTraceElement(cn, mtd, file, line);
            return e;
        }
    }

    static class StackTraceReaderV2 implements StackTraceReader {

        private DataInputStream dis;
        private List<String> stringDic = new ArrayList<String>();
        private List<StackTraceElement> frameDic = new ArrayList<StackTraceElement>();

        private boolean loaded;
        private long threadId;
        private long timestamp;
        private StackTraceElement[] trace;

        public StackTraceReaderV2(InputStream is) {
            this.dis = new DataInputStream(new InflaterInputStream(is));
            stringDic.add(null);
            frameDic.add(null);
            loaded = false;;
        }

        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#isLoaded()
         */
        @Override
        public boolean isLoaded() {
            return loaded;
        }

        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#getThreadId()
         */
        @Override
        public long getThreadId() {
            if (!isLoaded()) {
                throw new NoSuchElementException();
            }
            return threadId;
        }

        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#getTimestamp()
         */
        @Override
        public long getTimestamp() {
            if (!isLoaded()) {
                throw new NoSuchElementException();
            }
            return timestamp;
        }

        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#getTrace()
         */
        @Override
        public StackTraceElement[] getTrace() {
            if (!isLoaded()) {
                throw new NoSuchElementException();
            }
            return trace;
        }

        /* (non-Javadoc)
         * @see org.gridkit.jvmtool.StackTraceReader#loadNext()
         */
        @Override
        public boolean loadNext() throws IOException {
            loaded = false;
            while(true) {
                int tag = dis.read();
                if (tag < 0) {
                    dis.close();
                    break;
                }
                else if (tag == TAG_STRING) {
                    String str = dis.readUTF();
                    stringDic.add(str);
                }
                else if (tag == TAG_FRAME) {
                    StackTraceElement ste = readStackTraceElement();
                    frameDic.add(ste);
                }
                else if (tag == TAG_TRACE) {
                    threadId = dis.readLong();
                    timestamp = dis.readLong();
                    int len = readVarInt(dis);
                    trace = new StackTraceElement[len];
                    for(int i = 0; i != len; ++i) {
                        int ref = readVarInt(dis);
                        trace[i] = frameDic.get(ref);
                    }
                    loaded = true;
                    break;
                }
                else {
                    throw new IOException("Data format error");
                }
            }
            return loaded;
        }

        private StackTraceElement readStackTraceElement() throws IOException {
            int npkg = readVarInt(dis);
            int ncn = readVarInt(dis);
            int nmtd = readVarInt(dis);
            int nfile = readVarInt(dis);
            int line = readVarInt(dis) - 2;
            String cn = stringDic.get(npkg);
            if (cn.length() > 0) {
                cn += ".";
            }
            cn += stringDic.get(ncn);
            String mtd = stringDic.get(nmtd);
            String file = stringDic.get(nfile);
            StackTraceElement e = new StackTraceElement(cn, mtd, file, line);
            return e;
        }
    }

    static int readVarInt(DataInputStream dis) throws IOException {
        int b = dis.readByte();
        if ((b & 0x80) == 0) {
            return 0x7F & b;
        }
        else {
            int v = (0x7F & b);
            b = dis.readByte();
            v |= ((0x7F & b) << 7);
            if ((b & 0x80) == 0) {
                return v;
            }
            b = dis.readByte();
            v |= ((0x7F & b) << 14);
            if ((b & 0x80) == 0) {
                return v;
            }
            b = dis.readByte();
            v |= ((0xFF & b) << 21);
            return v;
        }
    }
    
    static void writeVarInt(DataOutputStream dos, int v) throws IOException {
        int val = v;
        if ((val & 0xFFFFFF80) == 0) {
            dos.write(val);
            return;
        }
        dos.write(0x80 | (0x7F & val));
        val >>= 7;
        if ((val & 0xFFFFFF80) == 0) {
            dos.write(val);
            return;
        }
        dos.write(0x80 | (0x7F & val));
        val >>= 7;
        if ((val & 0xFFFFFF80) == 0) {
            dos.write(val);
            return;
        }
        dos.write(0x80 | (0x7F & val));
        val >>= 7;
        if ((val & 0xFFFFFF00) == 0) {
            dos.write(val);
            return;
        }
        else {
            throw new IllegalArgumentException("Out of bounds");
        }
    }
}
