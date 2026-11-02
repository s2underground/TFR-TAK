package com.atakmap.android.tfr.plugin.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a TFR area outline from its published definition (the FAA XML
 * {@code aseShapes} vertices), and checks it against the FAA's own
 * pre-computed outline ({@code abdMergedArea}).
 *
 * <p>Vertex types (AIXM 4.5, as used in the FAA TFR XML):
 * <ul>
 * <li>GRC: a point; the edge to the next point is a great circle (geodesic).
 * <li>CIR: a whole circle (center + radius); the only vertex of its shape.
 * <li>CWA / CCA: an arc (center + radius) running clockwise /
 * counter-clockwise from the previous point to the next point.
 * </ul>
 * The arc rule was confirmed on all 50 shapes of the Nov 2, 2026 fixtures:
 * every outline built this way matches the FAA's within 0.5 percent of its
 * size, the difference being the FAA's own coarse 36-point circles.
 *
 * <p>Outlines are densified finely (arc chord error under {@link #MAX_SAG_M},
 * geodesic edges split below {@link #MAX_EDGE_M}) so they draw correctly
 * in any map projection.
 */
public final class Boundary {

    private Boundary() {
    }

    /** Max distance an arc chord may fall inside the true arc. */
    static final double MAX_SAG_M = 5.0;
    /** Max length of a straight drawn segment. */
    static final double MAX_EDGE_M = 5000.0;
    /** Arc step limits, degrees. */
    static final double MIN_STEP_DEG = 0.5, MAX_STEP_DEG = 3.0;

    /** One definition vertex. */
    public static final class Vertex {
        public final String type; // GRC, CIR, CWA, CCA
        public final LatLon point; // GRC; CIR center
        public final LatLon arcCenter; // CWA/CCA
        public final double radiusM; // CIR, CWA, CCA

        public Vertex(String type, LatLon point, LatLon arcCenter,
                double radiusM) {
            this.type = type;
            this.point = point;
            this.arcCenter = arcCenter;
            this.radiusM = radiusM;
        }
    }

    /** Thrown when a definition can't be turned into an outline. */
    public static final class GeometryException extends Exception {
        public GeometryException(String msg) {
            super(msg);
        }
    }

    /**
     * The outline (open ring: the first point is not repeated) for a shape
     * definition.
     */
    public static List<LatLon> build(List<Vertex> def)
            throws GeometryException {
        if (def == null || def.isEmpty())
            throw new GeometryException("no vertices");
        if (def.size() == 1 && "CIR".equals(def.get(0).type))
            return circle(def.get(0));
        for (Vertex v : def)
            if ("CIR".equals(v.type))
                throw new GeometryException("circle mixed with other vertices");

        int n = def.size();
        List<LatLon> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Vertex v = def.get(i);
            if ("GRC".equals(v.type)) {
                requirePoint(v.point, i);
                out.add(v.point);
            } else if ("CWA".equals(v.type) || "CCA".equals(v.type)) {
                LatLon from = previousPoint(def, i);
                LatLon to = nextPoint(def, i);
                requirePoint(v.arcCenter, i);
                if (!(v.radiusM > 0) || v.radiusM > 2000 * Geo.METERS_PER_NM)
                    throw new GeometryException("bad arc radius at vertex " + i);
                out.addAll(arcInterior(v.arcCenter, v.radiusM, from, to,
                        "CWA".equals(v.type)));
            } else {
                throw new GeometryException(
                        "unknown vertex type '" + v.type + "' at " + i);
            }
        }
        // Drop a repeated closing point and consecutive duplicates.
        List<LatLon> ring = new ArrayList<>();
        for (LatLon p : out)
            if (ring.isEmpty() || !ring.get(ring.size() - 1).equals(p))
                ring.add(p);
        if (ring.size() > 1 && ring.get(0).equals(ring.get(ring.size() - 1)))
            ring.remove(ring.size() - 1);
        if (ring.size() < 3)
            throw new GeometryException("fewer than 3 distinct points");
        return densifyEdges(ring);
    }

    private static void requirePoint(LatLon p, int i) throws GeometryException {
        if (p == null || !p.isValid())
            throw new GeometryException("missing or invalid point at vertex " + i);
    }

    private static LatLon previousPoint(List<Vertex> def, int i)
            throws GeometryException {
        int n = def.size();
        for (int k = 1; k < n; k++) {
            Vertex v = def.get(((i - k) % n + n) % n);
            if ("GRC".equals(v.type) && v.point != null)
                return v.point;
            if (!"GRC".equals(v.type))
                break;
        }
        throw new GeometryException("arc at vertex " + i + " has no start point");
    }

    private static LatLon nextPoint(List<Vertex> def, int i)
            throws GeometryException {
        Vertex v = def.get((i + 1) % def.size());
        if ("GRC".equals(v.type) && v.point != null)
            return v.point;
        throw new GeometryException("arc at vertex " + i + " has no end point");
    }

    /** Step in degrees for a radius, keeping chord error under MAX_SAG_M. */
    static double stepDeg(double radiusM) {
        double ratio = 1 - MAX_SAG_M / radiusM;
        double step = ratio <= 0 ? MAX_STEP_DEG
                : Math.toDegrees(2 * Math.acos(ratio));
        return Math.max(MIN_STEP_DEG, Math.min(MAX_STEP_DEG, step));
    }

    static List<LatLon> circle(Vertex v) throws GeometryException {
        if (v.point == null || !v.point.isValid())
            throw new GeometryException("circle has no valid center");
        if (!(v.radiusM > 0) || v.radiusM > 2000 * Geo.METERS_PER_NM)
            throw new GeometryException("bad circle radius");
        int n = (int) Math.ceil(360.0 / stepDeg(v.radiusM));
        List<LatLon> ring = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            ring.add(Geo.destination(v.point, 360.0 * i / n, v.radiusM));
        return ring;
    }

    /** Points strictly between from and to along the arc. */
    static List<LatLon> arcInterior(LatLon c, double r, LatLon from,
            LatLon to, boolean clockwise) {
        double a1 = Geo.azimuth(c, from);
        double a2 = Geo.azimuth(c, to);
        double sweep = clockwise ? mod360(a2 - a1) : -mod360(a1 - a2);
        if (sweep == 0) // start == end: a full circle back to the start
            sweep = clockwise ? 360 : -360;
        int n = Math.max(2, (int) Math.ceil(Math.abs(sweep) / stepDeg(r)));
        List<LatLon> pts = new ArrayList<>(n);
        for (int i = 1; i < n; i++)
            pts.add(Geo.destination(c, a1 + sweep * i / n, r));
        return pts;
    }

    private static double mod360(double d) {
        double m = d % 360;
        return m < 0 ? m + 360 : m;
    }

    /** Split long edges along the geodesic so they draw true in any projection. */
    public static List<LatLon> densifyEdges(List<LatLon> ring) {
        List<LatLon> out = new ArrayList<>(ring.size());
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            LatLon a = ring.get(i), b = ring.get((i + 1) % n);
            out.add(a);
            double[] inv = Geo.inverse(a, b);
            if (inv[0] > MAX_EDGE_M) {
                int parts = (int) Math.ceil(inv[0] / MAX_EDGE_M);
                for (int k = 1; k < parts; k++)
                    out.add(Geo.destination(a, inv[1], inv[0] * k / parts));
            }
        }
        return out;
    }

    /**
     * Largest distance (m) between the two outlines, measured both ways
     * (each vertex of one to the nearest edge of the other).
     */
    public static double maxDeviation(List<LatLon> a, List<LatLon> b) {
        return Math.max(oneWay(a, b), oneWay(b, a));
    }

    private static double oneWay(List<LatLon> from, List<LatLon> to) {
        double worst = 0;
        int n = to.size();
        for (LatLon p : from) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < n; i++)
                best = Math.min(best,
                        Geo.distanceToSegment(p, to.get(i), to.get((i + 1) % n)));
            worst = Math.max(worst, best);
        }
        return worst;
    }

    /** Largest distance between any two vertices (a size measure). */
    public static double extent(List<LatLon> ring) {
        double lat0 = 90, lat1 = -90, lonMin = 180, lonMax = -180;
        for (LatLon p : ring) {
            lat0 = Math.min(lat0, p.lat);
            lat1 = Math.max(lat1, p.lat);
            lonMin = Math.min(lonMin, p.lon);
            lonMax = Math.max(lonMax, p.lon);
        }
        return Geo.distance(new LatLon(lat0, lonMin), new LatLon(lat1, lonMax));
    }

    /**
     * Allowed deviation between our outline and the FAA's: the FAA draws
     * circles with 36 points (about 0.6 percent inside), so 1.5 percent of
     * the size plus 100 m.
     */
    public static double tolerance(List<LatLon> ring) {
        return 100.0 + 0.015 * extent(ring);
    }

    /** Ring with the closing point removed if present. */
    public static List<LatLon> open(List<LatLon> pts) {
        List<LatLon> r = new ArrayList<>(pts);
        if (r.size() > 1 && r.get(0).equals(r.get(r.size() - 1)))
            r.remove(r.size() - 1);
        return Collections.unmodifiableList(r);
    }

    /** Ray-casting point-in-polygon test (fine for TFR-sized areas). */
    public static boolean contains(List<LatLon> ring, LatLon p) {
        boolean in = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            LatLon a = ring.get(i), b = ring.get(j);
            double ax = Geo.dLon(a.lon, p.lon), bx = Geo.dLon(b.lon, p.lon);
            if ((a.lat > p.lat) != (b.lat > p.lat)) {
                double x = ax + (p.lat - a.lat) / (b.lat - a.lat) * (bx - ax);
                if (x > 0)
                    in = !in;
            }
        }
        return in;
    }

    /**
     * Distance (m) from p to the area: 0 inside, else to the nearest edge.
     * Far away (over 50 km) it is the geodesic distance to the nearest
     * vertex, which on a densified outline (edges of 5 km or less) is within
     * 2.5 km and avoids the flat approximation's error at long range.
     */
    public static double distanceTo(List<LatLon> ring, LatLon p) {
        if (contains(ring, p))
            return 0;
        double nearestVertex = Double.MAX_VALUE;
        for (LatLon q : ring)
            nearestVertex = Math.min(nearestVertex, Geo.distance(p, q));
        if (nearestVertex > 50000)
            return nearestVertex;
        double best = nearestVertex;
        int n = ring.size();
        for (int i = 0; i < n; i++)
            best = Math.min(best, Geo.distanceToSegment(p, ring.get(i),
                    ring.get((i + 1) % n)));
        return best;
    }
}
