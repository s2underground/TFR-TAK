package com.atakmap.android.tfr.plugin.atak;

import android.graphics.Color;
import android.graphics.Typeface;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.tfr.plugin.ui.MapPlan;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.opengl.GLLabelManager;
import com.atakmap.map.opengl.GLMapSurface;
import com.atakmap.map.opengl.GLMapView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TFR labels drawn straight through ATAK's map label manager (the one ATAK's
 * own marker and shape labels use), so they can be drawn "x-ray".
 *
 * <p>Why not the polylines' own center labels: in a tilted 3D view ATAK
 * depth-tests labels, and the extruded TFR boxes write depth, so a label
 * on the ground inside its box (always, for a center label; and for nested
 * areas like the DC rings, anywhere) is hidden by the box's walls and roof.
 * The x-ray hint draws the hidden part at reduced opacity, as ATAK does for
 * markers behind terrain. In the flat (nadir) view nothing changes.
 *
 * <p>Thresholds are whole ATAK tile levels (see {@link MapPlan.Label#level}),
 * computed for this screen's density. Main thread only.
 */
final class LabelLayer {

    private static final String TAG = "TfrLabelLayer";

    private final GLLabelManager labels;
    private final double minPixels;
    private final MapTextFormat format;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, String> signatures = new HashMap<>();
    private final Map<String, List<String>> members = new HashMap<>();
    private final Map<String, Boolean> shown = new HashMap<>();

    private LabelLayer(GLLabelManager labels, double density) {
        this.labels = labels;
        this.minPixels = MapPlan.LABEL_MIN_DP * Math.max(1.0, density);
        this.format = new MapTextFormat(Typeface.DEFAULT,
                MapView.getDefaultTextFormat().getFontSize());
    }

    /** Null if ATAK's label manager can't be reached (then: polyline labels). */
    static LabelLayer create(MapView mapView) {
        try {
            GLMapSurface surface = mapView.getGLSurface();
            GLMapView view = surface != null ? surface.getGLMapView() : null;
            GLLabelManager lm = view != null ? view.getLabelManager() : null;
            if (lm == null)
                return null;
            double density = mapView.getResources().getDisplayMetrics().density;
            return new LabelLayer(lm, density);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "label manager not available, using shape labels", e);
            return null;
        }
    }

    /** Pixels an area must span for its label (this screen). */
    double minPixels() {
        return minPixels;
    }

    interface Visibility {
        /** Whether the map shape with this uid is currently shown. */
        boolean isShown(String shapeUid);
    }

    void apply(List<MapPlan.Label> plan, Visibility vis) {
        Set<String> wanted = new HashSet<>();
        for (MapPlan.Label l : plan) {
            wanted.add(l.uid);
            String sig = l.signature();
            Integer id = ids.get(l.uid);
            try {
                if (id == null) {
                    id = labels.addLabel(l.text);
                    ids.put(l.uid, id);
                    style(id);
                }
                if (!sig.equals(signatures.get(l.uid))) {
                    labels.setText(id, l.text);
                    labels.setGeometry(id,
                            new Point(l.anchor.lon, l.anchor.lat));
                    labels.setAltitudeMode(id,
                            Feature.AltitudeMode.ClampToGround);
                    labels.setLevelOfDetail(id, l.level(minPixels),
                            Integer.MAX_VALUE);
                    signatures.put(l.uid, sig);
                    members.put(l.uid, l.shapeUids);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "could not label " + l.uid, e);
            }
        }
        for (String uid : new HashSet<>(ids.keySet()))
            if (!wanted.contains(uid))
                remove(uid);
        refresh(vis);
    }

    private void style(int id) {
        labels.setTextFormat(id, format);
        labels.setColor(id, Color.WHITE);
        labels.setFill(id, true);
        // Same backing as ATAK's own labels (60% black).
        labels.setBackgroundColor(id, Color.argb(153, 0, 0, 0));
        labels.setVerticalAlignment(id,
                GLLabelManager.VerticalAlignment.Middle);
        labels.setPriority(id, GLLabelManager.Priority.Standard);
        labels.setHints(id,
                labels.getHints(id) | GLLabelManager.HINT_XRAY);
        // Taps go to the shape underneath, which opens the TFR.
        labels.setHitTestable(id, false);
    }

    /** Shows each label while any of its shapes is shown. */
    void refresh(Visibility vis) {
        for (Map.Entry<String, Integer> e : ids.entrySet()) {
            boolean v = false;
            List<String> m = members.get(e.getKey());
            if (m != null)
                for (String uid : m)
                    v |= vis.isShown(uid);
            if (!Boolean.valueOf(v).equals(shown.get(e.getKey()))) {
                try {
                    labels.setVisible(e.getValue(), v);
                    shown.put(e.getKey(), v);
                } catch (RuntimeException ex) {
                    Log.e(TAG, "label visibility", ex);
                }
            }
        }
    }

    private void remove(String uid) {
        Integer id = ids.remove(uid);
        signatures.remove(uid);
        members.remove(uid);
        shown.remove(uid);
        if (id != null) {
            try {
                labels.removeLabel(id);
            } catch (RuntimeException e) {
                Log.e(TAG, "could not remove label " + uid, e);
            }
        }
    }

    void dispose() {
        for (String uid : new HashSet<>(ids.keySet()))
            remove(uid);
    }
}
