TFR-TAK


_________________________________________________________________
PURPOSE AND CAPABILITIES

TFR-TAK is an ATAK plugin that puts the FAA's Temporary Flight
Restrictions on the map by itself, straight from the FAA's public TFR
site (tfr.faa.gov). No server of our own is involved. It is meant for
people on the ground (incident and disaster response, drone operators,
prepared citizens) who want to know when an airspace closure goes into
effect near them; pilots should keep using their aviation apps.

- Polls the FAA list of active TFRs every 10 minutes (manual Refresh in
  the pane) and reads each TFR's detail file
- Draws every TFR area that has a graphic as a native ATAK shape,
  extruded from the ground to its ceiling in 3D view
- Color by type: red = security (14 CFR 99.7) and VIP (91.141); orange =
  hazard/disaster (91.137/91.138); yellow = air shows and sports
  (91.145); blue = space operations (91.143); purple = UAS restrictions
  over public gatherings (49 USC 44812)
- Solid outline = in effect now; dashed = published but not active yet,
  or between scheduled windows; dotted = dropped from the FAA list
  (possibly cancelled)
- Pane: data age and any feed problem at the top; list sorted active
  first, then by distance from you; "Security & VIP only" filter; full
  NOTAM text, every area's altitudes and schedule, and "Show on map"
- Tap a TFR on the map to open its details
- Notification when a newly posted TFR is within 50 NM of you
- TFRs without a graphic in the FAA data (nationwide security notices,
  some long-term restrictions) are listed as text-only, never guessed

The NOTAM text is the authority; the map is a depiction. For awareness
only, not for navigation or flight planning.

_________________________________________________________________
HOW IT STAYS CORRECT

- Times: every time in the FAA files is UTC (checked against the NOTAM
  text). Shown in Zulu and device local time. Active / starts / between
  windows / ended is computed from the published schedule, including
  daily windows that cross midnight and day-of-week schedules, so it
  stays right without a connection.
- Shapes: each outline is built from the published definition (points,
  circles, clockwise and counter-clockwise arcs on WGS84) and checked
  against the FAA's own pre-computed graphic. If they disagree, the FAA
  graphic is shown and the detail view says so.
- Altitudes are shown exactly as published (AGL, MSL, flight level),
  never converted.
- A doubtful schedule is shown as active for its whole period; an
  unreadable outline is listed as text-only with a note. Nothing is
  silently dropped.
- Feed glitches can't wipe the map: an unreadable answer changes
  nothing; a suddenly empty or halved list is held until later polls
  confirm it; a TFR missing from one poll is flagged "possibly
  cancelled" and only removed if still missing at the next.
- The last FAA files received are cached on the device
  (atak/tools/tfrtak/), so the picture survives a restart without
  signal. Data older than 30 minutes is flagged STALE.

See docs/DESIGN.md for details and the FAA file format notes.

_________________________________________________________________
STATUS

0.1 (Nov 2026): first test build.

_________________________________________________________________
POINT OF CONTACTS

S2Underground - s2underground@protonmail.com

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (443) to tfr.faa.gov only.

_________________________________________________________________
EQUIPMENT REQUIRED

None.

_________________________________________________________________
EQUIPMENT SUPPORTED

ATAK-CIV 5.5 and later.

_________________________________________________________________
COMPILATION

Template-based (ATAK plugin template, Gradle 8.14.3, AGP 8.13.0).
Release builds target ATAK 5.6.0. For the 5.5.1 developer build:

    ./gradlew assembleCivDebug -PatakVersion=5.5.0

Unit tests use real FAA files captured Nov 2, 2026
(app/src/test/resources/fixtures/). A live end-to-end test against
tfr.faa.gov runs only on request:

    ./gradlew testCivDebugUnitTest -Ptfr.live=true

_________________________________________________________________
DEVELOPER NOTES

Data source: https://tfr.faa.gov/tfrapi/exportTfrList (JSON list) and
https://tfr.faa.gov/download/detail_<n>_<nnnn>.xml (per TFR). These are
the FAA's public website endpoints; if they change, only net/ and
core/TfrListParser / core/TfrXmlParser need updating.
