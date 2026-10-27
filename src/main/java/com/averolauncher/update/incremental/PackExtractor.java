package com.averolauncher.update.incremental;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.function.LongConsumer;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Turns one blob from {@code payload.pack} back into a verified file.
 *
 * <p>Defences against hostile/corrupt packs: the blob is read through a hard limit of exactly the signed
 * {@code packedSize}; inflating stops (and fails) the moment it would exceed the signed {@code size};
 * the result is only moved into place after its SHA-256 equals the signed hash.
 */
final class PackExtractor {

    static final class IntegrityException extends IOException {
        IntegrityException(String m) { super(m); }
    }

    private PackExtractor() {}

    /** Reads exactly {@code e.packedSize} bytes from {@code packed} and writes the verified file to {@code dest}. */
    static void extract(InputStream packed, UpdateManifest.FileEntry e, Path dest, CancelToken token,
                        LongConsumer onPackedBytes) throws IOException {
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        Counting bounded = new Counting(packed, e.packedSize, token, onPackedBytes);
        MessageDigest md = Hashes.sha256();
        long written = 0;
        try {
            InputStream src = e.method == 1 ? new InflaterInputStream(bounded, new Inflater(true), 32 * 1024) : bounded;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = src.read(buf)) != -1) {
                    written += n;
                    if (written > e.size) throw new IntegrityException("File is larger than the manifest allows: " + e.path);
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            } catch (java.util.zip.ZipException z) {
                throw new IntegrityException("Corrupt compressed data for " + e.path);
            } catch (java.io.EOFException z) {
                throw new IOException("Download ended early for " + e.path);
            }
            bounded.drain();   // keep a shared stream aligned for the next entry
            if (written != e.size) throw new IntegrityException("File is shorter than the manifest says: " + e.path);
            if (!Hashes.hex(md.digest()).equals(e.sha256)) throw new IntegrityException("Hash mismatch for " + e.path);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw ex;
        }
    }

    /** Skips {@code n} bytes of {@code in} (used when streaming a whole pack and an entry isn't needed). */
    static void skipFully(InputStream in, long n, CancelToken token, LongConsumer onBytes) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (n > 0) {
            token.check();
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("Download ended early");
            token.touch();
            n -= r;
            if (onBytes != null) onBytes.accept(r);
        }
    }

    /** Reads at most {@code limit} bytes of the underlying stream and reports them. */
    private static final class Counting extends FilterInputStream {
        private long remaining;
        private final CancelToken token;
        private final LongConsumer cb;

        Counting(InputStream in, long limit, CancelToken token, LongConsumer cb) {
            super(in); this.remaining = limit; this.token = token; this.cb = cb;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            token.check();
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) throw new java.io.EOFException();
            remaining -= n;
            token.touch();
            if (cb != null) cb.accept(n);
            return n;
        }

        void drain() throws IOException {
            if (remaining > 0) skipFully(in, remaining, token, cb);
            remaining = 0;
        }
    }
}
