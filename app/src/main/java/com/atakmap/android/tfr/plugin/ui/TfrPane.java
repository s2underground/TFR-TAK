package com.atakmap.android.tfr.plugin.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.coremap.log.Log;
import com.atakmap.android.tfr.plugin.core.nas.AirportEvent;
import com.atakmap.android.tfr.plugin.core.nas.National;
import com.atakmap.android.tfr.plugin.engine.NasSnapshot;
import com.atakmap.android.tfr.plugin.engine.Snapshot;

import java.util.List;
import java.util.TimeZone;

/**
 * The TFR-TAK pane (1.1): header, three tabs (Airspace · TFRs · NOTAMs), a
 * national-alert bar, and detail pages. Look = the S2 plugins' charcoal
 * "instrument panel" ({@link Theme}). Built in code; renders what
 * {@link Rows} and {@link NasRows} compute. Every entry point is guarded:
 * a display error is shown, never thrown into ATAK. Main thread only.
 */
public final class TfrPane {

    private static final String TAG = "TfrPane";

    public interface Actions {
        void refresh();

        void setSecurityOnly(boolean on);

        void setShowEnded(boolean on);

        /** Show or remove every TFR shape on the map. */
        void setMapVisible(boolean on);

        /** Show or hide the TFR labels (shapes stay). */
        void setLabelsVisible(boolean on);

        boolean isCollapsed(Rows.Box box);

        void setCollapsed(Rows.Box box, boolean collapsed);

        /** Zoom the map to a TFR; false if it has nothing drawn. */
        boolean goTo(String id);

        /** Pan the map to a point. */
        void panTo(double lat, double lon);

        /** Current self position, or null if unknown. */
        LatLon self();

        /** Remember the open tab. */
        void setTab(int tab);
    }

    public static final int TAB_AIRSPACE = 0, TAB_TFRS = 1, TAB_NOTAMS = 2;

    private final Context ctx;
    private final Actions actions;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final TextView headerStatus;
    private final LinearLayout tabs, nationalBar, header;
    private final TextView[] tabViews = new TextView[3];

    private Snapshot snap;
    private NasSnapshot nas;
    private int tab;
    private boolean onMap, labels, securityOnly, showEnded;
    /** Detail page: a TFR id, or an airport event key; null = tab page. */
    private String detailTfr;
    private AirportEvent detailAirport;
    private double detailAirportDist = Double.NaN;
    private String lastSig;

    public TfrPane(Context pluginContext, Actions actions, boolean onMap,
            boolean labels, boolean secOnly, boolean ended, int tab) {
        this.ctx = pluginContext;
        this.actions = actions;
        this.onMap = onMap;
        this.labels = labels;
        this.securityOnly = secOnly;
        this.showEnded = ended;
        this.tab = tab >= 0 && tab <= 2 ? tab : TAB_AIRSPACE;

        scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(Theme.BG);
        scroll.setFillViewport(true);
        LinearLayout root = vbox();
        int pad = dp(12);
        root.setPadding(pad, dp(8), pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- header: title, data age, refresh
        header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("TFR-TAK", 18, Theme.TEXT, true);
        title.setLetterSpacing(0.15f);
        header.addView(title);
        headerStatus = mono("", 11, Theme.DIM);
        headerStatus.setPadding(dp(10), 0, 0, 0);
        header.addView(headerStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = squareButton("↻");
        refresh.setContentDescription("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guard("refresh", new Runnable() {
                    @Override
                    public void run() {
                        TfrPane.this.actions.refresh();
                    }
                });
            }
        });
        header.addView(refresh);
        root.addView(header);

        // ---- tabs
        tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(8), 0, 0);
        String[] names = { "AIRSPACE", "TFRS", "NOTAMS" };
        for (int i = 0; i < 3; i++) {
            final int t = i;
            TextView tv = text(names[i], 13, Theme.TEXT, true);
            tv.setGravity(Gravity.CENTER);
            tv.setLetterSpacing(0.1f);
            tv.setMinHeight(dp(52));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    guard("tab", new Runnable() {
                        @Override
                        public void run() {
                            showTab(t);
                        }
                    });
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(3), 0, i == 2 ? 0 : dp(3), 0);
            tabs.addView(tv, lp);
            tabViews[i] = tv;
        }
        root.addView(tabs);

        // ---- national alert bar (any tab)
        nationalBar = vbox();
        nationalBar.setPadding(0, dp(8), 0, 0);
        root.addView(nationalBar);

        content = vbox();
        content.setPadding(0, dp(4), 0, 0);
        root.addView(content);
    }

    public View getView() {
        return scroll;
    }

    public void update(Snapshot s) {
        snap = s;
        render(false);
    }

    public void updateNas(NasSnapshot s) {
        nas = s;
        render(false);
    }

    public void showTab(int t) {
        tab = t;
        detailTfr = null;
        detailAirport = null;
        actions.setTab(t);
        render(true);
        scroll.scrollTo(0, 0);
    }

    public void showDetail(String id) {
        tab = TAB_TFRS;
        detailTfr = id;
        detailAirport = null;
        render(true);
        scroll.scrollTo(0, 0);
    }

    /** Back from a detail page; false if already on a tab page. */
    public boolean back() {
        if (detailTfr == null && detailAirport == null)
            return false;
        detailTfr = null;
        detailAirport = null;
        render(true);
        return true;
    }

    // ---- rendering -------------------------------------------------------

    private void render(final boolean force) {
        guard("render", new Runnable() {
            @Override
            public void run() {
                renderImpl(force);
            }
        });
    }

    private void renderImpl(boolean force) {
        Format f = new Format(TimeZone.getDefault());
        headerStatus.setText(headerLine(f));
        headerStatus.setTextColor(
                (snap != null && Rows.statusIsWarning(snap))
                        || (nas != null && nas.stale()) ? Theme.CAUTION
                                : Theme.DIM);
        renderTabs();
        renderNationalBar(f);

        // Rebuild the page only when what it shows changed.
        String sig = signature();
        if (!force && sig.equals(lastSig))
            return;
        lastSig = sig;
        content.removeAllViews();
        if (detailTfr != null)
            tfrDetail(f);
        else if (detailAirport != null)
            airportDetail(f);
        else if (tab == TAB_AIRSPACE)
            airspacePage(f);
        else if (tab == TAB_TFRS)
            tfrPage(f);
        else
            notamPage();
        content.addView(footer());
    }

    private String headerLine(Format f) {
        StringBuilder b = new StringBuilder();
        long now = snap != null ? snap.nowMs
                : nas != null ? nas.nowMs : System.currentTimeMillis();
        b.append(f.zulu(now));
        if (snap != null && snap.lastListOkMs > 0)
            b.append(" · TFRs ").append(Format.ago(snap.nowMs, snap.lastListOkMs));
        if ((snap != null && Rows.statusIsWarning(snap))
                || (nas != null && nas.stale()))
            b.append(" · STALE");
        return b.toString();
    }

    private void renderTabs() {
        String[] subs = { airspaceTag(), tfrTag(), "SOON" };
        int[] colors = { nas != null && nas.severeNational() ? Theme.STOP
                : nas != null && !nas.national.isEmpty() ? Theme.CAUTION
                        : Theme.DIM, Theme.DIM, Theme.DIM };
        String[] names = { "AIRSPACE", "TFRS", "NOTAMS" };
        for (int i = 0; i < 3; i++) {
            TextView tv = tabViews[i];
            boolean sel = i == tab;
            tv.setText(names[i] + "\n" + subs[i]);
            tv.setTextColor(sel ? Theme.BG : Theme.TEXT);
            tv.setBackground(sel ? Theme.fill(Theme.TEXT, dp(4))
                    : Theme.panel(colors[i], dp(4), dp(1), dp(3)));
        }
    }

    private String airspaceTag() {
        if (nas == null)
            return "…";
        if (nas.severeNational())
            return "NAT STOP";
        if (!nas.national.isEmpty())
            return "NATIONAL";
        int gs = 0;
        for (AirportEvent e : nas.airports)
            if (e.type == AirportEvent.Type.GROUND_STOP)
                gs++;
        return gs > 0 ? gs + " GS" : nas.airports.size() + " EVENTS";
    }

    private String tfrTag() {
        if (snap == null)
            return "…";
        int near = 0;
        LatLon self = actions.self();
        if (self != null) {
            for (Rows.Row r : Rows.build(snap, new Rows.Filter(false, false),
                    self, new Format(TimeZone.getDefault())))
                if (!r.status.startsWith("Ended") && r.distanceM >= 0
                        && r.distanceM <= 50 * 1852.0)
                    near++;
            return near + " NEAR";
        }
        return snap.items.size() + " LISTED";
    }

    private void renderNationalBar(Format f) {
        nationalBar.removeAllViews();
        if (nas == null || tab == TAB_AIRSPACE && detailAirport == null)
            return; // the Airspace page shows the full cards
        for (National.Event e : nas.national) {
            if (e.cancelled)
                continue;
            final int c = e.kind.severe ? Theme.STOP : Theme.CAUTION;
            TextView bar = text("●  " + NasRows.title(e) + "  ·  "
                    + (e.text != null && e.text.eventStartMs > 0
                            ? "since " + f.zulu(e.text.eventStartMs)
                            : "sent " + f.zulu(e.advisory.sentMs))
                    + "  ›", 13, Theme.TEXT, true);
            bar.setPadding(dp(10), dp(10), dp(10), dp(10));
            bar.setBackground(Theme.alert(c, dp(4), dp(1)));
            bar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    guard("national", new Runnable() {
                        @Override
                        public void run() {
                            showTab(TAB_AIRSPACE);
                        }
                    });
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(4));
            nationalBar.addView(bar, lp);
        }
    }

    private String signature() {
        StringBuilder b = new StringBuilder();
        b.append(tab).append(detailTfr).append(detailAirport == null ? ""
                : detailAirport.key()).append(onMap).append(labels)
                .append(securityOnly).append(showEnded).append('|');
        if (detailTfr != null || detailAirport != null)
            return b.append(snap == null ? 0 : snap.nowMs / 60000)
                    .append(nas == null ? 0 : nas.nowMs).toString();
        if (tab == TAB_AIRSPACE && nas != null) {
            b.append(nas.airportsOkMs).append(nas.advisoriesOkMs)
                    .append(nas.airportsError).append(nas.advisoriesError);
            for (National.Event e : nas.national)
                b.append(e.advisory.key()).append(e.text != null)
                        .append(e.cancelled);
            b.append(nas.airports.size()).append(actions.self() == null);
        } else if (tab == TAB_TFRS && snap != null) {
            Rows.Filter filter = new Rows.Filter(securityOnly, showEnded);
            for (Rows.Section sec : Rows.sections(Rows.build(snap, filter,
                    actions.self(), new Format(TimeZone.getDefault())))) {
                b.append(sec.box).append(actions.isCollapsed(sec.box)).append(':');
                for (Rows.Row r : sec.rows)
                    b.append(r.id).append(r.status).append(r.distance)
                            .append(r.notes.size()).append('|');
            }
            b.append(Rows.warningLine(snap));
        }
        return b.toString();
    }

    // ---- Airspace tab ------------------------------------------------------

    private void airspacePage(Format f) {
        content.addView(dimLine(NasRows.status(nas, f)));
        if (nas != null && nas.advisoriesError != null)
            content.addView(warn(nas.advisoriesError));
        if (nas != null && nas.airportsError != null)
            content.addView(warn(nas.airportsError));

        content.addView(section("NATIONAL · FAA COMMAND CENTER"));
        if (nas == null || nas.advisoriesOkMs <= 0 && nas.national.isEmpty())
            content.addView(dimLine(nas == null ? "Checking…"
                    : "Command Center advisories not received yet."));
        else if (nas.national.isEmpty())
            content.addView(dimLine(
                    "No national ground stop or other nation-wide event in force."));
        else
            for (National.Event e : nas.national)
                content.addView(nationalCard(e, f));

        List<NasRows.AirportRow> rows = NasRows.airports(nas,
                actions.self());
        content.addView(section("AIRPORTS · " + (actions.self() != null
                ? "NEAREST FIRST" : "MOST SERIOUS FIRST") + " · "
                + rows.size()));
        if (nas != null && nas.airportsOkMs > 0 && rows.isEmpty())
            content.addView(dimLine(
                    "No ground stops, delay programs, closures or delays reported."));
        int shown = 0;
        for (NasRows.AirportRow r : rows) {
            if (shown++ >= 150)
                break;
            content.addView(airportRow(r));
        }
    }

    private View nationalCard(National.Event e, Format f) {
        int c = e.cancelled ? Theme.GO : e.kind.severe ? Theme.STOP
                : Theme.CAUTION;
        LinearLayout card = vbox();
        card.setBackground(Theme.alert(c, dp(4), dp(1)));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(chip(NasRows.state(e, f), c));
        TextView t = text(NasRows.title(e), 16, Theme.TEXT, true);
        t.setPadding(dp(8), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);
        TextView w = mono(NasRows.window(e, f), 13, Theme.TEXT);
        w.setPadding(0, dp(6), 0, dp(6));
        card.addView(w);
        TextView body = mono(NasRows.body(e), 12, Theme.TEXT);
        body.setTextIsSelectable(true);
        body.setBackground(Theme.fill(0xFF0C0E10, dp(4)));
        body.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.addView(body);
        TextView src = mono(NasRows.source(e, f), 11, Theme.DIM2);
        src.setPadding(0, dp(6), 0, 0);
        card.addView(src);
        return spaced(card, 6);
    }

    private View airportRow(final NasRows.AirportRow r) {
        final AirportEvent e = r.event;
        int c = e.type == AirportEvent.Type.GROUND_STOP
                || e.type == AirportEvent.Type.CLOSED && !e.partial
                        ? Theme.STOP
                        : Theme.CAUTION;
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.panel(Theme.BORDER, dp(4), dp(1), 0));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setMinimumHeight(dp(48));
        TextView id = mono(e.airport, 15, Theme.TEXT);
        id.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        id.setMinWidth(dp(48));
        row.addView(id);
        View ch = chip(e.type.tag, c);
        row.addView(ch);
        LinearLayout mid = vbox();
        mid.setPadding(dp(10), 0, dp(6), 0);
        mid.addView(text(e.what, 13, Theme.TEXT, true));
        String reason = NasRows.reason(e);
        if (!reason.isEmpty())
            mid.addView(mono(reason, 11, Theme.DIM2));
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(mono(NasRows.distance(r.distanceM), 12, Theme.TEXT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guard("airport", new Runnable() {
                    @Override
                    public void run() {
                        detailAirport = e;
                        detailAirportDist = r.distanceM;
                        detailTfr = null;
                        render(true);
                        scroll.scrollTo(0, 0);
                    }
                });
            }
        });
        return spaced(row, 4);
    }

    private void airportDetail(Format f) {
        final AirportEvent e = detailAirport;
        LinearLayout bar = new LinearLayout(ctx);
        bar.addView(button("‹  Airspace", false, new Runnable() {
            @Override
            public void run() {
                back();
            }
        }));
        if (!Double.isNaN(e.lat))
            bar.addView(button("Show on map", false, new Runnable() {
                @Override
                public void run() {
                    actions.panTo(e.lat, e.lon);
                }
            }));
        content.addView(bar);
        TextView t = text(NasRows.detail(e, detailAirportDist, f), 14,
                Theme.TEXT, false);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(10), 0, 0);
        content.addView(t);
    }

    // ---- TFRs tab ----------------------------------------------------------

    private void tfrPage(Format f) {
        if (snap == null) {
            content.addView(dimLine("Starting…"));
            return;
        }
        TextView st = mono(Rows.statusLine(snap, f), 12,
                Rows.statusIsWarning(snap) ? Theme.CAUTION : Theme.DIM);
        st.setPadding(0, dp(6), 0, dp(2));
        content.addView(st);
        String w = Rows.warningLine(snap);
        if (w != null)
            content.addView(warn(w));

        // Map switches
        content.addView(toggleRow("TFR shapes on the map", onMap, true,
                new Runnable() {
                    @Override
                    public void run() {
                        onMap = !onMap;
                        actions.setMapVisible(onMap);
                        render(true);
                    }
                }));
        content.addView(toggleRow("Labels", labels, onMap, new Runnable() {
            @Override
            public void run() {
                labels = !labels;
                actions.setLabelsVisible(labels);
                render(true);
            }
        }));

        // Filters
        LinearLayout chips = new LinearLayout(ctx);
        chips.setPadding(0, dp(6), 0, dp(2));
        chips.addView(choice("ALL TYPES", !securityOnly, new Runnable() {
            @Override
            public void run() {
                securityOnly = false;
                actions.setSecurityOnly(false);
                render(true);
            }
        }), weight());
        chips.addView(choice("SECURITY & VIP", securityOnly, new Runnable() {
            @Override
            public void run() {
                securityOnly = true;
                actions.setSecurityOnly(true);
                render(true);
            }
        }), weight());
        chips.addView(choice("+ ENDED", showEnded, new Runnable() {
            @Override
            public void run() {
                showEnded = !showEnded;
                actions.setShowEnded(showEnded);
                render(true);
            }
        }), weight());
        content.addView(chips);

        Rows.Filter filter = new Rows.Filter(securityOnly, showEnded);
        List<Rows.Row> rows = Rows.build(snap, filter, actions.self(), f);
        if (snap.items.isEmpty() && snap.lastListOkMs > 0) {
            content.addView(dimLine("The FAA lists no TFRs."));
            return;
        }
        for (Rows.Section sec : Rows.sections(rows))
            sectionView(sec);
    }

    private void sectionView(final Rows.Section sec) {
        final boolean collapsed = actions.isCollapsed(sec.box);
        TextView head = text((collapsed ? "▸  " : "▾  ")
                + sec.box.title.toUpperCase(java.util.Locale.US) + " · "
                + sec.rows.size(), 12, Theme.DIM, true);
        head.setLetterSpacing(0.12f);
        head.setPadding(0, dp(12), 0, dp(6));
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guard("section", new Runnable() {
                    @Override
                    public void run() {
                        actions.setCollapsed(sec.box, !collapsed);
                        render(true);
                    }
                });
            }
        });
        content.addView(head);
        if (collapsed)
            return;
        if (sec.rows.isEmpty())
            content.addView(dimLine(sec.box == Rows.Box.ACTIVE
                    ? "No TFR with a map graphic is active now."
                    : "None published."));
        for (Rows.Row r : sec.rows)
            content.addView(tfrRow(r));
    }

    private View tfrRow(final Rows.Row r) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean active = r.statusColor == Rows.ACTIVE_COLOR;
        row.setBackground(Theme.leftEdge(r.category.color, active,
                dp(4), dp(4)));
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        LinearLayout col = vbox();
        col.addView(text(r.title, 14, Theme.TEXT, true));
        col.addView(mono(r.status, 12, r.statusColor == Rows.ACTIVE_COLOR
                ? Theme.STOP
                : r.statusColor == Rows.PENDING_COLOR ? Theme.CAUTION
                        : Theme.DIM2));
        col.addView(text(r.subtitle, 12, Theme.DIM2, false));
        for (String n : r.notes)
            col.addView(text(n, 12, Theme.CAUTION, false));
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout right = vbox();
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (!r.distance.isEmpty())
            right.addView(mono(r.distance, 13, Theme.TEXT));
        if (r.hasOutline)
            right.addView(button("MAP", false, new Runnable() {
                @Override
                public void run() {
                    actions.goTo(r.id);
                }
            }));
        row.addView(right);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guard("tfr", new Runnable() {
                    @Override
                    public void run() {
                        showDetail(r.id);
                    }
                });
            }
        });
        return spaced(row, 4);
    }

    private void tfrDetail(Format f) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.addView(button("‹  All TFRs", false, new Runnable() {
            @Override
            public void run() {
                back();
            }
        }));
        Snapshot.Item it = snap == null ? null : snap.find(detailTfr);
        final String id = detailTfr;
        if (it != null && it.tfr != null && it.tfr.hasOutline())
            bar.addView(button("Show on map", false, new Runnable() {
                @Override
                public void run() {
                    actions.goTo(id);
                }
            }));
        content.addView(bar);
        TextView t = text(it == null ? "FDC " + detailTfr
                + " is no longer in the FAA list."
                : Rows.detail(it, snap.nowMs, f), 14, Theme.TEXT, false);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(10), 0, 0);
        content.addView(t);
    }

    // ---- NOTAMs tab ----------------------------------------------------------

    private void notamPage() {
        content.addView(section("NOTAMS · COMING IN 1.2"));
        LinearLayout card = vbox();
        card.setBackground(Theme.panel(Theme.BORDER, dp(4), dp(1), 0));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(text("Critical NOTAMs near you and nation-wide (GPS "
                + "interference, ATC and navaid outages, airport closures, "
                + "airspace) are planned for the next version.", 14,
                Theme.TEXT, false));
        TextView how = text("They come from the FAA's NOTAM service, which "
                + "needs a free key of your own: email NOTAMS@faa.gov to "
                + "request a client ID and secret. You will be able to enter "
                + "them here; they will stay on this device.", 13, Theme.DIM2,
                false);
        how.setPadding(0, dp(8), 0, 0);
        card.addView(how);
        content.addView(card);
        content.addView(dimLine("Until then: TFRs (TFRs tab) and national and "
                + "airport status (Airspace tab) need no key."));
    }

    // ---- view helpers ----------------------------------------------------------

    private View footer() {
        TextView t = text("For awareness only. Not for navigation or flight "
                + "planning. The NOTAM and advisory text is the authority. "
                + "Verify at tfr.faa.gov, nasstatus.faa.gov or with Flight "
                + "Service (1-800-WX-BRIEF) before any flight, including "
                + "drones.", 11, Theme.DIM, false);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private View section(String s) {
        TextView t = text(s, 11, Theme.DIM, true);
        t.setLetterSpacing(0.15f);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private TextView dimLine(String s) {
        TextView t = text(s, 13, Theme.DIM2, false);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView warn(String s) {
        TextView t = text(s, 13, Theme.CAUTION, false);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private View toggleRow(String label, boolean on, boolean enabled,
            final Runnable click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.panel(Theme.BORDER, dp(4), dp(1), 0));
        row.setPadding(dp(12), dp(4), dp(6), dp(4));
        row.setMinimumHeight(dp(48));
        TextView l = text(label, 14, enabled ? Theme.TEXT : Theme.DIM, true);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView b = button(on ? "ON" : "OFF", on, click);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.4f);
        b.setMinWidth(dp(72));
        row.addView(b);
        return spaced(row, 4);
    }

    private TextView choice(String label, boolean selected,
            final Runnable click) {
        TextView b = button(label, selected, click);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(4), 0);
        return lp;
    }

    /** Button in the house style: outlined, or inverted when selected. */
    private TextView button(String label, boolean selected,
            final Runnable click) {
        TextView b = text(label, 13, selected ? Theme.BG : Theme.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.06f);
        b.setMinHeight(dp(44));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(selected ? Theme.fill(Theme.TEXT, dp(4))
                : Theme.panel(Theme.BORDER, dp(4), dp(1), 0));
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guard("button", click);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), dp(6), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView squareButton(String glyph) {
        TextView b = text(glyph, 20, Theme.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Theme.panel(Theme.BORDER, dp(4), dp(1), 0));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        b.setClickable(true);
        return b;
    }

    private TextView chip(String label, int color) {
        TextView c = text(label, 10, Theme.BG, true);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setPadding(dp(5), dp(2), dp(5), dp(2));
        c.setBackground(Theme.fill(color, dp(2)));
        return c;
    }

    private View spaced(View v, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(bottomDp));
        v.setLayoutParams(lp);
        return v;
    }

    private void guard(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "display error while " + what, e);
            try {
                content.removeAllViews();
                content.addView(warn("Display error while " + what + ": "
                        + e.getClass().getSimpleName()
                        + ". Please report it."));
                lastSig = null;
            } catch (RuntimeException ignored) {
                // nothing more we can do safely
            }
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView mono(String s, float sp, int color) {
        TextView t = text(s, sp, color, false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return t;
    }

    /** House palette (WX-TAK "C", charcoal instrument panel). */
    static final class Theme {
        static final int BG = 0xFF111316;
        static final int PANEL = 0xFF16191D;
        static final int BORDER = 0xFF2B3138;
        static final int TEXT = 0xFFE6E9ED;
        static final int DIM = 0xFF8D96A1;
        static final int DIM2 = 0xFFC3CAD2;
        static final int GO = 0xFF5FB37A;
        static final int CAUTION = 0xFFD9A441;
        static final int STOP = 0xFFD9615A;

        static GradientDrawable fill(int color, int radius) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            d.setCornerRadius(radius);
            return d;
        }

        /** Panel with a 1 px border; a coloured border when topPx > 0. */
        static GradientDrawable panel(int border, int radius, int stroke,
                int topPx) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(PANEL);
            d.setCornerRadius(radius);
            d.setStroke(topPx > 0 ? Math.max(stroke, topPx / 2) : stroke,
                    topPx > 0 ? border : BORDER);
            return d;
        }

        /** Alert panel: tinted background, coloured border. */
        static GradientDrawable alert(int color, int radius, int stroke) {
            GradientDrawable d = new GradientDrawable();
            int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF,
                    b = color & 0xFF;
            d.setColor(0xFF000000 | ((0x11 + r / 8) << 16)
                    | ((0x13 + g / 10) << 8) | (0x16 + b / 12));
            d.setCornerRadius(radius);
            d.setStroke(Math.max(stroke, 2), color);
            return d;
        }

        /** TFR row: panel, solid (active) or dashed category border. */
        static GradientDrawable leftEdge(int color, boolean solid, int radius,
                int width) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(PANEL);
            d.setCornerRadius(radius);
            if (solid)
                d.setStroke(width / 2, color);
            else
                d.setStroke(width / 2, color, width * 2, width);
            return d;
        }
    }
}
