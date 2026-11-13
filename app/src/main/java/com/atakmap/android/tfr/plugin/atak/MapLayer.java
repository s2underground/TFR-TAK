package com.atakmap.android.tfr.plugin.atak;

import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;
import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.ui.MapPlan;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.AtakMapView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws a {@link MapPlan} as ATAK polylines in the plugin's own map group.
 * Items are local only: never saved by ATAK, never sent over CoT, not
 * editable; they are redrawn from the cache when ATAK starts. Only shapes
 * whose signature changed are touched. The group is registered with ATAK's
 * Overlay Manager (a plain group added to the root is not listed there).
 * Labels are drawn by {@link LabelLayer} (x-ray, so a TFR's own 3D box
 * can't hide them); if ATAK's label manager can't be reached, the
 * polylines' own center labels are used instead. Main thread only.
 */
public final class MapLayer {

    private static final String TAG = "TfrMapLayer";
    public static final String GROUP_NAME = "TFR-TAK";
    /** Name used by 0.1-0.3; removed if left over. */
    private static final String OLD_GROUP_NAME = "TFRs (TFR-TAK)";

    private final MapView mapView;
    /** base64:// icon for the Overlay Manager entry; may be null. */
    private final String iconUri;
    private MapGroup group;
    private DefaultMapGroupOverlay overlay;
    private final Map<String, String> drawn = new HashMap<>();
    private LabelLayer labels;
    /** The pane's "Show labels" switch. */
    private boolean labelsOn = true;
    private boolean labelsChecked;
    private final MapItem.OnVisibleChangedListener visListener = new MapItem.OnVisibleChangedListener() {
        @Override
        public void onVisibleChanged(MapItem item) {
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    refreshLabels();
                }
            });
        }
    };
    private final MapGroup.OnVisibleChangedListener groupVisListener = new MapGroup.OnVisibleChangedListener() {
        @Override
        public void onGroupVisibleChanged(MapGroup g) {
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    refreshLabels();
                }
            });
        }
    };
    /** Re-checks label sizes as the map moves, at most every 150 ms. */
    private boolean moveQueued;
    private final Runnable moveCheck = new Runnable() {
        @Override
        public void run() {
            moveQueued = false;
            refreshLabels();
        }
    };
    private final AtakMapView.OnMapMovedListener mapMoved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView view, boolean animate) {
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    if (!moveQueued) {
                        moveQueued = true;
                        mapView.postDelayed(moveCheck, 150);
                    }
                }
            });
        }
    };
    private final LabelLayer.Visibility visibility = new LabelLayer.Visibility() {
        @Override
        public boolean isShown(String shapeUid) {
            if (group == null || !group.getVisible())
                return false;
            MapItem it = group.deepFindUID(shapeUid);
            return it != null && it.getVisible();
        }
    };

    public MapLayer(MapView mapView, String iconUri) {
        this.mapView = mapView;
        this.iconUri = iconUri;
    }

    private MapGroup group() {
        if (group == null) {
            MapGroup existing = mapView.getRootGroup().findMapGroup(GROUP_NAME);
            if (existing != null) {
                group = existing;
                group.clearItems();
            } else {
                group = new DefaultMapGroup(GROUP_NAME);
                mapView.getRootGroup().addGroup(group);
            }
            group.addOnVisibleChangedListener(groupVisListener);
            MapGroup old = mapView.getRootGroup().findMapGroup(OLD_GROUP_NAME);
            if (old != null) {
                old.clearItems();
                mapView.getRootGroup().removeGroup(old);
            }
            try {
                overlay = new DefaultMapGroupOverlay(mapView, group, iconUri);
                mapView.getMapOverlayManager().addOverlay(overlay);
            } catch (RuntimeException e) {
                Log.e(TAG, "Overlay Manager registration failed", e);
                overlay = null;
            }
        }
        return group;
    }

    public void apply(List<MapPlan.Shape> plan) {
        MapGroup g = group();
        if (!labelsChecked) {
            labelsChecked = true;
            labels = LabelLayer.create(mapView);
            if (labels != null) {
                labels.setLabelsOn(labelsOn);
                mapView.addOnMapMovedListener(mapMoved);
            }
        }
        final double minPixels = labels != null ? labels.minPixels()
                : MapPlan.LABEL_MIN_DP
                        * mapView.getResources().getDisplayMetrics().density;
        Set<String> wanted = new HashSet<>();
        for (MapPlan.Shape s : plan) {
            wanted.add(s.uid);
            String sig = s.signature();
            MapItem existing = g.deepFindUID(s.uid);
            if (existing != null && existing.getGroup() != null
                    && sig.equals(drawn.get(s.uid)))
                continue;
            try {
                if (existing instanceof Polyline && existing.getGroup() != null)
                    style((Polyline) existing, s, labels == null && labelsOn,
                            minPixels);
                else {
                    Polyline p = new Polyline(s.uid);
                    style(p, s, labels == null && labelsOn, minPixels);
                    p.addOnVisibleChangedListener(visListener);
                    // Hidden from Overlay Manager: new TFRs stay hidden too.
                    p.setVisible(g.getVisible());
                    g.addItem(p);
                }
                drawn.put(s.uid, sig);
            } catch (RuntimeException e) {
                Log.e(TAG, "could not draw " + s.uid, e);
            }
        }
        for (String uid : new HashSet<>(drawn.keySet())) {
            if (wanted.contains(uid))
                continue;
            MapItem it = g.deepFindUID(uid);
            if (it != null) {
                it.removeOnVisibleChangedListener(visListener);
                it.removeFromGroup();
            }
            drawn.remove(uid);
        }
        if (labels != null)
            labels.apply(MapPlan.labels(plan), visibility);
    }

    private void refreshLabels() {
        if (labels != null && group != null)
            labels.refresh(visibility);
    }

    private static void style(Polyline p, MapPlan.Shape s,
            boolean shapeLabels, double minPixels) {
        List<LatLon> o = s.outline;
        GeoPoint[] pts = new GeoPoint[o.size()];
        for (int i = 0; i < pts.length; i++)
            pts[i] = new GeoPoint(o.get(i).lat, o.get(i).lon);
        p.setPoints(pts);
        p.setStyle(Polyline.STYLE_CLOSED_MASK | Shape.STYLE_STROKE_MASK
                | Shape.STYLE_FILLED_MASK);
        p.setStrokeColor(s.stroke);
        p.setFillColor(s.fill);
        p.setStrokeWeight(3.0);
        p.setBasicLineStyle(s.line == MapPlan.Line.DASHED
                ? Shape.BASIC_LINE_STYLE_DASHED
                : s.line == MapPlan.Line.DOTTED ? Shape.BASIC_LINE_STYLE_DOTTED
                        : Shape.BASIC_LINE_STYLE_SOLID);
        if (!Double.isNaN(s.heightM))
            p.setHeight(s.heightM);
        p.setTitle(s.title);
        if (shapeLabels) {
            // Fallback only (LabelLayer is the normal path). ATAK's polyline
            // renderer shows the center label between tile levels
            // max(minLabelRenderResolution, res(minRenderScale)) (coarse)
            // and maxLabelRenderResolution (fine; 0 = all the way in).
            // The coarse limit is the exact resolution of the label's level
            // (a hair finer, so ATAK's rounding lands on that level).
            double coarse = MapPlan.levelResolution(MapPlan.labelLevel(
                    s.extentM, minPixels)) * 0.999;
            p.setMetaString("centerPointLabel", s.label);
            p.setMinLabelRenderResolution(coarse);
            p.setMaxLabelRenderResolution(0.0);
            // Legacy renderer: shown while map scale >= this (96 dpi).
            p.setMetaDouble("minRenderScale", 0.0254 / (96.0 * coarse));
        } else {
            p.removeMetaData("centerPointLabel");
        }
        p.setMetaString("tfrtak.id", s.tfrId);
        p.setClickable(true);
        p.setEditable(false);
        p.setMovable(false);
        // Our own tap handling (opens the TFR); no ATAK radial menu.
        p.setMetaBoolean("ignoreMenu", true);
        // Never persisted by ATAK or sent to others: the plugin owns these.
        p.setMetaBoolean("archive", false);
        p.setMetaBoolean("nevercot", true);
        p.setMetaBoolean("removable", false);
    }

    /**
     * The "Show labels" switch: hides or shows every TFR label, shapes
     * unchanged. Takes effect at once for our own labels; the fallback
     * shape labels are restyled on the next {@link #apply}.
     */
    public void setLabelsVisible(boolean on) {
        if (labelsOn == on)
            return;
        labelsOn = on;
        if (labels != null)
            labels.setLabelsOn(on);
        if (labels == null)
            drawn.clear(); // fallback: restyle the shapes on the next apply
        refreshLabels();
    }

    public void setVisible(boolean v) {
        group().setVisible(v);
        refreshLabels();
    }

    public void dispose() {
        mapView.removeOnMapMovedListener(mapMoved);
        mapView.removeCallbacks(moveCheck);
        moveQueued = false;
        if (labels != null) {
            labels.dispose();
            labels = null;
        }
        labelsChecked = false;
        if (overlay != null) {
            try {
                mapView.getMapOverlayManager().removeOverlay(overlay);
            } catch (RuntimeException e) {
                Log.e(TAG, "Overlay Manager removal failed", e);
            }
            overlay = null;
        }
        if (group != null) {
            group.removeOnVisibleChangedListener(groupVisListener);
            group.clearItems();
            try {
                mapView.getRootGroup().removeGroup(group);
            } catch (RuntimeException e) {
                Log.e(TAG, "group removal failed", e);
            }
            group = null;
        }
        drawn.clear();
    }
}
