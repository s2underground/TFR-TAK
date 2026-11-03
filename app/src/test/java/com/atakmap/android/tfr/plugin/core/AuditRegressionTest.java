package com.atakmap.android.tfr.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.atakmap.android.tfr.plugin.engine.Snapshot;
import com.atakmap.android.tfr.plugin.ui.Format;
import com.atakmap.android.tfr.plugin.ui.Rows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.TimeZone;

/** One test per pre-release audit finding (docs/AUDIT.md). */
public class AuditRegressionTest {

    static final String EVENING = "/fixtures/2026-11-03/";

    static Tfr evening(String id) throws Exception {
        return TfrXmlParser.parse(Fixtures.read(EVENING + "xml/detail_"
                + NotamId.fileKey(id) + ".xml"), id);
    }

    /** H1: a multi-area TFR is active until its LAST active area ends. */
    @Test
    public void multiAreaActiveUntilLastArea() {
        Tfr t = Fixtures.tfr("6/2481");
        long now = Fixtures.utc("2026-11-02T20:00");
        assertEquals(Schedule.State.ACTIVE, t.stateAt(now));
        // Was "until Nov 3 1100Z" (Area E's next start).
        assertEquals(Fixtures.utc("2027-03-14T13:00"), t.nextChange(now));
        Snapshot.Item it = new Snapshot.Item(new TfrListParser.Entry("6/2481",
                "SECURITY", "ZAN", "AK", "", ""), t, null, false, 0);
        Snapshot s = new Snapshot(Collections.singletonList(it), now, now, now,
                null, false, new ArrayList<String>());
        Rows.Row r = Rows.build(s, new Rows.Filter(false, false), null,
                new Format(TimeZone.getTimeZone("UTC"))).get(0);
        assertTrue(r.status, r.status.startsWith("ACTIVE until Mar 14 1300Z"));
    }

    /** H1: back-to-back areas chain; an active area without end = no end. */
    @Test
    public void multiAreaChainsAndOpenEnd() {
        Tfr mayport = Fixtures.tfr("6/4313"); // 4 areas, same daily window
        assertEquals(Fixtures.utc("2026-11-03T06:00"),
                mayport.nextChange(Fixtures.utc("2026-11-02T13:00")));
        Tfr nat = Fixtures.tfr("0/0367");
        assertEquals(-1, nat.nextChange(Fixtures.utc("2026-11-02T13:00")));
    }

    /** H2: an unreadable schedule part never makes the area look ended. */
    @Test
    public void unreadableSchedulePartKeepsAreaActive() throws Exception {
        String orig = new String(Fixtures.detail("6/4028"), StandardCharsets.UTF_8);
        String bad = "<dateExpire>2026-11-07T22:00:00</dateExpire>";
        int i = orig.lastIndexOf(bad);
        String mod = orig.substring(0, i)
                + "<dateExpire>2026-11-07T22:00:00-05:00</dateExpire>"
                + orig.substring(i + bad.length());
        Tfr t = TfrXmlParser.parse(mod.getBytes(StandardCharsets.UTF_8), "6/4028");
        assertTrue(t.areas.get(0).schedule.uncertain);
        assertFalse(t.warnings.isEmpty());
        // Was EXPIRED; the NOTAM runs until 11-07 2200Z.
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-07T18:00")));
        assertEquals(Schedule.State.EXPIRED, t.stateAt(Fixtures.utc("2026-11-07T22:00")));
    }

    /** M5: day codes count on the UTC day or the local day. */
    @Test
    public void weekdayCountsOnUtcOrLocalDay() {
        // "MON 0100-0500Z (1800-2200 PDT the previous evening)": Mon only.
        Schedule.Period p = new Schedule.Period(Fixtures.utc("2026-11-01T00:00"),
                Fixtures.utc("2026-11-30T00:00"), true, 60, 300,
                EnumSet.of(Schedule.Day.MONDAY), -420);
        // Mon Nov 2 0200Z (UTC Monday): active.
        assertTrue(p.activeAt(Fixtures.utc("2026-11-02T02:00")));
        // Tue Nov 3 0200Z = Mon Nov 2 1900 PDT (local Monday): active too.
        assertTrue(p.activeAt(Fixtures.utc("2026-11-03T02:00")));
        // Wed Nov 4 0200Z = Tue local: not active.
        assertFalse(p.activeAt(Fixtures.utc("2026-11-04T02:00")));
        // Same-date windows (Alaska 6/2481 Area E) are unchanged: Wed is off.
        Tfr ak = Fixtures.tfr("6/2481");
        for (TfrArea a : ak.areas)
            if (a.name.equals("Area E"))
                assertEquals(Schedule.State.BETWEEN,
                        a.schedule.stateAt(Fixtures.utc("2026-11-04T12:00")));
        assertEquals(-540, TfrXmlParser.zoneOffsetMinutes("AKST"));
        assertEquals(Integer.MIN_VALUE, TfrXmlParser.zoneOffsetMinutes("XYZ"));
    }

    /** Evening set: start/end dates copied from another NOTAM are ignored. */
    @Test
    public void dailyWindowIgnoresJunkDates() throws Exception {
        Tfr t = evening("6/4687"); // DLY 1100-1800Z, Nov 8 to Nov 9
        assertEquals(Schedule.State.PENDING, t.stateAt(Fixtures.utc("2026-11-04T12:00")));
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-08T11:00")));
        assertEquals(Schedule.State.BETWEEN, t.stateAt(Fixtures.utc("2026-11-08T18:00")));
        assertEquals(Fixtures.utc("2026-11-09T11:00"),
                t.nextChange(Fixtures.utc("2026-11-08T18:00")));
        assertEquals("SFC–FL250", Altitude.range(t.areas.get(0).floor,
                t.areas.get(0).ceiling));
    }

    /** "0 ft MSL" floors (6/4857) read SFC. */
    @Test
    public void zeroMslFloorIsSurface() throws Exception {
        Tfr t = evening("6/4857");
        TfrArea a = t.areas.get(0);
        assertTrue(a.floor.isSurface());
        assertEquals("SFC–2,000 ft MSL", Altitude.range(a.floor, a.ceiling));
    }

    /** L13: an unknown regulation falls back to the list's type word. */
    @Test
    public void categoryFallsBackToListType() throws Exception {
        String x = new String(Fixtures.detail("6/4313"), StandardCharsets.UTF_8)
                .replace("<codeType>99.7</codeType>", "<codeType>99.99</codeType>");
        byte[] b = x.getBytes(StandardCharsets.UTF_8);
        assertEquals(Category.OTHER, TfrXmlParser.parse(b, "6/4313").category);
        assertEquals(Category.SECURITY,
                TfrXmlParser.parse(b, "6/4313", "SECURITY").category);
    }

    /** L9: a byte-order mark doesn't make the list unreadable. */
    @Test
    public void listWithByteOrderMark() throws Exception {
        byte[] list = Fixtures.list();
        byte[] bom = new byte[list.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(list, 0, bom, 3, list.length);
        assertEquals(54, TfrListParser.parse(bom).entries.size());
    }

    /** L10: DOCTYPE anywhere, and UTF-16 input, are refused. */
    @Test
    public void xmlHardening() throws Exception {
        String ok = new String(Fixtures.detail("6/4199"), StandardCharsets.UTF_8);
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 6000; i++)
            pad.append(' ');
        // FAA files have no <?xml?> header: put the DOCTYPE after 6 kB of
        // whitespace, before the root element.
        String body = ok.startsWith("\uFEFF") ? ok.substring(1) : ok;
        String late = pad + "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + body;
        expectRefused(late.getBytes(StandardCharsets.UTF_8));
        expectRefused(ok.getBytes(StandardCharsets.UTF_16));
        expectRefused(ok.getBytes(StandardCharsets.UTF_16LE));
        // Real files (some with a UTF-8 BOM) still parse.
        byte[] bomFile = ("\uFEFF" + body).getBytes(StandardCharsets.UTF_8);
        assertEquals("6/4199", TfrXmlParser.parse(bomFile, "6/4199").id);
        assertEquals(-1, TfrXmlParser.time("2026-11-02T14:15:00" + pad));
    }

    private static void expectRefused(byte[] b) {
        try {
            TfrXmlParser.parse(b, "6/4199");
            fail("accepted");
        } catch (TfrXmlParser.ParseException expected) {
        }
    }

    /** L11: file keys are rebuilt from numbers. */
    @Test
    public void fileKeysFromNumbers() {
        assertEquals("6_4199", NotamId.fileKey("6/4199"));
        assertEquals("0_0367", NotamId.fileKey("0/367"));
        assertEquals("6_12345", NotamId.fileKey("6/12345"));
    }

    /** L8: a TFR with some areas undrawn says so in the list. */
    @Test
    public void partlyDrawnTfrIsFlagged() {
        Tfr full = Fixtures.tfr("6/4313");
        List<TfrArea> areas = new ArrayList<>(full.areas);
        TfrArea a = areas.get(1);
        areas.set(1, new TfrArea(a.name, a.floor, a.ceiling, a.schedule, null,
                TfrArea.Source.NONE, "Outline could not be read (test)."));
        Tfr t = new Tfr(full.id, full.cfr, full.category, full.facility,
                full.city, full.state, full.localName, full.issuedMs,
                full.effectiveMs, full.expireMs, full.text, areas,
                new ArrayList<String>());
        long now = Fixtures.utc("2026-11-02T13:00");
        Snapshot.Item it = new Snapshot.Item(new TfrListParser.Entry(t.id,
                "SECURITY", "", "", "", ""), t, null, false, 0);
        Snapshot s = new Snapshot(Collections.singletonList(it), now, now, now,
                null, false, new ArrayList<String>());
        Rows.Row r = Rows.build(s, new Rows.Filter(false, false), null,
                new Format(TimeZone.getTimeZone("UTC"))).get(0);
        assertTrue(r.notes.toString(),
                r.notes.contains("1 of 4 areas have no map graphic. Read the NOTAM text."));
    }
}
