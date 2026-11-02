package com.atakmap.android.tfr.plugin.net;

import com.atakmap.android.tfr.plugin.core.NotamId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * HTTPS client for tfr.faa.gov only. Every URL is built from fixed parts and
 * a validated NOTAM id, checked against the host allowlist before
 * connecting, and redirects are refused (a redirect could point anywhere).
 * Responses are size-capped and must be HTTP 200.
 */
public final class HttpFaaClient implements FaaClient {

    public static final String HOST = "tfr.faa.gov";
    private static final String BASE = "https://" + HOST;
    private static final String LIST_PATH = "/tfrapi/exportTfrList";
    private static final String DETAIL_PREFIX = "/download/detail_";

    static final int CONNECT_TIMEOUT_MS = 15000;
    static final int READ_TIMEOUT_MS = 30000;
    static final int MAX_LIST_BYTES = 8 * 1024 * 1024;
    static final int MAX_DETAIL_BYTES = 4 * 1024 * 1024;

    private final String userAgent;
    private final String base;

    public HttpFaaClient(String pluginVersion) {
        this(pluginVersion, BASE);
    }

    /** For tests: a different base (http://127.0.0.1:port). */
    HttpFaaClient(String pluginVersion, String base) {
        this.userAgent = "TFR-TAK/" + pluginVersion
                + " (ATAK plugin; s2underground@protonmail.com)";
        this.base = base;
    }

    /** Every connection must go to the FAA host (or loopback in tests). */
    static boolean allowed(URL u, String base) {
        if (u == null)
            return false;
        String host = u.getHost() == null ? ""
                : u.getHost().toLowerCase(Locale.US);
        if (base.equals(BASE))
            return "https".equals(u.getProtocol()) && HOST.equals(host)
                    && (u.getPort() == -1 || u.getPort() == 443);
        return "127.0.0.1".equals(host);
    }

    @Override
    public byte[] fetchList() throws IOException {
        return get(new URL(base + LIST_PATH), MAX_LIST_BYTES,
                "application/json");
    }

    @Override
    public byte[] fetchDetail(String notamId) throws IOException {
        if (!NotamId.isValid(notamId))
            throw new IOException("invalid NOTAM id");
        return get(new URL(base + DETAIL_PREFIX + NotamId.fileKey(notamId)
                + ".xml"), MAX_DETAIL_BYTES, "application/xml");
    }

    private byte[] get(URL url, int maxBytes, String accept)
            throws IOException {
        if (!allowed(url, base))
            throw new IOException("blocked: not an FAA address");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (!allowed(c.getURL(), base))
                throw new IOException("blocked: response from another host");
            if (code != 200)
                throw new HttpStatusException(code);
            long len = c.getContentLength(); // -1 if unknown or > 2 GB
            if (len > maxBytes)
                throw new IOException("response too large");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(
                        len > 0 ? (int) len : 65536);
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes)
                        throw new IOException("response too large");
                    out.write(buf, 0, n);
                }
                if (len > 0 && total != len)
                    throw new IOException("response truncated");
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
