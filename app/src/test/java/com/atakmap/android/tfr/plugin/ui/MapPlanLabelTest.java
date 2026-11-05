package com.atakmap.android.tfr.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.tfr.plugin.core.LatLon;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Label grouping rules (MapPlan.labels) on synthetic shapes. */
public class MapPlanLabelTest {

    /** A square of half-size d degrees around (lat, lon). */
    static List<LatLon> square(double lat, double lon, double d) {
        List<LatLon> r = new ArrayList<>();
        r.add(new LatLon(lat - d, lon - d));
        r.add(new LatLon(lat - d, lon + d));
        r.add(new LatLon(lat + d, lon + d));
        r.add(new LatLon(lat + d, lon - d));
        r.add(new LatLon(lat - d, lon - d));
        return r;
    }

    static MapPlan.Shape shape(String tfr, int i, String name,
            List<LatLon> o, String range, String state) {
        return new MapPlan.Shape("tfrtak-" + tfr + "-" + i, tfr, name, o, 0,
                0, MapPlan.Line.SOLID, Double.NaN, "Security", range, state,
                "t");
    }

    @Test
    public void nestedAreasWithDifferentAltitudesListEach() {
        List<MapPlan.Shape> plan = new ArrayList<>();
        plan.add(shape("1/1", 1, " Area", square(38.9, -77.0, 0.5),
                "SFC–17,999 ft MSL", "ACTIVE"));
        plan.add(shape("1/1", 2, "", square(38.9, -77.0, 0.1),
                "SFC–3,000 ft AGL", "not active now"));
        List<MapPlan.Label> l = MapPlan.labels(plan);
        assertEquals(1, l.size());
        assertEquals("Security\nArea: SFC–17,999 ft MSL · ACTIVE\n"
                + "Area 2: SFC–3,000 ft AGL · not active now", l.get(0).text);
        assertEquals(plan.get(0).center, l.get(0).anchor);
        assertEquals(plan.get(0).extentM, l.get(0).extentM, 0);
        assertEquals(2, l.get(0).shapeUids.size());
    }

    @Test
    public void separateAreasAndOtherTfrsKeepTheirOwnLabels() {
        List<MapPlan.Shape> plan = new ArrayList<>();
        // Two areas of one TFR, far apart (e.g. two launch sites).
        plan.add(shape("2/2", 1, "A", square(28.5, -80.6, 0.2), "SFC–UNL",
                "ACTIVE"));
        plan.add(shape("2/2", 2, "B", square(34.7, -120.6, 0.2), "SFC–UNL",
                "ACTIVE"));
        // A different TFR inside the first one: labelled on its own.
        plan.add(shape("3/3", 1, "", square(28.5, -80.6, 0.05),
                "SFC–3,000 ft AGL", "ACTIVE"));
        List<MapPlan.Label> l = MapPlan.labels(plan);
        assertEquals(3, l.size());
        for (MapPlan.Label x : l) {
            assertEquals(1, x.shapeUids.size());
            assertTrue(x.text, !x.text.contains("\n"));
        }
    }

    @Test
    public void levelIsTheCoarsestWhereTheAreaSpansTheMinimum() {
        for (double ext = 50; ext < 3e6; ext *= 1.37) {
            for (double px : new double[] { 160, 320, 480 }) {
                int lv = MapPlan.labelLevel(ext, px);
                double res = MapPlan.levelResolution(lv);
                if (lv > 0 && lv < 30) {
                    assertTrue(ext / res >= px - 1e-6);
                    // One level coarser would be too early.
                    assertTrue(ext / MapPlan.levelResolution(lv - 1) < px);
                }
            }
        }
    }
}
