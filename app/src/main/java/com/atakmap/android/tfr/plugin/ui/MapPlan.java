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
    /**
     * A label appears once its area spans at least this many dp on screen
     * (between 1x and 2x this, since ATAK switches labels per whole tile
     * level). About the width of a label, so a label is never much wider
     * than the area it names, and still shown at the "Show on map" zoom.
     */
    public static final double LABEL_MIN_DP = 160.0;
    /** Map resolution of tile level 0 at the equator (m/pixel), as ATAK. */
    public static final double LEVEL0_RES = 156543.034;

    public enum Line {
        SOLID, DASHED, DOTTED
    }

    public static final class Shape {
        public final String uid;
        public final String tfrId;
        /** The area's name as published ("Area B"); may be empty. */
        public final String areaName;
        public final List<LatLon> outline;
        public final int stroke;
        public final int fill;
        public final Line line;
        /** Extrusion height above ground in metres; NaN = flat. */
        public final double heightM;
        /** One-area label: "Security · SFC–17,999 ft MSL · ACTIVE". */
        public final String label;
        final String head, range, state;
        public final String title;
        /** Bounding-box diagonal in metres (what the label zoom uses). */
        public final double extentM;
        /** Center of the bounding box, where ATAK would put the label. */
        public final LatLon center;
        /** Bounding-box corners (south-west, north-east). */
        public final LatLon sw, ne;

        Shape(String uid, String tfrId, String areaName, List<LatLon> outline,
                int stroke, int fill, Line line, double heightM, String head,
                String range, String state, String title) {
            this.uid = uid;
            this.tfrId = tfrId;
            this.areaName = areaName;
            this.outline = outline;
            this.stroke = stroke;
            this.fill = fill;
            this.line = line;
            this.heightM = heightM;
            this.head = head;
            this.range = range;
            this.state = state;
            this.label = head + " · " + range + " · " + state;
            this.title = title;
            this.extentM = Boundary.extent(outline);
            this.center = center(outline);
            LatLon[] box = box(outline);
            this.sw = box[0];
            this.ne = box[1];
        }

        /** Changes when anything drawn changes (every point counts). */
        public String signature() {
            long h = 17;
            for (LatLon p : outline)
                h = h * 31 + p.hashCode();
            return outline.size() + "|" + h + "|" + stroke + "|" + fill + "|"
                    + line + "|" + heightM + "|" + label + "|" + title;
        }
    }

    /**
     * One map label. Areas of the same TFR that lie inside one another
     * (the Washington DC rings) share one label: separate labels would sit
     * on top of each other.
     *
     * <p>Drawn by atak/MapLayer through ATAK's label manager rather than as
     * the polylines' own center labels, because ATAK depth-tests labels in
     * tilted 3D views and a TFR's extruded box hides a label on the ground
     * inside it (ATAK 5.5 GLLabelManager / GLBatchGeometryRenderer4). Ours
     * are drawn "x-ray", like markers behind terrain.
     */
    public static final class Label {
        public final String uid;
        public final String text;
        public final LatLon anchor;
        public final double extentM;
        /** The shapes this label names; it is shown while any of them is. */
        public final List<String> shapeUids;
        /**
         * Bounding box of the area it names (the largest of a group). The
         * label is shown while the box's diagonal spans at least the
         * minimum on screen, measured by projecting these two corners
         * (atak/LabelLayer), so it is right in tilted views too.
         */
        public final LatLon sw, ne;

        Label(String uid, String text, LatLon anchor, double extentM,
                List<String> shapeUids, LatLon sw, LatLon ne) {
            this.uid = uid;
            this.text = text;
            this.anchor = anchor;
            this.extentM = extentM;
            this.shapeUids = Collections.unmodifiableList(shapeUids);
            this.sw = sw;
            this.ne = ne;
        }

        /**
         * ATAK tile level from which the label is shown (and at every closer
         * zoom). ATAK compares whole levels: level = floor(log2(LEVEL0_RES /
         * resolution)). Taking the ceiling here means the label shows only
         * once the area really spans {@code minPixels} (up to 2x that).
         */
        public int level(double minPixels) {
            return labelLevel(extentM, minPixels);
        }

        public String signature() {
            return text + "|" + anchor.lat + "|" + anchor.lon + "|" + extentM
                    + "|" + shapeUids;
        }
    }

    /** See {@link Label#level}. Clamped to ATAK's 0..30. */
    public static int labelLevel(double extentM, double minPixels) {
        if (!(extentM > 0) || !(minPixels > 0))
            return 30;
        double needed = extentM / minPixels; // finest resolution allowed
        double l = Math.log(LEVEL0_RES / needed) / Math.log(2);
        int level = (int) Math.ceil(l - 1e-9);
        return Math.max(0, Math.min(30, level));
    }

    /** Resolution (m/pixel) of a tile level, as ATAK computes it. */
    public static double levelResolution(int level) {
        return LEVEL0_RES / Math.pow(2, level);
    }

    /**
     * Whether a label is shown for an area whose bounding-box diagonal spans
     * {@code screenPx} on screen. NaN (corner behind the camera or not
     * projectable) = not shown.
     */
    public static boolean bigEnough(double screenPx, double minPixels) {
        return screenPx >= minPixels;
    }

    static LatLon[] box(List<LatLon> ring) {
        double lat0 = 90, lat1 = -90, lon0 = 180, lon1 = -180;
        for (LatLon p : ring) {
            lat0 = Math.min(lat0, p.lat);
            lat1 = Math.max(lat1, p.lat);
            lon0 = Math.min(lon0, p.lon);
            lon1 = Math.max(lon1, p.lon);
        }
        return new LatLon[] { new LatLon(lat0, lon0), new LatLon(lat1, lon1) };
    }

    static LatLon center(List<LatLon> ring) {
        double lat0 = 90, lat1 = -90, lon0 = 180, lon1 = -180;
        for (LatLon p : ring) {
            lat0 = Math.min(lat0, p.lat);
            lat1 = Math.max(lat1, p.lat);
            lon0 = Math.min(lon0, p.lon);
            lon1 = Math.max(lon1, p.lon);
        }
        return new LatLon((lat0 + lat1) / 2, (lon0 + lon1) / 2);
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
                String title = "TFR " + t.id + " " + t.category.label
                        + (t.areas.size() > 1 && !a.name.isEmpty()
                                ? " " + a.name : "");
                out.add(new Shape(UID_PREFIX + NotamId.fileKey(t.id) + "-" + i,
                        t.id, a.name, a.outline, t.category.color, fill, line,
                        h, t.category.label, range, state, title));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The labels for a plan: one per area, except that areas of one TFR
     * lying inside one another share a label at the largest one's center.
     * If the merged areas differ in altitudes or state, the label lists
     * each ("Area B: SFC–3,000 ft AGL · ACTIVE").
     */
    public static List<Label> labels(List<Shape> plan) {
        List<Label> out = new ArrayList<>();
        int n = plan.size();
        int[] group = new int[n];
        for (int i = 0; i < n; i++)
            group[i] = i;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                Shape a = plan.get(i), b = plan.get(j);
                if (!a.tfrId.equals(b.tfrId))
                    continue;
                if (Boundary.contains(a.outline, b.center)
                        || Boundary.contains(b.outline, a.center))
                    union(group, i, j);
            }
        for (int i = 0; i < n; i++) {
            if (find(group, i) != i)
                continue;
            List<Shape> members = new ArrayList<>();
            for (int j = 0; j < n; j++)
                if (find(group, j) == i)
                    members.add(plan.get(j));
            Shape big = members.get(0);
            for (Shape m : members)
                if (m.extentM > big.extentM)
                    big = m;
            List<String> uids = new ArrayList<>();
            boolean same = true;
            for (Shape m : members) {
                uids.add(m.uid);
                same &= m.range.equals(big.range) && m.state.equals(big.state);
            }
            String text;
            if (same)
                text = big.label;
            else {
                StringBuilder b = new StringBuilder(big.head);
                int k = 0;
                for (Shape m : members) {
                    k++;
                    String name = m.areaName.trim().isEmpty() ? "Area " + k
                            : m.areaName.trim();
                    b.append('\n').append(name).append(": ").append(m.range)
                            .append(" · ").append(m.state);
                }
                text = b.toString();
            }
            out.add(new Label(big.uid + "-label", text, big.center,
                    big.extentM, uids, big.sw, big.ne));
        }
        return Collections.unmodifiableList(out);
    }

    private static int find(int[] g, int i) {
        while (g[i] != i)
            i = g[i] = g[g[i]];
        return i;
    }

    private static void union(int[] g, int a, int b) {
        int ra = find(g, a), rb = find(g, b);
        if (ra != rb)
            g[Math.max(ra, rb)] = Math.min(ra, rb);
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
