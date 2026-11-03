# TFR-TAK pre-release audit (0.3, Nov 2, 2026)

Two passes, run independently:

1. **Independent code review**: a separate reviewer that had not seen the
   development reasoning read the whole code base, checked every ATAK API
   against the SDK bytecode, probed about 30 live FAA files, and reproduced
   findings with throwaway tests.
2. **Data, security and release pass**: regression against a second day of
   live FAA data, SpotBugs 4.8.6 + FindSecBugs 1.13 (a stand-in for the
   TAK.gov Fortify scan), and the TAK.gov Third Party Pipeline requirements.

Every confirmed finding is fixed in 0.3 and has a regression test
(`core/AuditRegressionTest`, `engine/TfrEngineTest`).

## Findings

| # | Sev | Finding | Fix (0.3) |
|---|---|---|---|
| H1 | High | A multi-area TFR's "ACTIVE until" used the earliest change of any area (e.g. another area's next start). Live 6/2481 (Clear AK, security) said "until Nov 3 1100Z"; it runs to Mar 14 2027. | `Tfr.nextChange`: when active, the end of the last active area, chaining areas that overlap or touch; an active area with no end = no end. |
| H2 | High | A schedule part that couldn't be read was dropped; the "uncertain = active for its whole period" rule then covered only the readable parts, so an area could show EXPIRED while in force. | Uncertain schedules also get the NOTAM's overall effective/expiry period. |
| M3 | Med | A valid but empty FAA list was accepted after 3 answers, then everything was removed on the next poll and the device cache deleted (~24 min). | An empty list is never accepted while ≥3 TFRs are held (nationwide SSIs are always listed): picture kept, problem shown, STALE after 30 min. A drop below half must persist 30 min. |
| M4 | Med | A new TFR whose detail download failed in the poll that first listed it was never alerted; failed details waited a full 10 min. | Persisted "alert pending" until the detail is read (24 h cap); failed details retried every 2 min (5 times). |
| M5 | Med (plausible) | Day-of-week windows were applied to UTC days only; if the FAA means local days, windows near midnight UTC could land on the wrong day. | A window counts on its UTC day **or** its local day (NOTAM `codeTimeZone`); unknown zone with day limits = uncertain. Can only show more days, never fewer. |
| M6 | Med | `stop()` queued its flag and then discarded the queue, so a running poll kept fetching (without the 250 ms gap) and writing the cache. | `stopped` is volatile and set directly; checked between requests, before parsing, saving and publishing. |
| L7 | Low | `.bak` and `.corrupt-*` cache files were never deleted. | Removal deletes `.bak`; start-up sweep removes orphans and `.tmp`, keeps the newest 5 quarantined files. |
| L8 | Low | A TFR with some areas undrawn gave no hint in the list. | Row note "N of M areas have no map graphic. Read the NOTAM text." |
| L9 | Low | A UTF-8 byte-order mark on the list would reject it on every poll (the FAA's XML files carry one). | BOM stripped. |
| L10 | Low | The DOCTYPE pre-check looked at the first 4 kB only; parser defences were set through a helper with an empty catch. | Whole file scanned; UTF-16/32 refused; direct `setFeature` calls incl. `FEATURE_SECURE_PROCESSING`, `setXIncludeAware(false)`, an entity resolver that returns nothing; narrow catches. |
| L11 | Low | Network-supplied NOTAM ids flowed into file names and URLs (validated by regex only). | `NotamId.fileKey` rebuilds the name from the two parsed integers. |
| L12 | Low | The pane's list (≈4,200 vertices of distance work) was rebuilt every minute on the main thread even when hidden. | Rendered only while the pane is visible. |
| L13 | Low | Map shapes were redrawn only if point count / first / middle point changed; an unknown regulation code became grey "Other" (hidden by the security filter). | Signature covers every point; category falls back to the list's type word. |
| D1 | Low | "0 ft MSL" floors (live 6/4857) showed as "0 ft MSL". | Shown as SFC. |
| D2 | Info | Details didn't show the exact regulation cited or the issue time. | Added ("cited: 91.137(a)(1)", "Issued: ..."). |

## Verified sound

- ATAK API use matches the SDK bytecode (Polyline extrusion defaults,
  `centerPointLabel`, `ignoreMenu`, `postNotification`, `scaleToFit`,
  `findMapGroup`/`deepFindUID`); snapshots handed to the main thread;
  listeners removed on stop.
- Arc direction, circles and the outline cross-check: all 103 drawn areas
  across both days match the FAA's graphic; midnight-crossing windows;
  junk dates in `startTime`/`endTime` (live 6/4687 carries another NOTAM's
  dates) are ignored as designed.
- Altitudes shown as published; the network client (fixed host, no
  redirects, size caps, truncation check).

## Data regression

| Set | TFRs | Areas | Drawn & confirmed | Text-only | Warnings |
|---|---|---|---|---|---|
| 2026-11-02 (morning) | 54 | 65 | 50 | 15 | 0 |
| 2026-11-03 UTC (evening) | 55 | 68 | 53 | 15 | 0 |
| Live run at build time | 55 | 68 | 53 | 15 | 0 |

New TFR forms seen in the evening set and now covered by tests: SFC–FL250
daily windows (6/4687, 6/4688), a 0 ft MSL floor (6/4857), two separate
periods (6/4390).

## Static analysis (SpotBugs + FindSecBugs, max effort)

Remaining, accepted, expected from Fortify too:
- **Path manipulation** (`new File(dir, name)`): names are built from
  parsed integers (`detail_%d_%04d.xml`) or come from listing the plugin's
  own cache folder.
- **SSRF** (`HttpFaaClient`): the URL is a fixed `https://tfr.faa.gov` path
  plus two integers; host allowlist checked before connecting and on the
  response; redirects refused. (GhostMaps' Fortify scan reported SSRF on a
  similar client despite an allowlist.)
- **ReDoS** on the time pattern: no nested quantifiers, and input is capped
  at 40 characters before matching.
- Exposure-of-internal-representation and Unicode case-mapping notes:
  false positives (constructor injection; `toUpperCase(Locale.US)` on ASCII
  codes).

## Not covered by automated tests (device checklist)

- Labels (`centerPointLabel`) visible; tap opens details without ATAK's
  shape menu; 3D extrusion (FL430 areas ≈ 13 km); "TFR-TAK" in
  Overlay Manager; Show on map zoom; notification; map switch; box states.
- Offline restart: airplane mode, restart ATAK, TFRs return with the
  connection warning.
- Minified release APK, not just debug.

## Device test of 0.3 (Nov 2, 2026, dev tablet, ATAK 5.5.1)

Passed: tap opens details (no radial menu), 3D extrusion, Map / Show on
map zoom, map switch, box collapse, airplane-mode restart.
Failed, fixed in 0.4:
- **Labels not shown.** ATAK draws a polyline's `centerPointLabel` only
  below `maxLabelRenderResolution`, default 10 m/pixel (street level), in
  both `GLPolyline` and `GLPolylineFeature`. Now set per shape to
  extent / 150 px (min 10 m/px).
- **Not in Overlay Manager.** A map group added to the root is not listed;
  it must be registered with `getMapOverlayManager().addOverlay(new
  DefaultMapGroupOverlay(...))` (as ATAK's HelloWorld sample does). Group
  renamed "TFR-TAK"; icon passed as base64:// (URL_SAFE|NO_WRAP, the flags
  `ATAKUtilities.setIcon` decodes with). New shapes inherit the group's
  visibility, so hiding it in Overlay Manager also hides TFRs posted later.

## Known limits (by design, stated in the manual)

- Awareness only, not navigation. Not every restriction is a TFR (no
  permanent restricted/prohibited areas, DC SFRA/FRZ).
- 15 TFRs have no geometry anywhere in FAA data: listed text-only.
- Ceilings in MSL / flight levels are drawn as heights above ground
  (overstates, never understates).
- FDC numbers wrap yearly; a removed TFR's files are deleted, so a reused
  number starts fresh.
