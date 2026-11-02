package com.atakmap.android.tfr.plugin.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Real FAA files captured Nov 2, 2026 (test resources). */
public final class Fixtures {

    private Fixtures() {
    }

    public static final String SET = "/fixtures/2026-11-02/";

    public static byte[] read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("missing fixture " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] list() {
        return read(SET + "list.json");
    }

    public static byte[] detail(String id) {
        return read(SET + "xml/detail_" + id.replace('/', '_') + ".xml");
    }

    public static Tfr tfr(String id) {
        try {
            return TfrXmlParser.parse(detail(id), id);
        } catch (TfrXmlParser.ParseException e) {
            throw new AssertionError(id + ": " + e.getMessage());
        }
    }

    /** "2026-11-02T14:15" UTC to ms. */
    public static long utc(String s) {
        long t = TfrXmlParser.time(s.length() == 16 ? s + ":00" : s);
        if (t < 0)
            throw new IllegalArgumentException(s);
        return t;
    }
}
