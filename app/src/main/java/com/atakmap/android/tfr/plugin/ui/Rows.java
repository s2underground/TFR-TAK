package com.atakmap.android.tfr.plugin.ui;

import com.atakmap.android.tfr.plugin.core.Altitude;
import com.atakmap.android.tfr.plugin.core.Boundary;
import com.atakmap.android.tfr.plugin.core.Category;
import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.core.Schedule;
import com.atakmap.android.tfr.plugin.core.Tfr;
import com.atakmap.android.tfr.plugin.core.TfrArea;
import com.atakmap.android.tfr.plugin.engine.Snapshot;
import com.atakmap.android.tfr.plugin.engine.Tuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a snapshot into what the pane shows: a status line and sorted rows.
 * Pure Java (tested); the pane only renders.
 */
public final class Rows {

    public static final int ACTIVE_COLOR = 0xFFFF5252;
    public static final int PENDING_COLOR = 0xFFFFC107;
    public static final int DIM_COLOR = 0xFFAAAAAA;
    public static final int OK_COLOR = 0xFF69F0AE;

    /** What to list. */
    public static final class Filter {
        public final boolean securityOnly;
        public final boolean showExpired;

        public Filter(boolean securityOnly, boolean showExpired) {
            this.securityOnly = securityOnly;
            this.showExpired = showExpired;
        }
    }

    public static final class Row {
        public final String id;
        public final Category category;
        /** "VIP · Rome, NEW YORK" */
        public final String title;
        /** "SFC–4,999 ft AGL · FDC 6/4199" */
        public final String subtitle;
        /** "ACTIVE until Nov 2 1815Z (13:15 EST)" */
        public final String status;
        public final int statusColor;
        /** "12 NM", "inside", "" */
        public final String distance;
        public final boolean hasOutline;
        /** Notes in plain words (no graphic, possibly cancelled, errors). */
        public final List<String> notes;
        final int rank;
        final double distanceM;

        Row(String id, Category category, String title, String subtitle,
                String status, int statusColor, String distance,
                boolean hasOutline, List<String> notes, int rank,
                double distanceM) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.subtitle = subtitle;
            this.status = status;
            this.statusColor = statusColor;
            this.distance = distance;
            this.hasOutline = hasOutline;
            this.notes = Collections.unmodifiableList(notes);
            this.rank = rank;
            this.distanceM = distanceM;
        }
    }

    private Rows() {
    }

    /** The status line at the top of the pane. */
    public static String statusLine(Snapshot s, Format f) {
        if (s.refreshing && s.lastListOkMs == 0)
            return "Loading TFRs from tfr.faa.gov…";
        if (s.lastListOkMs == 0)
            return "No TFR data yet."
                    + (s.problem != null ? " " + s.problem : "");
        String base = "FAA data as of " + f.zulu(s.lastListOkMs) + " ("
                + Format.ago(s.nowMs, s.lastListOkMs) + ")";
        if (s.refreshing)
            base += " · updating…";
        return base;
    }

    /** True if the status line should be shown as a warning. */
    public static boolean statusIsWarning(Snapshot s) {
        return s.lastListOkMs == 0 || s.isStale() || s.problem != null;
    }

    /** A second line explaining staleness or the latest problem; null if none. */
    public static String warningLine(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        if (s.lastListOkMs > 0 && s.isStale())
            sb.append("STALE: not updated for over ")
                    .append(Tuning.STALE_MS / 60000)
                    .append(" min. New TFRs may be missing. ");
        if (s.problem != null)
            sb.append(s.problem);
        String w = sb.toString().trim();
        return w.isEmpty() ? null : w;
    }

    public static List<Row> build(Snapshot s, Filter filter, LatLon self,
            Format f) {
        List<Row> rows = new ArrayList<>();
        for (Snapshot.Item it : s.items) {
            Row r = row(it, s.nowMs, self, f);
            if (filter.securityOnly && !r.category.isSecurity())
                continue;
            if (!filter.showExpired && r.rank == 3)
                continue;
            rows.add(r);
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (a.rank != b.rank)
                    return Integer.compare(a.rank, b.rank);
                boolean ad = !Double.isNaN(a.distanceM), bd = !Double.isNaN(b.distanceM);
                if (ad && bd && a.distanceM != b.distanceM)
                    return Double.compare(a.distanceM, b.distanceM);
                if (ad != bd)
                    return ad ? -1 : 1;
                if (a.category.isSecurity() != b.category.isSecurity())
                    return a.category.isSecurity() ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
        return rows;
    }

    static Row row(Snapshot.Item it, long now, LatLon self, Format f) {
        List<String> notes = new ArrayList<>();
        Tfr t = it.tfr;
        Category cat = t != null ? t.category : Category.of(null, it.entry.type);
        String place = t != null && !t.place().isEmpty() ? t.place()
                : it.entry.description;
        String title = cat.label + " · " + place;

        String status;
        int color;
        int rank;
        double dist = Double.NaN;
        boolean outline = false;
        String sub;
        if (t == null) {
            status = "Details not available yet";
            color = DIM_COLOR;
            rank = 2;
            sub = "FDC " + it.id() + " · " + it.entry.description;
        } else {
            Schedule.State st = t.stateAt(now);
            long next = t.nextChange(now);
            switch (st) {
                case ACTIVE:
                    status = next < 0 ? "ACTIVE (no published end)"
                            : "ACTIVE until " + f.when(next);
                    color = ACTIVE_COLOR;
                    rank = 0;
                    break;
                case PENDING:
                    status = "Starts " + f.when(next) + ", in "
                            + Format.duration(next - now);
                    color = PENDING_COLOR;
                    rank = 1;
                    break;
                case BETWEEN:
                    status = "Not active now. Next: " + f.when(next) + ", in "
                            + Format.duration(next - now);
                    color = PENDING_COLOR;
                    rank = 1;
                    break;
                default:
                    status = "Ended (per published times)";
                    color = DIM_COLOR;
                    rank = 3;
                    break;
            }
            sub = altitudes(t) + " · FDC " + t.id;
            outline = t.hasOutline();
            if (self != null && outline) {
                for (TfrArea a : t.areas)
                    if (a.hasOutline()) {
                        double d = Boundary.distanceTo(a.outline, self);
                        if (Double.isNaN(dist) || d < dist)
                            dist = d;
                    }
            }
            if (!outline)
                notes.add("No map graphic in the FAA data. Read the NOTAM text.");
            if (!t.warnings.isEmpty())
                notes.add("Some details could not be read with certainty. Check the NOTAM text.");
        }
        if (it.possiblyCancelled)
            notes.add("No longer in the FAA list. Possibly cancelled; will be removed if still missing at the next update.");
        if (it.detailError != null)
            notes.add(t == null ? it.detailError
                    : it.detailError + " Showing the last copy received.");
        return new Row(it.id(), cat, title, sub, status, color,
                Format.distance(dist), outline, notes, rank, dist);
    }

    /** "SFC–4,999 ft AGL", or "SFC–2,500 ft MSL (+3 areas)" */
    static String altitudes(Tfr t) {
        if (t.areas.isEmpty())
            return "altitudes in NOTAM text";
        TfrArea a = t.areas.get(0);
        String s = a.floor.ref == Altitude.Ref.UNKNOWN
                || a.ceiling.ref == Altitude.Ref.UNKNOWN
                        ? "altitudes in NOTAM text"
                        : Altitude.range(a.floor, a.ceiling);
        if (t.areas.size() > 1)
            s += " (" + t.areas.size() + " areas)";
        return s;
    }

    /** Full detail text for one TFR (plain text, shown in a scroll view). */
    public static String detail(Snapshot.Item it, long now, Format f) {
        StringBuilder sb = new StringBuilder();
        Tfr t = it.tfr;
        if (t == null) {
            sb.append("FDC ").append(it.id()).append("\n")
                    .append(it.entry.type).append(" · ")
                    .append(it.entry.description).append("\n\n")
                    .append(it.detailError != null ? it.detailError
                            : "Details not loaded yet.");
            return sb.toString();
        }
        sb.append("FDC ").append(t.id).append(" · ")
                .append(t.category.label).append("\n");
        sb.append(t.category.regulation).append("\n");
        sb.append(t.place());
        if (!t.facility.isEmpty())
            sb.append(" · ").append(t.facility).append(" ARTCC");
        sb.append("\n");
        sb.append("Effective: ").append(t.effectiveMs <= 0 ? "in effect"
                : f.when(t.effectiveMs)).append("\n");
        sb.append("Ends: ").append(f.when(t.expireMs)).append("\n");
        int n = 1;
        for (TfrArea a : t.areas) {
            sb.append("\n");
            String name = a.name.isEmpty() ? "Area " + n : a.name;
            sb.append(name).append(": ");
            sb.append(a.floor.ref == Altitude.Ref.UNKNOWN
                    || a.ceiling.ref == Altitude.Ref.UNKNOWN
                            ? "altitudes: see NOTAM text"
                            : Altitude.range(a.floor, a.ceiling));
            sb.append("\n  ").append(areaState(a, now, f));
            if (a.schedule.uncertain)
                sb.append("\n  Schedule partly unreadable: shown as active for its whole period.");
            if (!a.geometryNote.isEmpty())
                sb.append("\n  ").append(a.geometryNote);
            sb.append("\n  Outline: ").append(sourceText(a.source));
            n++;
        }
        if (!t.warnings.isEmpty()) {
            sb.append("\n\nCould not be read with certainty:");
            for (String w : t.warnings)
                sb.append("\n• ").append(w);
        }
        if (it.possiblyCancelled)
            sb.append("\n\nThis TFR is no longer in the FAA list. It may have been cancelled.");
        if (it.detailError != null)
            sb.append("\n\n").append(it.detailError)
                    .append(" Showing the last copy received.");
        sb.append("\n\nNOTAM TEXT (the authority; the map is only a depiction):\n\n");
        sb.append(t.text);
        return sb.toString();
    }

    static String areaState(TfrArea a, long now, Format f) {
        Schedule.State st = a.schedule.stateAt(now);
        long next = a.schedule.nextChange(now);
        switch (st) {
            case ACTIVE:
                return next < 0 ? "ACTIVE now (no published end)"
                        : "ACTIVE now, until " + f.when(next);
            case PENDING:
                return "Starts " + f.when(next);
            case BETWEEN:
                return "Not active now; next active " + f.when(next);
            default:
                return "Ended";
        }
    }

    static String sourceText(TfrArea.Source s) {
        switch (s) {
            case DEFINITION_CHECKED:
                return "built from the published definition, matches the FAA graphic";
            case DEFINITION:
                return "built from the published definition";
            case FAA_OUTLINE:
                return "FAA graphic";
            default:
                return "none (text only)";
        }
    }
}
