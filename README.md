# Gatelangs

Walk every street in a city. Gatelangs tracks your position, matches it against the
real OpenStreetMap road network, and paints each stretch of road as you cover it.

Kotlin Compose Multiplatform, targeting **Desktop (JVM)** and **Web (Wasm)**.

See [PLAN.md](PLAN.md) for the architecture and the order of work.

## Setup

The basemap comes from CARTO, which since August 2026 stamps an "API KEY REQUIRED"
watermark across every tile fetched without a key. Keys are free, arrive by email, and
need no account: <https://carto.com/basemaps/apikey/>.

Put yours in `local.properties` at the repo root — the file is gitignored, so it stays on
your machine:

```properties
carto.apiKey=your_key_here
```

CI can set `CARTO_API_KEY` in the environment instead. With neither, the app still builds
and runs against the keyless URL; you just get the watermark.

Note that this key ends up inside the built artifact and is visible in the browser's
network tab on the web build. That is unavoidable for a client-side map and is why the
key is worth scoping to this project rather than reusing one.

Tiles are only ever fetched once. On desktop they are kept under `~/.gatelangs/tiles/`
and reused on every later run; delete that directory to force a refresh. On the web the
browser's own HTTP cache does the same job — CARTO serves tiles with a 180-day
`max-age` — so no second cache is kept there.

## Running

```bash
./gradlew :composeApp:run                             # Desktop
```

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun     # Web
```

Desktop is the fast feedback loop and runs a simulated walker, so the whole tracking
pipeline is exercisable without a GPS. Web is the real target: opened on a phone over
HTTPS, it gets genuine GPS via the browser geolocation API.

To pre-download every dependency and the JDK 21 toolchain:

```bash
./gradlew verifySetup
```

## License

[MIT](LICENSE).
