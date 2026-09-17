# Gatelangs

Walk every street in a city. Gatelangs tracks your position, matches it against the
real OpenStreetMap road network, and paints each stretch of road as you cover it.

Kotlin Compose Multiplatform, targeting **Desktop (JVM)** and **Web (Wasm)**.

See [PLAN.md](PLAN.md) for the architecture and the order of work.

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
