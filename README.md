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
and reused on every later run; delete that directory to force a refresh. On the web they
go into a `gatelangs-tiles` bucket in the browser's Cache Storage — clear it from
DevTools → Application → Cache Storage. The browser's HTTP cache would nearly do the same
job, but it is keyed on the full URL and ours carries `?key=`, so rotating the CARTO key
would throw away every cached tile for imagery that had not changed. Cache Storage is
secure-context only; over plain HTTP nothing is kept and every tile is re-fetched.

While tiles are in flight the map draws the nearest coarser tile it already holds,
stretched over the gap, so the basemap comes up blurred and sharpens rather than filling
in block by block out of an empty background.

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

## Deploy

The web build ships to Netlify as static files — `netlify.toml` points at the Gradle
output and there is no server side.

Pushing to `main` builds and deploys via `.github/workflows/deploy.yml`. The build runs in
GitHub Actions rather than in Netlify's own git integration because Netlify's build image
has no JDK, so `./gradlew` cannot run there; Netlify only receives finished files. This
also means the Netlify site must **not** be linked to the repo in the Netlify UI — a
git-triggered build there would find no `index.html` (`build/` is gitignored) and publish
an empty directory over the good deploy, which is what a site-wide 404 looks like.

Two repository secrets are needed — `NETLIFY_AUTH_TOKEN` (a personal access token from
Netlify user settings) and `NETLIFY_SITE_ID` (the site's API ID, under Site configuration
→ General) — plus `CARTO_API_KEY` if the deployed map should be unwatermarked.

To ship from your own machine instead:

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
npx netlify-cli deploy --prod --dir composeApp/build/dist/wasmJs/productionExecutable
```

`composeApp/src/wasmJsMain/resources/_redirects` is copied into the bundle and sends every
path to `index.html`, so a reload on any URL still loads the app.

`wrangler.toml` is the earlier Cloudflare Workers setup, left in place but unused.

## License

[MIT](LICENSE).
