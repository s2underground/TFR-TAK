package com.atakmap.android.tfr.plugin.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the FAA list of active TFRs ({@code tfr.faa.gov/tfrapi/exportTfrList}):
 * a JSON array of objects with notam_id, type, facility, state, description,
 * creation_date. Strict about structure: a response that isn't such an
 * array is rejected as a whole, so a broken or substituted page can never
 * look like "no TFRs".
 */
public final class TfrListParser {

    private TfrListParser() {
    }

    public static final int MAX_BYTES = 8 * 1024 * 1024;

    /** One row of the list. */
    public static final class Entry {
        public final String id, type, facility, state, description, created;

        public Entry(String id, String type, String facility, String state,
                String description, String created) {
            this.id = id;
            this.type = type;
            this.facility = facility;
            this.state = state;
            this.description = description;
            this.created = created;
        }

        /** Everything that, if changed, means the detail should be re-read. */
        public String signature() {
            return type + "|" + facility + "|" + state + "|" + description
                    + "|" + created;
        }
    }

    public static final class Result {
        public final List<Entry> entries;
        /** Rows that couldn't be read (skipped). */
        public final int rejected;

        Result(List<Entry> entries, int rejected) {
            this.entries = Collections.unmodifiableList(entries);
            this.rejected = rejected;
        }
    }

    public static final class FeedException extends Exception {
        public FeedException(String msg) {
            super(msg);
        }
    }

    public static Result parse(byte[] json) throws FeedException {
        if (json == null || json.length == 0)
            throw new FeedException("empty response");
        if (json.length > MAX_BYTES)
            throw new FeedException("response too large");
        String s = new String(json, StandardCharsets.UTF_8).trim();
        if (!s.startsWith("["))
            throw new FeedException("not a TFR list");
        JSONArray arr;
        try {
            arr = new JSONArray(s);
        } catch (JSONException e) {
            throw new FeedException("malformed TFR list");
        }
        Map<String, Entry> byId = new LinkedHashMap<>();
        int rejected = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                rejected++;
                continue;
            }
            String id = o.optString("notam_id", "").trim();
            if (!NotamId.isValid(id)) {
                rejected++;
                continue;
            }
            if (byId.containsKey(id))
                continue; // duplicate row
            byId.put(id, new Entry(id, str(o, "type"), str(o, "facility"),
                    str(o, "state"), str(o, "description"),
                    str(o, "creation_date")));
        }
        if (arr.length() > 0 && rejected * 2 > arr.length())
            throw new FeedException("most TFR list rows unreadable");
        return new Result(new ArrayList<>(byId.values()), rejected);
    }

    private static String str(JSONObject o, String k) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL)
            return "";
        String s = String.valueOf(v).trim();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
