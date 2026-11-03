package com.atakmap.android.tfr.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Invariants over every captured FAA data set (add a new folder under
 * test/resources/fixtures/ whenever new TFR shapes or schedules appear):
 * every TFR parses, with no warnings, and every drawn outline is built
 * from the published definition and confirmed against the FAA's graphic.
 */
public class AllFixtureSetsTest {

    static final String[] SETS = { "2026-11-02", "2026-11-03" };

    @Test
    public void everySetParsesCleanly() throws Exception {
        for (String set : SETS) {
            String base = "/fixtures/" + set + "/";
            TfrListParser.Result list = TfrListParser
                    .parse(Fixtures.read(base + "list.json"));
            assertEquals(set, 0, list.rejected);
            assertTrue(set, list.entries.size() > 0);
            for (TfrListParser.Entry e : list.entries) {
                byte[] xml = Fixtures.read(base + "xml/detail_"
                        + NotamId.fileKey(e.id) + ".xml");
                Tfr t = TfrXmlParser.parse(xml, e.id);
                String where = set + " " + e.id;
                assertTrue(where + " " + t.warnings, t.warnings.isEmpty());
                assertFalse(where, t.text.isEmpty());
                assertEquals(where, Category.of(null, e.type), t.category);
                assertFalse(where, t.areas.isEmpty());
                for (TfrArea a : t.areas) {
                    assertTrue(where + " " + a.source,
                            a.source == TfrArea.Source.DEFINITION_CHECKED
                                    || a.source == TfrArea.Source.NONE);
                    assertFalse(where, a.schedule.uncertain);
                    assertFalse(where, a.schedule.periods.isEmpty());
                }
            }
        }
    }
}
