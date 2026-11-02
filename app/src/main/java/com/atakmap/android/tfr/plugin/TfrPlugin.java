package com.atakmap.android.tfr.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.tfr.plugin.atak.MapLayer;
import com.atakmap.android.tfr.plugin.core.Boundary;
import com.atakmap.android.tfr.plugin.core.Geo;
import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.core.Schedule;
import com.atakmap.android.tfr.plugin.core.Tfr;
import com.atakmap.android.tfr.plugin.core.TfrArea;
import com.atakmap.android.tfr.plugin.engine.ExecutorScheduler;
import com.atakmap.android.tfr.plugin.engine.Snapshot;
import com.atakmap.android.tfr.plugin.engine.TfrEngine;
import com.atakmap.android.tfr.plugin.net.HttpFaaClient;
import com.atakmap.android.tfr.plugin.ui.Format;
import com.atakmap.android.tfr.plugin.ui.MapPlan;
import com.atakmap.android.tfr.plugin.ui.Rows;
import com.atakmap.android.tfr.plugin.ui.TfrPane;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.android.util.NotificationUtil;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * TFR-TAK: FAA Temporary Flight Restrictions on the ATAK map, read directly
 * from tfr.faa.gov. No server of our own in the loop; the last data
 * received is cached on the device.
 */
public final class TfrPlugin implements IPlugin {

    private static final String TAG = "TfrPlugin";
    static final String PREF_SECURITY_ONLY = "tfrtak.securityOnly";
    static final String PREF_SHOW_ENDED = "tfrtak.showEnded";
    static final String PREF_MAP_VISIBLE = "tfrtak.mapVisible";
    static final String PREF_COLLAPSED = "tfrtak.collapsed.";
    static final String PREF_ALERT_NM = "tfrtak.alertNm";
    static final int DEFAULT_ALERT_NM = 50;
    private static final int NOTIFY_BASE = 0x7F70;

    private Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;
    private Pane pane;
    private TfrPane controller;

    private MapView mapView;
    private MapLayer layer;
    private TfrEngine engine;
    private SharedPreferences prefs;
    private MapEventDispatcher.MapEventDispatchListener clickListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Snapshot latest;
    private boolean securityOnly, showEnded, mapVisible;

    public TfrPlugin(IServiceController serviceController) {
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }
        uiService = serviceController.getService(IHostUIService.class);
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(icon(), Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    private Drawable icon() {
        return pluginContext.getResources().getDrawable(R.drawable.ic_launcher,
                pluginContext.getTheme());
    }

    @Override
    public void onStart() {
        mapView = MapView.getMapView();
        prefs = AtakPreferences.getInstance(mapView.getContext()).getSharedPrefs();
        securityOnly = prefs.getBoolean(PREF_SECURITY_ONLY, false);
        showEnded = prefs.getBoolean(PREF_SHOW_ENDED, false);
        mapVisible = prefs.getBoolean(PREF_MAP_VISIBLE, true);
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);

        layer = new MapLayer(mapView);

        clickListener = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                MapItem item = event.getItem();
                String id = item == null ? null : MapPlan.idOf(item.getUID());
                if (id != null && showPane())
                    controller.showDetail(id);
            }
        };
        mapView.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_CLICK,
                clickListener);

        File cache = FileSystemUtils.getItem("tools/tfrtak");
        engine = new TfrEngine(new ExecutorScheduler(),
                new HttpFaaClient(version()), cache);
        engine.setListener(new TfrEngine.Listener() {
            @Override
            public void onSnapshot(final Snapshot s) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onSnapshotMain(s);
                    }
                });
            }
        });
        engine.start();
    }

    private String version() {
        try {
            String v = pluginContext.getString(R.string.versionName);
            int sp = v.indexOf(' ');
            return sp > 0 ? v.substring(0, sp) : v;
        } catch (RuntimeException e) {
            return "dev";
        }
    }

    @Override
    public void onStop() {
        if (engine != null) {
            engine.setListener(null);
            engine.stop();
            engine = null;
        }
        if (uiService != null) {
            if (pane != null && uiService.isPaneVisible(pane))
                uiService.closePane(pane);
            uiService.removeToolbarItem(toolbarItem);
        }
        if (clickListener != null && mapView != null) {
            mapView.getMapEventDispatcher().removeMapEventListener(
                    MapEvent.ITEM_CLICK, clickListener);
            clickListener = null;
        }
        main.removeCallbacksAndMessages(null);
        if (layer != null) {
            layer.dispose();
            layer = null;
        }
        controller = null;
        pane = null;
    }

    private void onSnapshotMain(Snapshot s) {
        if (layer == null)
            return; // stopped
        latest = s;
        redraw();
        if (controller != null)
            controller.update(s);
        if (!s.newIds.isEmpty())
            alertNew(s);
    }

    /** Draw the latest snapshot, or nothing when TFRs are switched off. */
    private void redraw() {
        if (layer == null)
            return;
        try {
            if (mapVisible && latest != null)
                layer.apply(MapPlan.build(latest, securityOnly));
            else
                layer.apply(new ArrayList<MapPlan.Shape>());
        } catch (RuntimeException e) {
            Log.e(TAG, "map update failed", e);
        }
    }

    // ---- alerts -------------------------------------------------------

    /** Notify about newly posted TFRs within the alert distance of self. */
    private void alertNew(Snapshot s) {
        LatLon self = self();
        if (self == null)
            return;
        int nm = prefs.getInt(PREF_ALERT_NM, DEFAULT_ALERT_NM);
        if (nm <= 0)
            return;
        Format f = new Format(TimeZone.getDefault());
        for (String id : s.newIds) {
            Snapshot.Item it = s.find(id);
            if (it == null || it.tfr == null)
                continue;
            Tfr t = it.tfr;
            if (securityOnly && !t.category.isSecurity())
                continue;
            if (t.stateAt(s.nowMs) == Schedule.State.EXPIRED)
                continue;
            double best = Double.NaN;
            for (TfrArea a : t.areas)
                if (a.hasOutline()) {
                    double d = Boundary.distanceTo(a.outline, self);
                    if (Double.isNaN(best) || d < best)
                        best = d;
                }
            if (Double.isNaN(best) || best > nm * Geo.METERS_PER_NM)
                continue;
            Schedule.State st = t.stateAt(s.nowMs);
            String when = st == Schedule.State.ACTIVE ? "active now"
                    : "starts " + f.when(t.nextChange(s.nowMs));
            String title = "New TFR: " + t.category.label + ", "
                    + Format.distance(best)
                    + (best <= 0 ? " your position" : " from you");
            String msg = t.place() + " · FDC " + t.id + " · " + when;
            try {
                NotificationUtil.getInstance().postNotification(
                        NOTIFY_BASE + Math.abs(id.hashCode() % 1000),
                        NotificationUtil.GeneralIcon.STATUS_RED.getID(),
                        NotificationUtil.RED, title, msg, msg);
            } catch (RuntimeException e) {
                Log.e(TAG, "notification failed", e);
            }
        }
    }

    // ---- pane and actions -----------------------------------------------

    private LatLon self() {
        if (mapView == null)
            return null;
        Marker m = mapView.getSelfMarker();
        if (m == null)
            return null;
        GeoPoint p = m.getPoint();
        if (p == null || !p.isValid()
                || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return new LatLon(p.getLatitude(), p.getLongitude());
    }

    private boolean showPane() {
        if (uiService == null || mapView == null)
            return false;
        if (pane == null) {
            controller = new TfrPane(pluginContext, new TfrPane.Actions() {
                @Override
                public void refresh() {
                    if (engine != null)
                        engine.refreshNow();
                }

                @Override
                public void setSecurityOnly(boolean on) {
                    securityOnly = on;
                    prefs.edit().putBoolean(PREF_SECURITY_ONLY, on).apply();
                    redraw();
                }

                @Override
                public void setMapVisible(boolean on) {
                    mapVisible = on;
                    prefs.edit().putBoolean(PREF_MAP_VISIBLE, on).apply();
                    redraw();
                }

                @Override
                public boolean isCollapsed(Rows.Box box) {
                    return prefs.getBoolean(PREF_COLLAPSED + box.name(),
                            box.collapsedByDefault);
                }

                @Override
                public void setCollapsed(Rows.Box box, boolean collapsed) {
                    prefs.edit().putBoolean(PREF_COLLAPSED + box.name(),
                            collapsed).apply();
                }

                @Override
                public void setShowEnded(boolean on) {
                    showEnded = on;
                    prefs.edit().putBoolean(PREF_SHOW_ENDED, on).apply();
                }

                @Override
                public boolean goTo(String id) {
                    return zoomTo(id);
                }

                @Override
                public LatLon self() {
                    return TfrPlugin.this.self();
                }
            }, mapVisible, securityOnly, showEnded);
            pane = new PaneBuilder(controller.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.45D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                    .build();
        }
        if (latest != null)
            controller.update(latest);
        if (!uiService.isPaneVisible(pane)) {
            controller.showList();
            uiService.showPane(pane, null);
        }
        if (engine != null)
            engine.republish();
        return true;
    }

    private boolean zoomTo(String id) {
        Snapshot s = latest;
        if (s == null || mapView == null)
            return false;
        Snapshot.Item it = s.find(id);
        if (it == null || it.tfr == null)
            return false;
        List<GeoPoint> pts = new ArrayList<>();
        for (TfrArea a : it.tfr.areas)
            for (LatLon p : a.outline)
                pts.add(new GeoPoint(p.lat, p.lon));
        if (pts.isEmpty())
            return false;
        return ATAKUtilities.scaleToFit(mapView,
                pts.toArray(new GeoPoint[0]), mapView.getWidth(),
                mapView.getHeight());
    }
}
