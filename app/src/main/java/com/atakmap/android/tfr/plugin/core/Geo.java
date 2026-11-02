package com.atakmap.android.tfr.plugin.core;

/**
 * WGS84 geodesics (Vincenty direct and inverse), shared with Blast and
 * tested against GeographicLib. Distances in metres, angles in degrees.
 */
public final class Geo {

    private Geo() {
    }

    public static final double METERS_PER_NM = 1852.0;
    public static final double METERS_PER_FOOT = 0.3048;

    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double B = A * (1 - F);

    /** Mean earth radius (IUGG), for the spherical fallback. */
    static final double MEAN_RADIUS = 6371008.8;

    /** Destination from a start, azimuth (deg) and distance (m). */
    public static LatLon destination(LatLon start, double azimuthDeg,
            double distanceM) {
        double alpha1 = Math.toRadians(azimuthDeg);
        double sinA1 = Math.sin(alpha1), cosA1 = Math.cos(alpha1);
        double tanU1 = (1 - F) * Math.tan(Math.toRadians(start.lat));
        double cosU1 = 1 / Math.sqrt(1 + tanU1 * tanU1), sinU1 = tanU1 * cosU1;
        double sigma1 = Math.atan2(tanU1, cosA1);
        double sinAlpha = cosU1 * sinA1;
        double cos2Alpha = 1 - sinAlpha * sinAlpha;
        double u2 = cos2Alpha * (A * A - B * B) / (B * B);
        double bigA = 1 + u2 / 16384 * (4096 + u2 * (-768 + u2 * (320 - 175 * u2)));
        double bigB = u2 / 1024 * (256 + u2 * (-128 + u2 * (74 - 47 * u2)));
        double sigma = distanceM / (B * bigA), sigmaP;
        double cos2SigmaM, sinSigma, cosSigma;
        int guard = 0;
        do {
            cos2SigmaM = Math.cos(2 * sigma1 + sigma);
            sinSigma = Math.sin(sigma);
            cosSigma = Math.cos(sigma);
            double dSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4
                    * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                            - bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma)
                                    * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
            sigmaP = sigma;
            sigma = distanceM / (B * bigA) + dSigma;
        } while (Math.abs(sigma - sigmaP) > 1e-12 && ++guard < 200);
        double tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosA1;
        double lat2 = Math.atan2(sinU1 * cosSigma + cosU1 * sinSigma * cosA1,
                (1 - F) * Math.sqrt(sinAlpha * sinAlpha + tmp * tmp));
        double lambda = Math.atan2(sinSigma * sinA1,
                cosU1 * cosSigma - sinU1 * sinSigma * cosA1);
        double c = F / 16 * cos2Alpha * (4 + F * (4 - 3 * cos2Alpha));
        double l = lambda - (1 - c) * F * sinAlpha * (sigma + c * sinSigma
                * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        double lon2 = Math.toDegrees(Math.toRadians(start.lon) + l);
        lon2 = ((lon2 + 540) % 360) - 180;
        return new LatLon(Math.toDegrees(lat2), lon2);
    }

    /** Geodesic distance in metres; NaN only for invalid input. */
    public static double distance(LatLon a, LatLon b) {
        return inverse(a, b)[0];
    }

    /** Initial azimuth in degrees [0, 360) from a to b. */
    public static double azimuth(LatLon a, LatLon b) {
        return inverse(a, b)[1];
    }

    /**
     * {distance m, initial azimuth deg}. Vincenty, falling back to the
     * spherical solution for nearly antipodal points.
     */
    public static double[] inverse(LatLon a, LatLon b) {
        if (!a.isValid() || !b.isValid())
            return new double[] { Double.NaN, Double.NaN };
        double[] v = vincenty(a.lat, a.lon, b.lat, b.lon);
        if (v != null)
            return v;
        return spherical(a, b);
    }

    static double[] spherical(LatLon a, LatLon b) {
        double p1 = Math.toRadians(a.lat), p2 = Math.toRadians(b.lat);
        double dp = p2 - p1, dl = Math.toRadians(b.lon - a.lon);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2)
                        * Math.sin(dl / 2);
        double d = 2 * MEAN_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)));
        double az = Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(p2),
                Math.cos(p1) * Math.sin(p2)
                        - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)));
        return new double[] { d, (az + 360) % 360 };
    }

    /** Vincenty inverse: {distance, azimuth}, or null if not converged. */
    private static double[] vincenty(double lat1, double lon1, double lat2,
            double lon2) {
        double l = Math.toRadians(lon2 - lon1);
        double u1 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat1)));
        double u2 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat2)));
        double sinU1 = Math.sin(u1), cosU1 = Math.cos(u1);
        double sinU2 = Math.sin(u2), cosU2 = Math.cos(u2);
        double lambda = l, lambdaP;
        double sinSigma, cosSigma, sigma, cos2Alpha, cos2SigmaM;
        double sinL, cosL;
        int guard = 0;
        do {
            sinL = Math.sin(lambda);
            cosL = Math.cos(lambda);
            sinSigma = Math.sqrt((cosU2 * sinL) * (cosU2 * sinL)
                    + (cosU1 * sinU2 - sinU1 * cosU2 * cosL)
                            * (cosU1 * sinU2 - sinU1 * cosU2 * cosL));
            if (sinSigma == 0)
                return new double[] { 0, 0 };
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosL;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cosU1 * cosU2 * sinL / sinSigma;
            cos2Alpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cos2Alpha == 0 ? 0
                    : cosSigma - 2 * sinU1 * sinU2 / cos2Alpha;
            double c = F / 16 * cos2Alpha * (4 + F * (4 - 3 * cos2Alpha));
            lambdaP = lambda;
            lambda = l + (1 - c) * F * sinAlpha * (sigma + c * sinSigma
                    * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        } while (Math.abs(lambda - lambdaP) > 1e-12 && ++guard < 200);
        if (guard >= 200)
            return null;
        double u2b = cos2Alpha * (A * A - B * B) / (B * B);
        double bigA = 1 + u2b / 16384 * (4096 + u2b * (-768 + u2b * (320 - 175 * u2b)));
        double bigB = u2b / 1024 * (256 + u2b * (-128 + u2b * (74 - 47 * u2b)));
        double dSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4
                * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                        - bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma)
                                * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        double dist = B * bigA * (sigma - dSigma);
        sinL = Math.sin(lambda);
        cosL = Math.cos(lambda);
        double az = Math.toDegrees(Math.atan2(cosU2 * sinL,
                cosU1 * sinU2 - sinU1 * cosU2 * cosL));
        return new double[] { dist, (az + 360) % 360 };
    }

    /**
     * Distance in metres from p to the segment a-b, in a local flat
     * approximation around p. Accurate to well under 1 percent for the
     * short segments of a densified boundary; used for proximity and
     * cross-checks, not for drawing.
     */
    public static double distanceToSegment(LatLon p, LatLon a, LatLon b) {
        double k = Math.cos(Math.toRadians(p.lat));
        double ax = dLon(a.lon, p.lon) * k * 111320.0;
        double ay = (a.lat - p.lat) * 110574.0;
        double bx = dLon(b.lon, p.lon) * k * 111320.0;
        double by = (b.lat - p.lat) * 110574.0;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0
                : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    /** Longitude difference a - b wrapped to [-180, 180). */
    static double dLon(double a, double b) {
        double d = a - b;
        return ((d + 540) % 360) - 180;
    }
}
