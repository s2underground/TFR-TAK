package com.atakmap.android.tfr.plugin.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Crash-safe file writes: write a temp file, fsync, rename over the target,
 * keeping the previous version as {@code .bak}. An unreadable file is moved
 * aside ({@code .corrupt-<time>}) instead of being overwritten.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(File target, byte[] data) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("cannot create cache folder");
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
        if (target.exists()) {
            File bak = new File(dir, target.getName() + ".bak");
            if (bak.exists() && !bak.delete())
                throw new IOException("cannot replace backup");
            if (!target.renameTo(bak))
                throw new IOException("cannot back up cache file");
        }
        if (!tmp.renameTo(target))
            throw new IOException("cannot move cache file into place");
    }

    /** File contents, or null if it doesn't exist. */
    public static byte[] read(File f, int maxBytes) throws IOException {
        if (!f.isFile())
            return null;
        if (f.length() > maxBytes)
            throw new IOException("cache file too large");
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Move an unreadable file aside so it is kept for diagnosis. */
    public static void quarantine(File f, long now) {
        if (f.exists()) {
            File q = new File(f.getParentFile(), f.getName() + ".corrupt-" + now);
            if (!f.renameTo(q))
                delete(f);
        }
    }

    public static void delete(File f) {
        if (f.exists() && !f.delete())
            f.deleteOnExit();
    }
}
