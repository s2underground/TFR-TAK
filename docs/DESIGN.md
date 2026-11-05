# TFR-TAK design notes

## Layers

- `core/` pure Java: coordinates, altitudes, schedules, WGS84 geodesics,
  outline building, the FAA list and XML parsers. Unit-tested against real
  FAA files.
- `net/` `HttpFaaClient`: HTTPS to tfr.faa.gov only. URLs are fixed parts
  plus a validated NOTAM id (`^\d/\d{1,5}$`); host allowlist checked before
  connecting and on the response URL; redirects refused; size caps;
  HTTP 200 required; identifying User-Agent.
- `engine/` `TfrEngine`: all state on one thread (`Scheduler`), publishes
  immutable `Snapshot`s. Tested with a virtual-time scheduler and a fake
  FAA serving the fixtures.
- `ui/` `Rows` and `MapPlan`: pure Java, snapshot to pane rows / map
  shapes (tested). `TfrPane` only renders.
- `atak/` `MapLayer`: applies a `MapPlan` as ATAK `Polyline`s in group
  "TFR-TAK", registered with Overlay Manager (`DefaultMapGroupOverlay`,
  base64:// icon); only changed shapes are touched.

## FAA data format (learned from the 54 TFRs active Nov 2, 2026)

List: `GET https://tfr.faa.gov/tfrapi/exportTfrList` → JSON array of
`{notam_id, type, facility, state, description, creation_date}`.
Types seen: SECURITY, VIP, HAZARDS, AIR SHOWS/SPORTS, UAS PUBLIC GATHERING,
SPECIAL.

Detail: `GET https://tfr.faa.gov/download/detail_6_4199.xml`, root
`XNOTAM-Update/Group/Add/Not`:

- `NotUid`: `dateIndexYear` + `noSeqNo` = the NOTAM id (checked against
  the id requested), `dateIssued`, `txtLocalName`.
- `dateEffective` / `dateExpire`: **UTC**, whatever `codeTimeZone` says
  (6/4199: XML 14:15, text "2611021415 UTC (0915 LOCAL)"). Either may be
  missing: no start = already in effect (6/5945), no end = until further
  notice.
- `txtDescrUSNS`: the NOTAM text (plain). `txtDescrModern` is HTML.
- `TfrNot/codeType`: the regulation (99.7, 91.141, 91.137(a)(1),
  91.145, 44812, SPECIAL). Category is derived from it.
- `TFRAreaGroup`: per area `aseTFRArea` (name, `codeDistVer*` HEI = AGL,
  ALT = MSL; `uomDistVer*` FT or FL; `ScheduleGroup`s; `dayCode`s),
  `Aac` linking the area (child) to its shape (parent, `BOM`/`BASE`),
  `aseShapes` (the definition) and `abdMergedArea` (the FAA's computed
  outline). Linked by `codeId`, so nesting doesn't matter.
- Schedules: a `ScheduleGroup` with `isTimeSeparate=TRUE` is a daily
  window: active each day from the time of `startTime` to the time of
  `endTime` (their dates are meaningless; e.g. 6/4313 endTime
  2026-08-29T06:00 for DLY 1200-0600), crossing midnight when end <
  start, within `dateEffective`..`dateExpire`. `dayCode` "Daily" or day
  names (UTC days; 6/2481 Area E "TUE, THU, SAT 1100-1300").
- Shape vertices (`Avx/codeType`): GRC point (geodesic edge to the next),
  CIR full circle (center `geoLat/geoLong`, `valRadiusArc` NM), CWA/CCA
  arc around `geoLatArc/geoLongArc`, clockwise/counter-clockwise from the
  previous point to the next point. Confirmed: all 50 outlines built this
  way match `abdMergedArea` within 0.5% of their size.
- `abdMergedArea` circles are 36-point polygons with vertices on the
  circle, so their edges cut up to ~0.6% inside. We build our own
  (chord error ≤ 5 m) and use the FAA's only as a cross-check / fallback.
- 15 of 65 areas (nationwide SSIs, DC SFRA text, the US/Mexico border
  UAS TFR) have no geometry at all; no shapefile exists for them either
  (`/download/<id>.shp.zip` 404). Listed as text-only.

## Hardening rules

| Situation | Behaviour |
|---|---|
| List unreachable / HTTP error | Keep everything; "Can't reach tfr.faa.gov"; retry 1, 2, 5 min |
| List not a JSON array (HTML error page, truncated) | Same: never treated as "no TFRs" |
| List empty while ≥3 TFRs are held | Never accepted (nationwide SSIs are always listed): previous picture kept, problem shown, STALE after 30 min |
| List drops below half of ≥10 | Held until the drop has persisted 30 min (checked every 5 min) |
| TFR missing from one accepted list | Shown dotted, "possibly cancelled"; removed if missing again |
| Detail unreachable mid-poll | Stop, retry soon; keep last copies |
| Detail HTTP 404 / unreadable | Keep the last good copy, flagged; new TFR listed with list data only; retried every 2 min (5 times) |
| New TFR whose detail fails | Its alert waits (persisted) until the detail is read, up to 24 h |
| Detail for a different NOTAM / DOCTYPE / malformed | Refused |
| Schedule part unreadable | NOTAM's overall period added; area active from first start to last end, flagged |
| Day-of-week windows | Count on the UTC day or the local day (NOTAM zone); unknown zone + days = uncertain |
| Multi-area TFR active | "Active until" = when the last active area (chained) ends |
| Outline definition unusable or disagrees with FAA graphic | FAA graphic shown, flagged |
| No outline at all | Listed as text-only, never guessed |
| Restart without signal | Last FAA files re-parsed from the device cache; states computed from published times |
| Cache file unreadable | Moved aside as `.corrupt-<time>`, start fresh; newest 5 kept |
| Orphan cache files | Swept at start (details of removed TFRs, `.bak`, `.tmp`) |
| Plugin stopped mid-poll | No further requests, writes or snapshots |
| Regulation code unknown | Category from the list's type word |
| Data older than 30 min | STALE warning |

Polling: list every 10 min; details for new or changed rows immediately,
others re-read hourly (≤15 per poll), 250 ms apart.

## Map items

`Polyline` per area, uid `tfrtak-<n>_<nnnn>-<area#>`; closed, filled,
stroke 3; height = ceiling in feet (MSL and FL taken as height above
ground: only ever overstates the drawn height);
`ignoreMenu` (tap opens our detail view); `archive=false`,
`nevercot=true` (never saved by ATAK or sent to others).

## Labels (1.0.1)

Drawn by `atak/LabelLayer` straight through ATAK's map label manager
(`GLMapView.getLabelManager()`, the one ATAK's own labels use), not as
the polylines' `centerPointLabel`:

- **X-ray.** In a tilted 3D view ATAK depth-tests labels
  (`GLLabelManager::draw`) and extruded polygons write depth
  (`GLBatchGeometryRenderer4::drawSprites`), so a ground label inside a
  TFR's box is hidden by its walls and roof. `HINT_XRAY` draws the hidden
  part at 40% opacity, as for markers behind terrain. Flat view: no change.
- **One label per group of nested areas.** Areas of one TFR whose centers
  fall inside each other (the DC 30 NM ring and Area B) share one label at
  the largest area's bounding-box center; one line if altitudes and state
  match, else a line per area.
- **Zoom.** Shown from the coarsest ATAK tile level at which the area
  spans `LABEL_MIN_DP` (160 dp x screen density) and at every closer zoom.
  ATAK compares whole levels, floor(log2(156543.034 / m per px)); the level
  is rounded up so the label never appears before the area spans 160 dp
  (it appears between 160 and 320 dp; "Show on map" fits the whole view).
- Shown while any of its shapes is shown (map switch, Overlay Manager)
  and the pane's "Show labels" switch (pref `tfrtak.labelsVisible`) is on;
  not hit-testable, so a tap reaches the shape. Removed on stop.
- Fallback if the label manager can't be reached: the polylines'
  `centerPointLabel` with the same level (`minLabelRenderResolution` and
  `minRenderScale` = coarse limit, `maxLabelRenderResolution` = 0).
