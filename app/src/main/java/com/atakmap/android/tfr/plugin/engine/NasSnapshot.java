package com.atakmap.android.tfr.plugin.engine;

import com.atakmap.android.tfr.plugin.core.nas.AirportEvent;
import com.atakmap.android.tfr.plugin.core.nas.National;

import java.util.Collections;
import java.util.List;

/** What the NAS monitor knows: airport events and national events. */
public final class NasSnapshot {

    public final long nowMs;
    public final List<AirportEvent> airports;
    /** Last good airport feed (0 = never). */
    public final long airportsOkMs;
    public final String airportsError;
    public final List<National.Event> national;
    /** Last good advisories list (0 = never). */
    public final long advisoriesOkMs;
    public final String advisoriesError;
    /** National events first seen in this poll (not on the first load). */
    public final List<National.Event> newNational;

    public NasSnapshot(long nowMs, List<AirportEvent> airports,
            long airportsOkMs, String airportsError,
            List<National.Event> national, long advisoriesOkMs,
            String advisoriesError, List<National.Event> newNational) {
        this.nowMs = nowMs;
        this.airports = Collections.unmodifiableList(airports);
        this.airportsOkMs = airportsOkMs;
        this.airportsError = airportsError;
        this.national = Collections.unmodifiableList(national);
        this.advisoriesOkMs = advisoriesOkMs;
        this.advisoriesError = advisoriesError;
        this.newNational = Collections.unmodifiableList(newNational);
    }

    /** Older than 30 minutes, or never received. */
    public boolean stale() {
        long ok = Math.min(airportsOkMs, advisoriesOkMs);
        return ok <= 0 || nowMs - ok > 30 * 60_000L;
    }

    /** True when a national ground stop (or ATC zero) is in force. */
    public boolean severeNational() {
        for (National.Event e : national)
            if (!e.cancelled && e.kind.severe)
                return true;
        return false;
    }
}
