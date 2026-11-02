package com.atakmap.android.tfr.plugin.core;

import java.util.Collections;
import java.util.List;

/** One area of a TFR, with its own altitudes, schedule and outline. */
public final class TfrArea {

    /** Where the drawn outline came from. */
    public enum Source {
        /** Built from the published definition and confirmed against the FAA outline. */
        DEFINITION_CHECKED,
        /** Built from the published definition; the FAA gave no outline to check. */
        DEFINITION,
        /** The FAA's pre-computed outline (definition missing, unreadable or disagreeing). */
        FAA_OUTLINE,
        /** No outline in the FAA data: text-only; read the NOTAM. */
        NONE
    }

    public final String name;
    public final Altitude floor;
    public final Altitude ceiling;
    public final Schedule schedule;
    /** Open ring, or empty when {@link #source} is NONE. */
    public final List<LatLon> outline;
    public final Source source;
    /** Why the outline is what it is (for the detail view); may be empty. */
    public final String geometryNote;

    public TfrArea(String name, Altitude floor, Altitude ceiling,
            Schedule schedule, List<LatLon> outline, Source source,
            String geometryNote) {
        this.name = name == null ? "" : name;
        this.floor = floor;
        this.ceiling = ceiling;
        this.schedule = schedule;
        this.outline = outline == null ? Collections.<LatLon> emptyList()
                : Collections.unmodifiableList(outline);
        this.source = source;
        this.geometryNote = geometryNote == null ? "" : geometryNote;
    }

    public boolean hasOutline() {
        return source != Source.NONE && outline.size() >= 3;
    }
}
