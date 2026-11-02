package com.atakmap.android.tfr.plugin.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The client against a tiny HTTP server on plain sockets (Android unit tests
 * have no com.sun.net.httpserver).
 */
public class HttpFaaClientTest {

    ServerSocket server;
    Thread thread;
    volatile String status = "200 OK";
    volatile byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
    volatile String extraHeaders = "";
    volatile String declaredLength = null;
    final List<String> requests = new ArrayList<>();

    @Before
    public void start() throws IOException {
        server = new ServerSocket(0);
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(
                                s.getInputStream(), StandardCharsets.ISO_8859_1));
                        String line = in.readLine();
                        StringBuilder req = new StringBuilder(line);
                        while ((line = in.readLine()) != null && !line.isEmpty())
                            req.append('\n').append(line);
                        synchronized (requests) {
                            requests.add(req.toString());
                        }
                        OutputStream out = s.getOutputStream();
                        String len = declaredLength != null ? declaredLength
                                : String.valueOf(body.length);
                        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: " + len
                                + "\r\nConnection: close\r\n" + extraHeaders + "\r\n")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        out.write(body);
                        out.flush();
                    } catch (IOException e) {
                        // closed
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    HttpFaaClient client() {
        return new HttpFaaClient("0.1", "http://127.0.0.1:" + server.getLocalPort());
    }

    @Test
    public void fetchesListAndDetailWithFixedPaths() throws Exception {
        body = "[{\"notam_id\":\"6/4199\"}]".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(body, client().fetchList());
        client().fetchDetail("6/4199");
        synchronized (requests) {
            assertTrue(requests.get(0).startsWith("GET /tfrapi/exportTfrList "));
            assertTrue(requests.get(0).contains("User-Agent: TFR-TAK/0.1"));
            assertTrue(requests.get(1).startsWith("GET /download/detail_6_4199.xml "));
        }
    }

    @Test
    public void invalidIdNeverReachesTheNetwork() {
        try {
            client().fetchDetail("6/4199/../../evil");
            fail();
        } catch (IOException expected) {
        }
        synchronized (requests) {
            assertTrue(requests.isEmpty());
        }
    }

    @Test
    public void redirectsRefused() throws Exception {
        status = "302 Found";
        extraHeaders = "Location: https://example.com/\r\n";
        try {
            client().fetchList();
            fail();
        } catch (FaaClient.HttpStatusException e) {
            assertEquals(302, e.status);
        }
    }

    @Test
    public void errorStatusReported() throws Exception {
        status = "503 Service Unavailable";
        try {
            client().fetchList();
            fail();
        } catch (FaaClient.HttpStatusException e) {
            assertEquals(503, e.status);
        }
    }

    @Test
    public void oversizedResponseRefused() throws Exception {
        declaredLength = String.valueOf(HttpFaaClient.MAX_DETAIL_BYTES + 1);
        try {
            client().fetchDetail("6/4199");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("too large"));
        }
    }

    @Test
    public void allowlist() throws Exception {
        String faa = "https://tfr.faa.gov";
        assertTrue(HttpFaaClient.allowed(new URL("https://tfr.faa.gov/tfrapi/exportTfrList"), faa));
        assertTrue(HttpFaaClient.allowed(new URL("https://TFR.FAA.GOV/x"), faa));
        assertFalse(HttpFaaClient.allowed(new URL("http://tfr.faa.gov/x"), faa));
        assertFalse(HttpFaaClient.allowed(new URL("https://tfr.faa.gov.evil.com/x"), faa));
        assertFalse(HttpFaaClient.allowed(new URL("https://evil.com/tfr.faa.gov"), faa));
        assertFalse(HttpFaaClient.allowed(new URL("https://tfr.faa.gov:8443/x"), faa));
        assertFalse(HttpFaaClient.allowed(null, faa));
    }
}
