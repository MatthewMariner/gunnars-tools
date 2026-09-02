# Gunnar's Tools

A RuneLite plugin for Old School RuneScape. **This repository currently holds
only the plugin skeleton** — the Gradle build, the plugin/config wiring, and a
test harness proven to run. No feature is implemented yet, and nothing here is
submitted to, or available on, the Plugin Hub.

## What it will do

Wilderness Slayer trips are a carrying-capacity problem: bring too little
ammunition and a trip ends early; bring too much and inventory space that
could hold loot or supplies is wasted instead. The first tool in "Gunnar's
Tools" will estimate how much ammunition — arrows, runes, revenant ether,
whatever the monster's combat style calls for — a trip needs for a given
number of kills, by **measuring the player's own consumption per kill** as
they fight rather than modelling it from wiki data. Bank highlighting for the
estimated amount is a later milestone, once the measurement approach itself is
validated.

Nothing above is implemented in this repository yet. When it lands, this
section will describe what actually ships, not what is planned.

## Development

```bash
./gradlew build   # compile + package; also proves the JDK + wrapper work
./gradlew test    # runs the JUnit suite
./gradlew run     # launches a full RuneLite dev client with the plugin loaded
```

Compile target is Java 11 bytecode regardless of which JDK compiles it —
`build.gradle` pins `options.release.set(11)`. The RuneLite client version is
pinned explicitly in `build.gradle` rather than left on `latest.release`, so a
local build is reproducible; see the comment there for why and how to bump it.

See `AGENTS.md` for the fuller set of conventions this repository follows.

## License

BSD 2-Clause — see `LICENSE`.
