# User guide

## Enabling

**Menu → Plugins → ALPR cameras (DeFlock) → enable.** The plugin is off by default and adds
nothing to the app until enabled.

Once enabled you get a map layer, a settings screen, a "Configure map" toggle, and a new
entry in the route options sheet.

## Seeing cameras

Cameras are drawn from **zoom 13**. The camera icon itself appears from **zoom 15** — below
that only the view wedges are drawn, because at city scale the icons overlap into noise.

Each camera shows a wedge covering the area it is assumed to watch, drawn to scale in real
metres. Tap one to see its manufacturer, operator and facing.

A camera whose `direction` is missing from OpenStreetMap is drawn as a **translucent circle**
instead of a wedge. That is deliberate: the facing was never recorded, so the app does not
invent one, and routing treats such a camera as watching in every direction.

Downloading needs internet the first time you visit an area. After that the area is cached on
disk and works offline.

## Routing around cameras

Plan a route, then **Route options → Avoid ALPR camera view**.

The sheet has a switch and a **detour slider (0–60 minutes)**. The slider is the budget: the
most extra travel time you are willing to spend to stay out of camera view.

What happens when you start navigation:

1. The fastest route is calculated normally — this is the yardstick.
2. A second route is calculated that avoids every road any camera can see.
3. If that costs **less than your budget**, you get it.
4. If it costs more, the router gives ground one road class at a time — motorways and trunk
   roads first, then primary, then secondary — until the detour fits. Fast roads are conceded
   first because they are the expensive ones to route around.
5. If nothing fits, you get the plain fastest route, and the app tells you how many cameras
   can see it rather than pretending it avoided them.

Afterwards the route option row shows the result, so the budget is not something you have to
take on faith:

- `No ALPR cameras in view • +4 min detour` — fully avoided, cost you 4 minutes.
- `7 ALPR cameras in view` — nothing fit your budget; this is the fastest route.

Setting the slider to **0** means "only avoid cameras if it is free". Setting it high means
"detour a lot to stay unseen".

## Settings

**Menu → Plugins → ALPR cameras (DeFlock) → Settings.** Everything except the Overpass
endpoint is per navigation profile, so your car and bicycle profiles can differ.

| Setting | Default | What it means |
|---|---|---|
| Show cameras on map | on | Draws the layer. Also toggleable from Configure map. |
| Detection range | 60 m | How far a camera is assumed to read plates. |
| Field of view | 60° | How wide its view is assumed to be. `Direction not mapped` treats every camera as omnidirectional. |
| Avoid ALPR camera view | off | Whether routing avoids camera view for this profile. |
| Acceptable detour | 10 min | The detour budget described above. |
| Clear downloaded cameras | — | Deletes the offline cache; shows how many cameras are stored. |

### About range and field of view

**These are estimates, not measurements.** OpenStreetMap records where an ALPR camera is and
which way it points, but not how far it reads or how wide its lens is. 60 m and 60° are
deliberately modest defaults.

Raising them makes the app avoid more road and produce longer detours; lowering them does the
opposite. Whatever you choose, the wedge drawn on the map always matches what the router
avoids, so you can see the consequence of the setting directly.

## What this does not do

- **It is not a guarantee.** A camera that nobody has mapped is a camera the app cannot avoid,
  and the modelled field of view is an approximation of a real device.
- **No proximity alerts.** There is no voice or on-screen warning when you approach a camera
  during navigation. Cameras are shown on the map and accounted for when planning.
- **No editing.** You cannot add or correct cameras from here. Contribute at
  [deflock.org](https://deflock.org) or directly in OpenStreetMap.

## Data source and etiquette

Camera data comes from the public [Overpass API](https://overpass-api.de), which is a free,
donated service. The app downloads in ~40 km tiles, caches each for 30 days, downloads at most
16 tiles per request, and backs off when the server reports it is busy. Please do not
reconfigure it to poll aggressively.
