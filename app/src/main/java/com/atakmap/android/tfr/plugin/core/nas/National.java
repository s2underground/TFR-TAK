package com.atakmap.android.tfr.plugin.core.nas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which national Command Center events are in force now, from the
 * advisories of the last day. Pure.
 *
 * <p>Rules (conservative: when in doubt, keep showing):
 * <ul>
 * <li>Only {@link Advisory#nationalKind() national} advisories count.</li>
 * <li>Per kind, the newest advisory (date, number) decides: a cancellation
 * ends that kind (shown as "cancelled" for {@link #CANCELLED_SHOW_MS});
 * otherwise it is in force until its EVENT TIME end.</li>
 * <li>Without the advisory text (not downloaded yet), or with no readable
 * end, it counts as in force for {@link #NO_END_MS} after it was sent.</li>
 * <li>Advisories over {@link #MAX_AGE_MS} old are ignored.</li>
 * </ul>
 * The FAA's planned end is not a promise: the event card says "planned to"
 * and "expect update", and the NAS often reissues (111 → 120 on Nov 13).
 */
public final class National {

    public static final long NO_END_MS = 12 * 3600_000L;
    public static final long MAX_AGE_MS = 30 * 3600_000L;
    public static final long CANCELLED_SHOW_MS = 2 * 3600_000L;

    public static final class Event {
        public final Advisory.Kind kind;
        public final Advisory advisory;
        /** Advisory text; null if not downloaded yet. */
        public final Advisory.Text text;
        /** Planned end, ms UTC (EVENT TIME end, else sent + 12 h). */
        public final long endMs;
        /** True: the newest advisory of this kind cancels it. */
        public final boolean cancelled;
        /** Earlier advisories of the same kind still listed (111 → 120). */
        public final int earlier;

        Event(Advisory.Kind kind, Advisory a, Advisory.Text text, long endMs,
                boolean cancelled, int earlier) {
            this.kind = kind;
            this.advisory = a;
            this.text = text;
            this.endMs = endMs;
            this.cancelled = cancelled;
            this.earlier = earlier;
        }
    }

    private National() {
    }

    public interface Texts {
        /** The downloaded text of an advisory, or null. */
        Advisory.Text get(Advisory a);
    }

    public static List<Event> active(List<Advisory> all, Texts texts,
            long now) {
        Map<Advisory.Kind, List<Advisory>> byKind = new EnumMap<>(
                Advisory.Kind.class);
        for (Advisory a : all) {
            Advisory.Kind k = a.nationalKind();
            if (k == null)
                continue;
            if (a.sentMs > 0 && now - a.sentMs > MAX_AGE_MS)
                continue;
            List<Advisory> l = byKind.get(k);
            if (l == null)
                byKind.put(k, l = new ArrayList<>());
            l.add(a);
        }
        List<Event> out = new ArrayList<>();
        for (Map.Entry<Advisory.Kind, List<Advisory>> e : byKind.entrySet()) {
            List<Advisory> l = e.getValue();
            Collections.sort(l, NEWEST_FIRST);
            Advisory newest = l.get(0);
            Advisory.Text t = texts.get(newest);
            if (newest.isCancellation()) {
                if (newest.sentMs > 0
                        && now - newest.sentMs <= CANCELLED_SHOW_MS)
                    out.add(new Event(e.getKey(), newest, t, newest.sentMs,
                            true, l.size() - 1));
                continue;
            }
            long end = t != null && t.eventEndMs > 0 ? t.eventEndMs
                    : newest.sentMs > 0 ? newest.sentMs + NO_END_MS : 0;
            if (end > 0 && end <= now)
                continue;
            out.add(new Event(e.getKey(), newest, t, end, false,
                    l.size() - 1));
        }
        Collections.sort(out, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                if (a.cancelled != b.cancelled)
                    return a.cancelled ? 1 : -1;
                if (a.kind.severe != b.kind.severe)
                    return a.kind.severe ? -1 : 1;
                return NEWEST_FIRST.compare(a.advisory, b.advisory);
            }
        });
        return Collections.unmodifiableList(out);
    }

    static final Comparator<Advisory> NEWEST_FIRST = new Comparator<Advisory>() {
        @Override
        public int compare(Advisory a, Advisory b) {
            if (a.date != b.date)
                return a.date > b.date ? -1 : 1;
            return a.number > b.number ? -1 : a.number < b.number ? 1 : 0;
        }
    };
}
