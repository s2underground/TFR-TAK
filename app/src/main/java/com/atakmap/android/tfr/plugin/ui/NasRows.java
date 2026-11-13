package com.atakmap.android.tfr.plugin.ui;

import com.atakmap.android.tfr.plugin.core.Geo;
import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.core.nas.AirportEvent;
import com.atakmap.android.tfr.plugin.core.nas.National;
import com.atakmap.android.tfr.plugin.engine.NasSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** What the Airspace tab shows, as plain data. Pure (tested). */
public final class NasRows {

    private NasRows() {
    }

    public static final class AirportRow {
        public final AirportEvent event;
        /** Metres from you; NaN if unknown. */
        public final double distanceM;

        AirportRow(AirportEvent e, double d) {
            this.event = e;
            this.distanceM = d;
        }
    }

    /**
     * Airport events, nearest first when your position is known (then the
     * most serious type), else by seriousness and airport.
     */
    public static List<AirportRow> airports(NasSnapshot s, final LatLon self) {
        List<AirportRow> out = new ArrayList<>();
        if (s == null)
            return out;
        for (AirportEvent e : s.airports) {
            double d = Double.NaN;
            if (self != null && !Double.isNaN(e.lat))
                d = Geo.distance(self, new LatLon(e.lat, e.lon));
            out.add(new AirportRow(e, d));
        }
        Collections.sort(out, new Comparator<AirportRow>() {
            @Override
            public int compare(AirportRow a, AirportRow b) {
                boolean an = Double.isNaN(a.distanceM),
                        bn = Double.isNaN(b.distanceM);
                if (!an && !bn && a.distanceM != b.distanceM)
                    return Double.compare(a.distanceM, b.distanceM);
                if (an != bn)
                    return an ? 1 : -1;
                if (a.event.type.rank != b.event.type.rank)
                    return a.event.type.rank - b.event.type.rank;
                int c = a.event.airport.compareTo(b.event.airport);
                return c != 0 ? c : a.event.type.compareTo(b.event.type);
            }
        });
        return out;
    }

    /** Title of a national event: "NATIONWIDE GROUND STOP". */
    public static String title(National.Event e) {
        return e.advisory.title.toUpperCase(Locale.US);
    }

    /** "IN EFFECT" / "CANCELLED 2330Z". */
    public static String state(National.Event e, Format f) {
        return e.cancelled ? "CANCELLED " + f.zulu(e.advisory.sentMs)
                : "IN EFFECT";
    }

    /** "Since 2053Z · planned to 0400Z" (+ " (not confirmed)" if no text). */
    public static String window(National.Event e, Format f) {
        if (e.cancelled)
            return "Cancelled by ATCSCC ADVZY " + e.advisory.number;
        long start = e.text != null && e.text.eventStartMs > 0
                ? e.text.eventStartMs
                : e.advisory.sentMs;
        StringBuilder b = new StringBuilder();
        if (start > 0)
            b.append("Since ").append(f.zulu(start));
        if (e.text != null && e.text.eventEndMs > 0)
            b.append(b.length() > 0 ? " · " : "").append("planned to ")
                    .append(f.zulu(e.text.eventEndMs))
                    .append(" (may be extended)");
        else
            b.append(b.length() > 0 ? " · " : "")
                    .append("end not published yet");
        return b.toString();
    }

    /** Footer: "ATCSCC ADVZY 120 · DCC · sent 2246Z (2 earlier)". */
    public static String source(National.Event e, Format f) {
        return "ATCSCC ADVZY " + e.advisory.number + " · "
                + e.advisory.facility + " · sent "
                + (e.advisory.sentMs > 0 ? f.zulu(e.advisory.sentMs) : "?")
                + (e.earlier > 0 ? " · " + e.earlier + " earlier" : "");
    }

    /** Body text of the advisory, or a note that it is still loading. */
    public static String body(National.Event e) {
        if (e.text == null)
            return "Full text not received yet (the FAA advisories server "
                    + "often answers empty; TFR-TAK keeps trying).";
        return e.text.body;
    }

    /** "1 h 05–1 h 15, increasing" etc. plus reason in plain words. */
    public static String reason(AirportEvent e) {
        String r = e.reason.trim();
        if (r.isEmpty())
            return e.type == AirportEvent.Type.CLOSED ? "See NOTAM" : "";
        String u = r.toUpperCase(Locale.US);
        if (u.contains("NAS GS") || u.contains("NATIONAL STOP"))
            return r + " (national ground stop)";
        if (u.startsWith("EQ") || u.contains(":EQ"))
            return r + " (equipment)";
        return r;
    }

    /** Detail text of an airport event. */
    public static String detail(AirportEvent e, double distanceM, Format f) {
        StringBuilder b = new StringBuilder();
        b.append(e.airport);
        if (!e.name.isEmpty())
            b.append(" · ").append(e.name);
        b.append('\n').append(e.type.label)
                .append(e.partial ? " (with exceptions: see NOTAM)" : "")
                .append('\n');
        if (!Double.isNaN(distanceM))
            b.append(Format.distance(distanceM)).append(" from you\n");
        b.append('\n').append(e.what).append('\n');
        String r = reason(e);
        if (!r.isEmpty())
            b.append("Reason: ").append(r).append('\n');
        if (e.startMs > 0)
            b.append(e.type == AirportEvent.Type.DEPARTURE_DELAY
                    || e.type == AirportEvent.Type.ARRIVAL_DELAY ? "Reported: "
                            : "From: ")
                    .append(f.when(e.startMs)).append('\n');
        if (e.endMs > 0)
            b.append("Until: ").append(f.when(e.endMs)).append('\n');
        if (!e.notam.isEmpty())
            b.append("\nNOTAM:\n").append(e.notam).append('\n');
        if (!e.advisoryUrl.isEmpty())
            b.append("\nAdvisory: ").append(e.advisoryUrl).append('\n');
        b.append("\nSource: FAA NAS Status (nasstatus.faa.gov).");
        return b.toString();
    }

    /** Status line for the Airspace tab. */
    public static String status(NasSnapshot s, Format f) {
        if (s == null)
            return "Checking FAA airspace status…";
        long ok = Math.min(s.airportsOkMs, s.advisoriesOkMs);
        if (ok <= 0)
            ok = Math.max(s.airportsOkMs, s.advisoriesOkMs);
        if (ok <= 0)
            return "No answer from the FAA yet";
        return (s.stale() ? "STALE · " : "") + "FAA status "
                + Format.ago(s.nowMs, ok);
    }

    /** Distance as shown on a row. */
    public static String distance(double m) {
        return Double.isNaN(m) ? "" : Format.distance(m);
    }

    static boolean near(double m, int nm) {
        return !Double.isNaN(m) && m <= nm * Geo.METERS_PER_NM;
    }
}
