
#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "TFR-TAK",
   plugin-version: "0.4",
   platform: "ATAK",
   platform-version: "5.6.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
TFR-TAK puts the FAA's *Temporary Flight Restrictions* (TFRs) on the ATAK map by itself, straight from the FAA's public TFR site, *tfr.faa.gov*. No other server is involved. It is meant for people on the ground (incident and disaster response, drone operators, prepared citizens) who want to know when an airspace closure goes into effect near them. Pilots should keep using their aviation apps.

*For awareness only. Not for navigation or flight planning.* The NOTAM text is the authority; the map is a depiction of it.
]
= Getting started
- The device needs internet the first time. TFR-TAK loads every active TFR (usually 50 to 60 nationwide) within about a minute, then checks for changes every 10 minutes.
- Tap the *TFR-TAK* toolbar button to open the pane. *Refresh* checks right away.
- *Show TFRs on map* removes every TFR shape from the map, for screenshots or when TFRs don't matter. The list, the updates and the nearby alerts keep running.
- TFR-TAK also appears in ATAK's *Overlay Manager*, where the TFRs can be hidden like any other layer and each TFR area is listed.
- *Security & VIP only* hides every other kind of TFR, on the map and in the list.
- Tap a TFR on the map, or a row in the list, for its details and the full NOTAM text. *Map* / *Show on map* zooms to it.
]

#tak-slide[
  = Reading the map
- *Red:* security (14 CFR 99.7 Special Security Instructions) and VIP (14 CFR 91.141). *Orange:* hazard / disaster areas (91.137, 91.138). *Yellow:* air shows and major sporting events (91.145). *Blue:* space operations (91.143). *Purple:* drone restrictions over public gatherings (49 USC 44812). *Grey:* other.
- *Solid outline, stronger fill:* in effect now.
- *Dashed outline, faint fill:* published but not in effect yet, or between its scheduled windows (some TFRs are active only part of each day, or on certain days).
- *Dotted:* no longer in the FAA list, possibly cancelled. It is removed if it is still missing at the next update.
- Labels (type, altitudes, state) appear once you zoom in far enough for the area to fill part of the screen.
- In 3D view each area is raised from the ground to its ceiling. Ceilings in feet MSL and flight levels are drawn as height above ground, so the drawn top is never lower than the real one. Floors above the surface are not drawn; the label and details give them.
- A TFR disappears from the map at its published end time, even without a connection.
]

#tak-slide[
  = The pane
- *Top line:* when the FAA data was last received. *STALE* (amber) means no successful update for over 30 minutes: new TFRs may be missing. Problems reaching the FAA site are shown here too; the plugin keeps showing the last data received and keeps retrying.
- *Active now:* TFRs in effect, nearest to you first, with their end time.
- *Upcoming:* published TFRs not in effect yet, or between windows, soonest first, with when they start.
- *Text-only (no map graphic):* TFRs the FAA publishes without a shape, mostly long-standing nationwide security notices. Collapsed by default. Read their NOTAM text.
- *Ended:* only with *Show ended*.
- Tap a box header to open or close it; TFR-TAK remembers.
- Times are shown in Zulu with your device's local time in brackets.

= Details

Each TFR's details show the regulation, place, controlling ARTCC, effective and end times, and for every area its floor and ceiling exactly as published (SFC, ft AGL, ft MSL or flight level), whether it is active now and when that changes, and where its outline came from. Then the *full NOTAM text*, which can be selected and copied.
]

#tak-slide[
  = Alerts

When a TFR is newly posted within *50 NM* of your position, TFR-TAK shows an ATAK notification with its type, distance and start time. Alerts are not raised for the TFRs already listed on the first load, and follow the *Security & VIP only* filter. If a new TFR's details can't be downloaded at first, its alert comes as soon as they are.

= Without a connection

The last FAA data is kept on the device (`atak/tools/tfrtak/`), so the TFRs come back after a restart without signal. Active / upcoming / ended keep being worked out from the published times. TFRs posted, changed or cancelled while offline can't be known: watch the *STALE* warning.

= How the data is checked
- Every outline is built from the TFR's published definition (points, circles and arcs on WGS84) and checked against the FAA's own graphic. If they disagree, the FAA's graphic is shown and the details say so.
- If any part of a TFR can't be read with certainty, the details say so and TFR-TAK errs on the side of showing the restriction: an unclear schedule is shown as active for its whole period; an unreadable outline is listed as text-only.
- A glitch on the FAA site can't wipe the map: an unreadable or empty answer changes nothing (the warning at the top says so), and a much shorter list is only believed once it has lasted 30 minutes.
]

#tak-slide[
  = Caveats
- For awareness only. Not for navigation or flight planning. Before any flight, including drones, check the current NOTAMs at #link("https://tfr.faa.gov")[tfr.faa.gov] or with Flight Service (1-800-WX-BRIEF).
- Not every restriction is a TFR: permanent restricted and prohibited areas, the Washington DC SFRA/FRZ and similar airspace are not shown.
- Some TFRs have no shape in the FAA data and are listed as text only.
- TFRs posted after the last successful update are not shown. TFRs are often issued at short notice during incidents.
- Altitudes are shown as published and never converted; MSL altitudes are above sea level, not above the ground where you are.

= Source

FAA Temporary Flight Restrictions, #link("https://tfr.faa.gov")[tfr.faa.gov]: the list of active TFRs and each TFR's published XML. TFR-TAK is not endorsed by the FAA. Contact: S2Underground, s2underground\@protonmail.com.
]
