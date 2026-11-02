package com.atakmap.android.tfr.plugin.net;

import java.io.IOException;

/** Reads the two FAA resources the plugin needs. */
public interface FaaClient {

    /** The JSON list of active TFRs. */
    byte[] fetchList() throws IOException;

    /** The detail XML of one TFR (id already validated). */
    byte[] fetchDetail(String notamId) throws IOException;

    /** An HTTP answer other than 200. */
    final class HttpStatusException extends IOException {
        public final int status;

        public HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}
