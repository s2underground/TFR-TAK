package com.atakmap.android.tfr.plugin.core;

/** An immutable WGS84 latitude/longitude in decimal degrees. */
public final class LatLon {

    public final double lat;
    public final double lon;

    public LatLon(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    /** True if both values are finite and inside the valid ranges. */
    public boolean isValid() {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && !Double.isInfinite(lat) && !Double.isInfinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LatLon))
            return false;
        LatLon p = (LatLon) o;
        return Double.compare(lat, p.lat) == 0
                && Double.compare(lon, p.lon) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(lat) * 31 + Double.hashCode(lon);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon);
    }
}
