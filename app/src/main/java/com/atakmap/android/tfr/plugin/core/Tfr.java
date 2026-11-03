package com.atakmap.android.tfr.plugin.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One TFR NOTAM, parsed from the FAA detail XML. */
public final class Tfr {

    /** "6/4199" */
    public final String id;
    /** Regulation cited, e.g. "91.141", "99.7". */
    public final String cfr;
    public final Category category;
    /** Controlling ARTCC, e.g. "ZBW". */
    public final String facility;
    public final String city;
    public final String state;
    /** The FAA's own short name, e.g. "261102 Rome NY VIP". */
    public final String localName;
    public final long issuedMs;
    /** Overall effective period (UTC); end is {@link Schedule#FOREVER} if none. */
    public final long effectiveMs, expireMs;
    /** The full NOTAM text, as published. The legal authority. */
    public final String text;
    public final List<TfrArea> areas;
    /** Anything the parser couldn't read with certainty. */
    public final List<String> warnings;

    public Tfr(String id, String cfr, Category category, String facility,
            String city, String state, String localName, long issuedMs,
            long effectiveMs, long expireMs, String text, List<TfrArea> areas,
            List<String> warnings) {
        this.id = id;
        this.cfr = cfr == null ? "" : cfr;
        this.category = category;
        this.facility = facility == null ? "" : facility;
        this.city = city == null ? "" : city;
        this.state = state == null ? "" : state;
        this.localName = localName == null ? "" : localName;
        this.issuedMs = issuedMs;
        this.effectiveMs = effectiveMs;
        this.expireMs = expireMs;
        this.text = text == null ? "" : text;
        this.areas = Collections.unmodifiableList(new ArrayList<>(areas));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public boolean hasOutline() {
        for (TfrArea a : areas)
            if (a.hasOutline())
                return true;
        return false;
    }

    /** "Rome, NEW YORK" style place, or the facility. */
    public String place() {
        if (!city.isEmpty() && !state.isEmpty())
            return city + ", " + state;
        if (!city.isEmpty())
            return city;
        if (!state.isEmpty())
            return state;
        return facility;
    }

    /** Overall state: active if any area is active, etc. */
    public Schedule.State stateAt(long t) {
        boolean pending = false, between = false;
        if (areas.isEmpty())
            return t >= expireMs ? Schedule.State.EXPIRED
                    : t < effectiveMs ? Schedule.State.PENDING
                            : Schedule.State.ACTIVE;
        for (TfrArea a : areas) {
            Schedule.State s = a.schedule.stateAt(t);
            if (s == Schedule.State.ACTIVE)
                return Schedule.State.ACTIVE;
            if (s == Schedule.State.PENDING)
                pending = true;
            if (s == Schedule.State.BETWEEN)
                between = true;
        }
        if (between)
            return Schedule.State.BETWEEN;
        if (pending)
            return Schedule.State.PENDING;
        return Schedule.State.EXPIRED;
    }

    /**
     * When the TFR's overall state next changes; -1 if never.
     * Active: when the last of its areas stops being active (following
     * areas whose active time overlaps or touches), so a TFR is never said
     * to end while any area is still in effect. Otherwise: the earliest
     * time any area becomes active.
     */
    public long nextChange(long t) {
        if (areas.isEmpty()) {
            if (t < effectiveMs)
                return effectiveMs;
            return t < expireMs && expireMs != Schedule.FOREVER ? expireMs : -1;
        }
        if (stateAt(t) == Schedule.State.ACTIVE) {
            long end = t;
            for (int guard = 0; guard < 256; guard++) {
                long next = end;
                boolean any = false;
                for (TfrArea a : areas) {
                    if (a.schedule.stateAt(end) != Schedule.State.ACTIVE)
                        continue;
                    any = true;
                    long e = a.schedule.nextChange(end);
                    if (e < 0)
                        return -1; // an active area has no published end
                    next = Math.max(next, e);
                }
                if (!any || next <= end)
                    break;
                end = next;
            }
            return end;
        }
        long best = -1;
        for (TfrArea a : areas) {
            long c = a.schedule.nextChange(t);
            if (c >= 0 && (best < 0 || c < best))
                best = c;
        }
        return best;
    }
}
