package com.atakmap.android.tfr.plugin.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * When a TFR area is in effect, from the FAA XML {@code ScheduleGroup}s.
 *
 * <p>Verified against the NOTAM text of the Nov 2, 2026 fixtures: every
 * date-time in the XML is UTC, whatever {@code codeTimeZone} says (that
 * field names the local zone the text also quotes). A group with
 * {@code isTimeSeparate=TRUE} is a daily window: from {@code dateEffective}
 * to {@code dateExpire}, active each day from the time-of-day of
 * {@code startTime} to that of {@code endTime} (the dates in those two
 * fields are meaningless), crossing midnight when the end is earlier than
 * the start. Optional {@code dayCode}s on the area limit which days a
 * window may start on ("Daily" or day names). Whether those are UTC or
 * local days isn't documented, so a window counts if either its UTC day or
 * its local day (NOTAM time zone) is listed: this can only show a
 * restriction on more days than published, never fewer.
 *
 * <p>Anything that can't be read with certainty makes the schedule
 * {@link #uncertain}: it is then treated as active from its first start to
 * its last end (the parser adds the NOTAM's overall period), so a doubtful
 * schedule can never hide a restriction.
 */
public final class Schedule {

    public static final long FOREVER = Long.MAX_VALUE;
    private static final long DAY = 86400000L;
    private static final long MIN = 60000L;

    /** One effective period, optionally a daily window. */
    public static final class Period {
        public final long startMs;
        /** {@link #FOREVER} when the NOTAM has no end. */
        public final long endMs;
        public final boolean daily;
        /** Minutes after 0000Z the daily window opens/closes. */
        public final int dailyStartMin, dailyEndMin;
        /** Days a daily window may start on; empty = every day. */
        public final Set<Day> days;
        /** UTC offset (min) of the NOTAM's local zone, for {@link #days}. */
        public final int zoneOffsetMin;

        public Period(long startMs, long endMs) {
            this(startMs, endMs, false, 0, 0, EnumSet.noneOf(Day.class), 0);
        }

        public Period(long startMs, long endMs, boolean daily,
                int dailyStartMin, int dailyEndMin, Set<Day> days) {
            this(startMs, endMs, daily, dailyStartMin, dailyEndMin, days, 0);
        }

        public Period(long startMs, long endMs, boolean daily,
                int dailyStartMin, int dailyEndMin, Set<Day> days,
                int zoneOffsetMin) {
            this.zoneOffsetMin = zoneOffsetMin;
            this.startMs = startMs;
            this.endMs = endMs;
            this.daily = daily;
            this.dailyStartMin = dailyStartMin;
            this.dailyEndMin = dailyEndMin;
            this.days = days.isEmpty() ? EnumSet.noneOf(Day.class)
                    : EnumSet.copyOf(days);
        }

        /** Window length in minutes (a window may cross midnight). */
        int windowMinutes() {
            int len = dailyEndMin - dailyStartMin;
            return len <= 0 ? len + 1440 : len;
        }

        /** May the window starting on this UTC day run? (UTC or local day.) */
        boolean startsOn(long dayStartMs) {
            if (days.isEmpty())
                return true;
            if (days.contains(Day.ofEpochDay(Math.floorDiv(dayStartMs, DAY))))
                return true;
            long localStart = dayStartMs + dailyStartMin * MIN
                    + zoneOffsetMin * MIN;
            return days.contains(Day.ofEpochDay(Math.floorDiv(localStart, DAY)));
        }

        /** Is the period in effect at t? */
        public boolean activeAt(long t) {
            if (t < startMs || t >= endMs)
                return false;
            if (!daily)
                return true;
            long day0 = Math.floorDiv(t, DAY) * DAY;
            // The window may have started today or (crossing midnight) yesterday.
            for (long d = day0 - DAY; d <= day0; d += DAY) {
                if (!startsOn(d))
                    continue;
                long ws = d + dailyStartMin * MIN;
                long we = ws + windowMinutes() * MIN;
                if (t >= ws && t < we)
                    return true;
            }
            return false;
        }

        /** Next moment at or after t when the period becomes active; -1 if never. */
        public long nextStart(long t) {
            if (t >= endMs)
                return -1;
            if (!daily)
                return t < startMs ? startMs : t;
            long from = Math.max(t, startMs);
            long day0 = Math.floorDiv(from, DAY) * DAY;
            for (int i = -1; i < 9; i++) {
                long d = day0 + i * DAY;
                if (!startsOn(d))
                    continue;
                long ws = d + dailyStartMin * MIN;
                long we = ws + windowMinutes() * MIN;
                // An open window that began before startMs opens at startMs.
                long open = Math.max(ws, startMs);
                long close = Math.min(we, endMs);
                if (open >= close)
                    continue;
                if (from < close)
                    return Math.max(open, from);
            }
            return -1;
        }

        /** End of the active stretch containing t (t must be active). */
        long currentEnd(long t) {
            if (!daily)
                return endMs;
            long day0 = Math.floorDiv(t, DAY) * DAY;
            for (long d = day0 - DAY; d <= day0; d += DAY) {
                if (!startsOn(d))
                    continue;
                long ws = d + dailyStartMin * MIN;
                long we = ws + windowMinutes() * MIN;
                if (t >= ws && t < we)
                    return Math.min(we, endMs);
            }
            return endMs;
        }
    }

    /** UTC weekday. */
    public enum Day {
        MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

        static Day ofEpochDay(long epochDay) {
            // 1970-01-01 was a Thursday.
            int i = (int) Math.floorMod(epochDay + 3, 7L);
            return values()[i];
        }

        /** "Tuesday", "TUE", ... ; null if not a day name. */
        public static Day parse(String s) {
            if (s == null)
                return null;
            String u = s.trim().toUpperCase(java.util.Locale.US);
            if (u.length() < 3)
                return null;
            for (Day d : values())
                if (d.name().startsWith(u.substring(0, 3))
                        && d.name().startsWith(u))
                    return d;
            return null;
        }
    }

    public enum State {
        /** In effect now. */
        ACTIVE,
        /** Published, first period not started yet. */
        PENDING,
        /** Between daily windows or periods; will be active again. */
        BETWEEN,
        /** All periods have ended. */
        EXPIRED
    }

    public final List<Period> periods;
    /** Some part couldn't be read with certainty: treated as always active. */
    public final boolean uncertain;

    public Schedule(List<Period> periods, boolean uncertain) {
        this.periods = Collections.unmodifiableList(new ArrayList<>(periods));
        this.uncertain = uncertain;
    }

    public long firstStart() {
        long m = FOREVER;
        for (Period p : periods)
            m = Math.min(m, p.startMs);
        return m;
    }

    public long lastEnd() {
        long m = Long.MIN_VALUE;
        for (Period p : periods)
            m = Math.max(m, p.endMs);
        return m;
    }

    public State stateAt(long t) {
        if (periods.isEmpty())
            return uncertain ? State.ACTIVE : State.EXPIRED;
        if (t >= lastEnd())
            return State.EXPIRED;
        if (uncertain)
            return t < firstStart() ? State.PENDING : State.ACTIVE;
        for (Period p : periods)
            if (p.activeAt(t))
                return State.ACTIVE;
        if (t < firstStart())
            return State.PENDING;
        return nextChange(t) < 0 ? State.EXPIRED : State.BETWEEN;
    }

    /**
     * The next moment the state changes: when ACTIVE, the end of the current
     * stretch; otherwise the next start. -1 if nothing more will happen.
     */
    public long nextChange(long t) {
        if (periods.isEmpty())
            return -1;
        if (uncertain) {
            if (t < firstStart())
                return firstStart();
            long e = lastEnd();
            return t < e && e != FOREVER ? e : -1;
        }
        boolean active = false;
        long end = -1;
        for (Period p : periods) {
            if (p.activeAt(t)) {
                active = true;
                end = Math.max(end, p.currentEnd(t));
            }
        }
        if (active) {
            // Adjacent periods (back-to-back) keep it active: follow the chain.
            for (int guard = 0; guard < 64 && end != FOREVER; guard++) {
                long next = end;
                boolean extended = false;
                for (Period p : periods) {
                    if (p.activeAt(next)) {
                        long e2 = p.currentEnd(next);
                        if (e2 > end) {
                            end = e2;
                            extended = true;
                        }
                    }
                }
                if (!extended)
                    break;
            }
            return end == FOREVER ? -1 : end;
        }
        long best = -1;
        for (Period p : periods) {
            long s = p.nextStart(t);
            if (s >= 0 && (best < 0 || s < best))
                best = s;
        }
        return best;
    }
}
