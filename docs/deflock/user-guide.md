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

There are two ways cameras get onto your device, and the difference matters:

- **Offline camera data** you download per map region (see below). Deliberate, never expires,
  and is what routing uses.
- **Browsing cache**, filled in as you pan the map while online. Convenient, expires after 30
  days, and only covers wherever you happened to look.

## Offline camera data

**Menu → Plugins → ALPR cameras (DeFlock) → Settings → Offline camera data.**

The screen lists every offline map you have downloaded. Tap one to download cameras for it;
tap again later to update or delete. The data is stored beside your maps, named after the same
region, so `Us_indiana_northamerica.obf` gets `deflock/us_indiana_northamerica.deflock`.

Once a region is downloaded it never expires and is never re-fetched on its own — refreshing is
your decision, and the screen shows when each region was last downloaded. Large regions are
fetched in several requests, so the progress counter moves in steps. A download keeps going if
you leave the screen, and tapping the region while it runs offers to cancel it.

Overpass is a free shared service and refuses requests when it is busy, so each part of a
region is retried a few times before the download gives up. **If you have not changed the
Overpass endpoint, a refused request is retried against a public mirror**
(`overpass.kumi.systems`) — worth knowing, because the area you are downloading is visible to
whichever server answers. Setting a specific endpoint in settings disables that: a server you
chose deliberately is the only one that will be asked.

The mirrors are independent copies of OpenStreetMap and are not always equally current. Asking
both for the same area during testing returned 178 cameras from one and 92 from the other, so a
region fetched partly via the fallback can be less complete than one fetched entirely from the
main server. Re-downloading the region later is the fix.

Downloads are all-or-nothing. If part of a region cannot be fetched, nothing is written and
whatever you already had is left alone, because half a region would claim coverage it does not
have. If a region genuinely has no ALPR cameras mapped yet, it says so and stores nothing —
that is different from having data, and the router treats it as unknown ground rather than as
ground known to be clear.

**No internet at all?** A region file can be shared from a device that did download it, and
imported through the normal Android share sheet. Plain GeoJSON exports from DeFlock or Overpass
can be imported the same way.

## Routing around cameras

Plan a route, then **Route options → Avoid ALPR camera view**. Routing only ever uses data
already on your device — it will not reach for the network mid-calculation, so download the
regions you care about first.

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
- `No offline camera data for this route` — no camera data at all was available for this
  ground, so nothing could be avoided. **This is not the same as a clean route.**
- `… • Camera data may be incomplete` — appended to either of the first two. Cameras were
  found and routed against, but you have not downloaded a region covering the whole route, so
  some of it may have been planned blind.

The last two exist because the alternative is worse: without them, a route over an area you
never downloaded looks exactly like a route that successfully avoided every camera. Note that
the qualifier *adds to* the result rather than hiding it — if avoidance worked, you still see
the detour it cost.

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
| Offline camera data | — | Per-region downloads, described above. |
| Clear downloaded cameras | — | Clears the *browsing cache* only. Downloaded regions are unaffected; delete those from the offline data screen. |

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
donated service. Region downloads are split into several bounded requests rather than one
country-sized query; map browsing fetches ~40 km tiles and caches each for 30 days, at most 16
per request, backing off when the server reports it is busy. Please do not reconfigure it to
poll aggressively — and prefer downloading a region once over letting the browsing cache fill
in piecemeal.
