package com.atakmap.android.tfr.plugin.ui;

import com.atakmap.android.tfr.plugin.core.Altitude;
import com.atakmap.android.tfr.plugin.core.Boundary;
import com.atakmap.android.tfr.plugin.core.Geo;
import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.core.NotamId;
import com.atakmap.android.tfr.plugin.core.Schedule;
import com.atakmap.android.tfr.plugin.core.Tfr;
import com.atakmap.android.tfr.plugin.core.TfrArea;
import com.atakmap.android.tfr.plugin.engine.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What to draw on the map for a snapshot: one shape per area that has an
 * outline and hasn't ended. Pure Java (tested); atak/MapLayer applies it.
 *
 * <p>Style: color by category; solid outline with a stronger fill when
 * active; dashed with a faint fill when published but not active now;
 * dotted when the TFR has dropped out of the FAA list.
 */
public final class MapPlan {

    public static final String UID_PREFIX = "tfrtak-";
    /** Labels appear once an area spans about this many pixels. */
    public static final double LABEL_MIN_PIXELS = 150.0;

    public enum Line {
        SOLID, DASHED, DOTTED
    }

    public static final class Shape {
        public final String uid;
        public final String tfrId;
        public final List<LatLon> outline;
        public final int stroke;
        public final int fill;
        public final Line line;
        /** Extrusion height above ground in metres; NaN = flat. */
        public final double heightM;
        public final String label;
        public final String title;
        /**
         * Coarsest map resolution (m/pixel) at which the label is drawn.
         * ATAK's default is 10 m/px (street level), which hides TFR labels
         * at any zoom where the TFR is visible; we show it once the area is
         * about {@link #LABEL_MIN_PIXELS} pixels across.
         */
        public final double labelMaxResM;

        Shape(String uid, String tfrId, List<LatLon> outline, int stroke,
                int fill, Line line, double heightM, String label,
                String title) {
            this.uid = uid;
            this.tfrId = tfrId;
            this.outline = outline;
            this.stroke = stroke;
            this.fill = fill;
            this.line = line;
            this.heightM = heightM;
            this.label = label;
            this.title = title;
            this.labelMaxResM = Math.max(10.0,
                    Boundary.extent(outline) / LABEL_MIN_PIXELS);
        }

        /** Changes when anything drawn changes (every point counts). */
        public String signature() {
            long h = 17;
            for (LatLon p : outline)
                h = h * 31 + p.hashCode();
            return outline.size() + "|" + h + "|" + stroke + "|" + fill + "|"
                    + line + "|" + heightM + "|" + label + "|" + title + "|"
                    + labelMaxResM;
        }
    }

    private MapPlan() {
    }

    public static List<Shape> build(Snapshot s, boolean securityOnly) {
        List<Shape> out = new ArrayList<>();
        for (Snapshot.Item it : s.items) {
            Tfr t = it.tfr;
            if (t == null)
                continue;
            if (securityOnly && !t.category.isSecurity())
                continue;
            int i = 0;
            for (TfrArea a : t.areas) {
                i++;
                if (!a.hasOutline())
                    continue;
                Schedule.State st = a.schedule.stateAt(s.nowMs);
                if (st == Schedule.State.EXPIRED)
                    continue;
                boolean active = st == Schedule.State.ACTIVE;
                Line line = it.possiblyCancelled ? Line.DOTTED
                        : active ? Line.SOLID : Line.DASHED;
                int rgb = t.category.color & 0x00FFFFFF;
                int fill = (active && !it.possiblyCancelled ? 0x50000000
                        : 0x1A000000) | rgb;
                double ft = a.ceiling.extrusionFeet();
                double h = Double.isNaN(ft) || ft <= 0 ? Double.NaN
                        : ft * Geo.METERS_PER_FOOT;
                String range = a.floor.ref == Altitude.Ref.UNKNOWN
                        || a.ceiling.ref == Altitude.Ref.UNKNOWN ? "see NOTAM"
                                : Altitude.range(a.floor, a.ceiling);
                String state = it.possiblyCancelled ? "possibly cancelled"
                        : active ? "ACTIVE"
                                : st == Schedule.State.PENDING ? "not yet active"
                                        : "not active now";
                String label = t.category.label + " · " + range + " · "
                        + state;
                String title = "TFR " + t.id + " " + t.category.label
                        + (t.areas.size() > 1 && !a.name.isEmpty()
                                ? " " + a.name : "");
                out.add(new Shape(UID_PREFIX + NotamId.fileKey(t.id) + "-" + i,
                        t.id, a.outline, t.category.color, fill, line, h, label,
                        title));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The NOTAM id of a map item uid we created; null if not ours. */
    public static String idOf(String uid) {
        if (uid == null || !uid.startsWith(UID_PREFIX))
            return null;
        String rest = uid.substring(UID_PREFIX.length());
        int dash = rest.lastIndexOf('-');
        if (dash <= 0)
            return null;
        String id = rest.substring(0, dash).replace('_', '/');
        return NotamId.isValid(id) ? id : null;
    }
}
