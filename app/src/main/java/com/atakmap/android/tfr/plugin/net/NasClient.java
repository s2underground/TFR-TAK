package com.atakmap.android.tfr.plugin.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * HTTPS client for the FAA's national airspace status: NAS Status
 * (nasstatus.faa.gov) and the Command Center advisories database
 * (www.fly.faa.gov). Same hardening as {@link HttpFaaClient}: URLs from
 * fixed parts and integers only, host allowlist before connecting and on
 * the response, no redirects, size caps, HTTP 200 only.
 */
public final class NasClient {

    public static final String NAS_HOST = "nasstatus.faa.gov";
    public static final String ADV_HOST = "www.fly.faa.gov";
    static final String AIRPORT_EVENTS = "https://" + NAS_HOST
            + "/api/airport-events";
    static final String ADV_LIST = "https://" + ADV_HOST
            + "/adv/adv_list?whichAdvisories=ATCSCC&advisoryCategory=All"
            + "&airflow=true&ctop=true&gStop=true&gDelay=true&route=true"
            + "&other=true&date=";
    static final String ADV_TEXT = "https://" + ADV_HOST
            + "/adv/adv_otherdis?adv_date=";

    private final String userAgent;

    public NasClient(String pluginVersion) {
        this.userAgent = "TFR-TAK/" + pluginVersion
                + " (ATAK plugin; s2underground@protonmail.com)";
    }

    static boolean allowed(URL u) {
        if (u == null || !"https".equals(u.getProtocol()))
            return false;
        if (u.getPort() != -1 && u.getPort() != 443)
            return false;
        String h = u.getHost() == null ? ""
                : u.getHost().toLowerCase(Locale.US);
        return NAS_HOST.equals(h) || ADV_HOST.equals(h);
    }

    public byte[] airportEvents() throws IOException {
        return get(new URL(AIRPORT_EVENTS), 4 * 1024 * 1024);
    }

    /** The advisories list for a UTC date (yyyyMMdd). */
    public byte[] advisoryList(int date) throws IOException {
        if (date < 20000101 || date > 21001231)
            throw new IOException("bad date");
        String d = String.format(Locale.US, "%04d-%02d-%02d", date / 10000,
                date / 100 % 100, date % 100);
        return get(new URL(ADV_LIST + d), 2 * 1024 * 1024);
    }

    /** One advisory page: UTC date (yyyyMMdd) and number. */
    public byte[] advisoryText(int date, int number) throws IOException {
        if (date < 20000101 || date > 21001231 || number < 1 || number > 9999)
            throw new IOException("bad advisory");
        String d = String.format(Locale.US, "%02d%02d%04d", date / 100 % 100,
                date % 100, date / 10000);
        return get(new URL(ADV_TEXT + d + "&advn=" + number), 512 * 1024);
    }

    private byte[] get(URL url, int maxBytes) throws IOException {
        if (!allowed(url))
            throw new IOException("blocked: not an FAA address");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(HttpFaaClient.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(HttpFaaClient.READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", userAgent);
            int code = c.getResponseCode();
            if (!allowed(c.getURL()))
                throw new IOException("blocked: response from another host");
            if (code != 200)
                throw new FaaClient.HttpStatusException(code);
            long len = c.getContentLength();
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
