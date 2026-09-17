 # Gatelangs — plan

Walk every street in a city. The app tracks your position, matches it against the
real road network, and paints each stretch of road as you cover it.

Targets: **Desktop (JVM)** and **Web (Wasm)**. One day.

---

## 1. The actual problem

Rendering a map is the visible part, but it is not the hard part. The hard part is
three things:

1. **Knowing which roads exist.** A city is not a picture — it is a set of polylines.
   You cannot compute "83% walked" from map tiles.
2. **Deciding when a road counts as walked.** GPS is noisy. A fix 12 m from a street
   may mean you walked it, or may mean you walked the parallel street.
3. **Doing 1 and 2 fast enough**, continuously, while drawing.

Everything below follows from those.

## 2. Data sources

OpenStreetMap supplies **two separate things**, and it is worth keeping them apart:

| Need | Source | Notes |
| :--- | :--- | :--- |
| Backdrop imagery | Raster tiles, `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | Free. Swappable for Mapbox/MapTiler raster later — it is one URL template. |
| Road geometry | Overpass API, `https://overpass-api.de/api/interpreter` | The polylines we actually reason about. |

Mapbox would only replace the *first* row. The road data still has to come from OSM
either way, so the tile provider is a cosmetic decision we can defer.

Overpass query (bbox, `out geom` inlines node coordinates so we never resolve node
refs separately — this saves a whole request round and a join):

```
[out:json][timeout:60];
way["highway"~"^(residential|living_street|pedestrian|footway|unclassified|tertiary|secondary|primary|path|steps)$"]
  (59.900,10.700,59.940,10.790);
out geom;
```

`motorway`/`trunk` are excluded — you cannot walk them. `service` is excluded too
(driveways and parking aisles would inflate the denominator). Which classes count is
a single tunable constant, not a design commitment.

## 3. Core model

```kotlin
data class LatLon(val lat: Double, val lon: Double)

/** One walkable stretch, <= ~25 m. Ways are flattened into these at load time. */
data class Segment(val wayId: Long, val a: LatLon, val b: LatLon)

data class RoadNetwork(
    val segments: List<Segment>,          // flat, index == segment id
    val streetNames: Map<Long, String?>,  // wayId -> name
    val index: GridIndex,
)
```

Walked state is a `BooleanArray` parallel to `segments`. Coverage is
`walked.count { it } / segments.size`. Per-street coverage groups by `wayId`.

Size check: a 4 km x 4 km slice of Oslo is roughly 200 km of road, so ~8 000 segments.
All of Oslo is ~120 000. Both are nothing — this fits in memory comfortably and the
whole design can stay simple.

## 4. The two pieces of geometry that matter

**Projection.** Two different projections, for two different jobs:

- *Rendering*: Web Mercator → world pixels at zoom z. Standard slippy-map math,
  needed because tiles are defined in it.
- *Matching*: local equirectangular to metres around a reference latitude
  (`x = R * (lon - lon0) * cos(lat0)`, `y = R * (lat - lat0)`). Over a city this is
  accurate to well under a metre, and it makes distance a plain Euclidean formula.
  Do **not** use haversine per segment per fix — project once, then it is arithmetic.

**Point-to-segment distance.** Not point-to-endpoint. Project the point onto the
segment, clamp the parameter to `[0, 1]`, measure to that. ~10 lines, and the whole
app's correctness rests on it.

**Spatial index.** A uniform grid hash, cell ≈ 50 m, `Map<Long, IntArray>` keyed by
packed `(cellX, cellY)`. Segments are inserted into every cell their bounding box
touches. Two queries serve the entire app:

- GPS fix → the 3x3 cells around it → candidate segments to mark walked.
- Viewport bbox → cells → segments to draw.

This replaces any need for an R-tree and is about 30 lines.

## 5. Matching rule

For each fix: mark every candidate segment whose point-to-segment distance is under
`max(15 m, accuracy)`. Discard fixes with `accuracy > 30 m` outright.

This will occasionally light up a parallel street. Accepted for now. If there is time,
the cheap fix is a **bearing gate**: compare the direction of travel (from the previous
fix) against the segment's bearing and reject segments more than ~40° off. That kills
most parallel-street bleed for very little code.

## 6. Rendering

Compose `Canvas`, drawn bottom-up:

1. **Tiles.** For the current center/zoom, compute the visible tile range, fetch each
   `{z}/{x}/{y}.png` with Ktor, decode to `ImageBitmap`, `drawImage` at its pixel
   offset. In-memory LRU (~200 tiles) plus in-flight deduplication so a pan does not
   refetch what is already loading.
2. **Roads.** Query the grid by viewport, `drawLine` per segment — muted grey for
   unwalked, saturated green for walked (see `MapColors` in `ui/theme/Theme.kt`;
   these are deliberately outside the Material scheme because they have to read on
   top of arbitrary tile imagery, not on top of a Material surface).
3. **Position.** Dot plus accuracy halo.

Camera is `(center: LatLon, zoom: Double)`. Drag pans, scroll/pinch zooms.

> **A convenient consequence of dropping Android and iOS:** both remaining targets are
> Skiko-backed, so tile decoding is `Image.makeFromEncoded(bytes).toComposeImageBitmap()`
> on each. It still needs a two-line `expect`/`actual` because a jvm+wasmJs project has
> no shared skiko source set by default, but there is no real platform divergence.

## 7. Location

```kotlin
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val timestampMs: Long)
interface LocationSource { fun fixes(): Flow<Fix> }
expect fun createLocationSource(): LocationSource
```

- **Wasm**: `navigator.geolocation.watchPosition` with `enableHighAccuracy = true`,
  wrapped in a `callbackFlow`. This is the real thing — a phone browser gives real GPS.
- **Desktop**: a **simulated walker** that traverses the road graph at ~1.4 m/s with a
  little noise, emitting a fix per second. This is not a stub to apologise for: it is
  the dev loop for all the matching logic, and it is how the app gets demoed without
  anyone leaving the building.

## 8. Persistence

```kotlin
expect class Storage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}
```

Wasm → `localStorage`; Desktop → a file under `~/.gatelangs/`. Walked state is stored
as `wayId:hexBitset` per way, which stays compact enough for localStorage's 5 MB.

## 9. Order of work

Each step ends at something you can look at. Steps 1–4 are the demo; 5–7 are upside.

| # | Work | Milestone | Status |
| :--- | :--- | :--- | :--- |
| 1 | `geo/` — Mercator, metre projection, point-to-segment distance, `GridIndex`, with unit tests | Green tests, pure `commonMain` | **done** — 70 tests green |
| 2 | Tile source, decode, LRU cache, `MapState`, pan/zoom gestures, canvas draw | A pannable map of Oslo | **done** — compiles, unrun |
| 3 | Overpass client, DTOs, flatten to segments, build index, draw overlay | Roads drawn on the map | **done** — incl. bundled fallback |
| 4 | Simulated walker + matcher + walked colouring + coverage % | End-to-end demo | **done** — unrun |
| 5 | Wasm: geolocation actual, serve over HTTPS, test on a real phone | Real GPS | binding written; HTTPS serving outstanding |
| 6 | Persistence + bundled fallback road data | Survives reload | **done** — unverified |
| 7 | Stats panel, follow-me camera, per-street list | Polish | follow-me done; stats outstanding |

Everything through step 4 is written and compiles; steps 1–4 were verified green before
the build environment became unavailable (see below). The bundled Oslo snapshot is
committed: 2 864 ways, 179.5 km of walkable road, ~7 180 segments, 501 KB.

Step 1 first is deliberate. It is the only part that is genuinely unpleasant to debug
once it is buried under rendering and async I/O, and it is the only part that is
trivially unit-testable.

## 10. Risks, and what to do about them

1. **Overpass is slow and rate-limits.** This is the most likely thing to break the
   demo. Mitigation: fetch the response **once, early**, and commit it to
   `commonMain/composeResources/files/roads-oslo.json` as a fallback the app loads when
   the network fails. Do this at step 3, not at hour six.
2. **Wasm geolocation requires HTTPS.** `wasmJsBrowserDevelopmentRun` serves plain HTTP.
   `localhost` is exempt, so the laptop is fine — but a *phone* pointed at the laptop's
   LAN IP over HTTP will be refused by the browser, silently-ish. Needs an HTTPS tunnel
   (cloudflared/ngrok) or a deployed build. **Budget time for this; it surprises people.**
3. **OSM tile usage policy.** Fine for a demo, but cache aggressively and do not hammer.
   If it becomes a problem, swapping in a MapTiler/Mapbox key is a one-line change.
4. **Parallel-street bleed** in matching — see §5. Acceptable; bearing gate if time.

## 11. Deliberately not doing

Accounts, sync, multiple cities, offline tile packs, route suggestions ("what should I
walk next"), activity import from Strava. All interesting; none of them are today.
