package org.bxteam.divinemc.diagnostics;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal streaming reader for the binary HPROF format ("JAVA PROFILE 1.0.1/1.0.2"),
 * the same format produced by {@link HeapDump}. A single pass extracts everything the
 * plugin attribution needs without ever holding the whole heap in memory:
 *
 * <ul>
 *   <li>class id → name (from {@code LOAD CLASS} + string table),</li>
 *   <li>class id → defining classloader id and instance size (from {@code CLASS DUMP}),</li>
 *   <li>per-class live instance count and shallow bytes (from {@code INSTANCE/ARRAY DUMP}),</li>
 *   <li>classloader instance id → classloader type name (best effort, for leak labelling).</li>
 * </ul>
 *
 * Object-graph retained sizes (dominator trees) are intentionally not computed — that
 * needs an out-of-process tool on large heaps. Shallow-bytes-per-classloader plus the
 * histogram growth delta is the practical leak signal this feeds.
 */
public final class HprofParser {
    private HprofParser() {
    }

    /** Per-class record assembled across LOAD CLASS and CLASS DUMP. */
    public static final class ClassRec {
        public String name = "?";
        public long loaderId;          // 0 == bootstrap
        public int instanceSize;
        public long instances;
        public long shallowBytes;
    }

    public static final class Result {
        /** class object id → record. */
        public final Map<Long, ClassRec> classById = new HashMap<>(4096);
        /** class name → list of class object ids (multiple ⇒ loaded by multiple loaders). */
        public final Map<String, List<Long>> classIdsByName = new HashMap<>(4096);
        public long totalInstances;
        public long totalShallowBytes;
        public int classCount;
        public int classLoaderCount;       // distinct loader ids that defined ≥1 class
    }

    // Top-level record tags.
    private static final int T_STRING = 0x01;
    private static final int T_LOAD_CLASS = 0x02;
    private static final int T_HEAP_DUMP = 0x0C;
    private static final int T_HEAP_DUMP_SEGMENT = 0x1C;

    // Heap-dump sub-record tags.
    private static final int H_ROOT_UNKNOWN = 0xFF;
    private static final int H_ROOT_JNI_GLOBAL = 0x01;
    private static final int H_ROOT_JNI_LOCAL = 0x02;
    private static final int H_ROOT_JAVA_FRAME = 0x03;
    private static final int H_ROOT_NATIVE_STACK = 0x04;
    private static final int H_ROOT_STICKY_CLASS = 0x05;
    private static final int H_ROOT_THREAD_BLOCK = 0x06;
    private static final int H_ROOT_MONITOR_USED = 0x07;
    private static final int H_ROOT_THREAD_OBJECT = 0x08;
    private static final int H_CLASS_DUMP = 0x20;
    private static final int H_INSTANCE_DUMP = 0x21;
    private static final int H_OBJECT_ARRAY_DUMP = 0x22;
    private static final int H_PRIMITIVE_ARRAY_DUMP = 0x23;
    private static final int H_HEAP_DUMP_INFO = 0xFE;   // HotSpot extension

    public static Result parse(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            readHeaderVersion(in);
            final int idSize = in.readInt();
            in.readLong(); // dump timestamp (ms) — unused

            final Result r = new Result();
            // string id → class name candidate; LOAD CLASS resolves class id → name string id.
            final Map<Long, String> strings = new HashMap<>(8192);
            final Map<Long, Long> classNameStringByClassId = new HashMap<>(4096);

            while (true) {
                final int tag;
                try {
                    tag = in.readUnsignedByte();
                } catch (EOFException eof) {
                    break;
                }
                in.readInt(); // record timestamp delta — unused
                final long length = in.readInt() & 0xFFFFFFFFL;

                switch (tag) {
                    case T_STRING -> {
                        final long id = readId(in, idSize);
                        final byte[] bytes = new byte[(int) (length - idSize)];
                        in.readFully(bytes);
                        strings.put(id, new String(bytes, StandardCharsets.UTF_8));
                    }
                    case T_LOAD_CLASS -> {
                        in.readInt();                       // class serial
                        final long classId = readId(in, idSize);
                        in.readInt();                       // stack trace serial
                        final long nameStringId = readId(in, idSize);
                        classNameStringByClassId.put(classId, nameStringId);
                        r.classCount++;
                    }
                    case T_HEAP_DUMP, T_HEAP_DUMP_SEGMENT -> parseHeapSegment(in, idSize, length, r);
                    default -> in.skipNBytes(length);
                }
            }

            // Resolve class names now that the full string table is known.
            for (Map.Entry<Long, Long> e : classNameStringByClassId.entrySet()) {
                final ClassRec rec = r.classById.computeIfAbsent(e.getKey(), k -> new ClassRec());
                rec.name = normalize(strings.getOrDefault(e.getValue(), "?"));
                r.classIdsByName.computeIfAbsent(rec.name, k -> new ArrayList<>(1)).add(e.getKey());
            }

            final java.util.Set<Long> loaders = new java.util.HashSet<>();
            for (ClassRec rec : r.classById.values()) {
                if (rec.loaderId != 0) {
                    loaders.add(rec.loaderId);
                }
            }
            r.classLoaderCount = loaders.size();
            return r;
        }
    }

    private static void parseHeapSegment(DataInputStream in, int idSize, long length, Result r) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            final int sub = in.readUnsignedByte();
            remaining -= 1;
            switch (sub) {
                case H_ROOT_UNKNOWN, H_ROOT_STICKY_CLASS, H_ROOT_MONITOR_USED -> remaining -= skip(in, idSize);
                case H_ROOT_JNI_GLOBAL -> remaining -= skip(in, 2L * idSize);
                case H_ROOT_JNI_LOCAL, H_ROOT_JAVA_FRAME, H_ROOT_THREAD_OBJECT -> remaining -= skip(in, idSize + 8L);
                case H_ROOT_NATIVE_STACK, H_ROOT_THREAD_BLOCK -> remaining -= skip(in, idSize + 4L);
                case H_HEAP_DUMP_INFO -> remaining -= skip(in, 4L + idSize);
                case H_CLASS_DUMP -> remaining -= readClassDump(in, idSize, r);
                case H_INSTANCE_DUMP -> remaining -= readInstanceDump(in, idSize, r);
                case H_OBJECT_ARRAY_DUMP -> remaining -= readObjectArray(in, idSize, r);
                case H_PRIMITIVE_ARRAY_DUMP -> remaining -= readPrimitiveArray(in, idSize, r);
                default -> throw new IOException("unknown heap sub-record 0x" + Integer.toHexString(sub));
            }
        }
    }

    /** @return bytes consumed after the sub-tag. */
    private static long readClassDump(DataInputStream in, int idSize, Result r) throws IOException {
        long c = 0;
        final long classId = readId(in, idSize); c += idSize;
        c += skip(in, 4);                       // stack trace serial
        c += skip(in, idSize);                  // super class id
        final long loaderId = readId(in, idSize); c += idSize;
        c += skip(in, idSize);                  // signers
        c += skip(in, idSize);                  // protection domain
        c += skip(in, 2L * idSize);             // reserved 1, 2
        final int instanceSize = in.readInt(); c += 4;

        final ClassRec rec = r.classById.computeIfAbsent(classId, k -> new ClassRec());
        rec.loaderId = loaderId;
        rec.instanceSize = instanceSize;

        final int cpCount = in.readUnsignedShort(); c += 2;
        for (int i = 0; i < cpCount; i++) {
            c += skip(in, 2);                   // cp index
            final int type = in.readUnsignedByte(); c += 1;
            c += skip(in, typeSize(type, idSize));
        }
        final int staticCount = in.readUnsignedShort(); c += 2;
        for (int i = 0; i < staticCount; i++) {
            c += skip(in, idSize);              // name string id
            final int type = in.readUnsignedByte(); c += 1;
            c += skip(in, typeSize(type, idSize));
        }
        final int instFieldCount = in.readUnsignedShort(); c += 2;
        for (int i = 0; i < instFieldCount; i++) {
            c += skip(in, idSize);              // name string id
            c += skip(in, 1);                   // type
        }
        return c;
    }

    private static long readInstanceDump(DataInputStream in, int idSize, Result r) throws IOException {
        long c = 0;
        c += skip(in, idSize);                  // object id
        c += skip(in, 4);                       // stack trace serial
        final long classId = readId(in, idSize); c += idSize;
        final int numBytes = in.readInt(); c += 4;
        c += skip(in, numBytes);

        final ClassRec rec = r.classById.computeIfAbsent(classId, k -> new ClassRec());
        rec.instances++;
        rec.shallowBytes += numBytes;
        r.totalInstances++;
        r.totalShallowBytes += numBytes;
        return c;
    }

    private static long readObjectArray(DataInputStream in, int idSize, Result r) throws IOException {
        long c = 0;
        c += skip(in, idSize);                  // array object id
        c += skip(in, 4);                       // stack trace serial
        final int numElems = in.readInt(); c += 4;
        final long classId = readId(in, idSize); c += idSize;
        final long bytes = (long) numElems * idSize;
        c += skip(in, bytes);

        final ClassRec rec = r.classById.computeIfAbsent(classId, k -> new ClassRec());
        rec.instances++;
        rec.shallowBytes += bytes;
        r.totalInstances++;
        r.totalShallowBytes += bytes;
        return c;
    }

    private static long readPrimitiveArray(DataInputStream in, int idSize, Result r) throws IOException {
        long c = 0;
        c += skip(in, idSize);                  // array object id
        c += skip(in, 4);                       // stack trace serial
        final int numElems = in.readInt(); c += 4;
        final int type = in.readUnsignedByte(); c += 1;
        final long bytes = (long) numElems * elementSize(type);
        c += skip(in, bytes);
        r.totalInstances++;
        r.totalShallowBytes += bytes;
        return c;
    }

    // --- helpers ---

    private static void readHeaderVersion(DataInputStream in) throws IOException {
        // Null-terminated format string, e.g. "JAVA PROFILE 1.0.2".
        final StringBuilder sb = new StringBuilder(20);
        int b;
        while ((b = in.read()) > 0) {
            sb.append((char) b);
        }
        if (b == -1 || !sb.toString().startsWith("JAVA PROFILE")) {
            throw new IOException("not an HPROF dump: '" + sb + "'");
        }
    }

    private static long readId(DataInputStream in, int idSize) throws IOException {
        return idSize == 8 ? in.readLong() : (in.readInt() & 0xFFFFFFFFL);
    }

    /** Skips n bytes and returns n (for byte accounting). */
    private static long skip(DataInputStream in, long n) throws IOException {
        in.skipNBytes(n);
        return n;
    }

    private static long typeSize(int type, int idSize) {
        return type == 2 ? idSize : elementSize(type);
    }

    private static int elementSize(int type) {
        return switch (type) {
            case 4, 8 -> 1;   // boolean, byte
            case 5, 9 -> 2;   // char, short
            case 6, 10 -> 4;  // float, int
            case 7, 11 -> 8;  // double, long
            case 2 -> 8;      // object (overridden by typeSize with idSize)
            default -> 1;
        };
    }

    /** HPROF stores names with '/' or '.'; normalize to dotted Java form. */
    private static String normalize(String name) {
        return name.replace('/', '.');
    }
}
