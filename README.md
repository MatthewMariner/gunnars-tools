<div align="center">

# Gunnar's Tools

**Every arrow you carry and don't fire is a gift to whoever kills you. This tells you exactly how many to bring.**

A RuneLite plugin that measures what a Wilderness Slayer kill actually costs you
in ammunition, then turns that into one line — for a trip of N kills, bring X.
It reads only your own inventory and equipment; nothing about anyone else.

[![RuneLite](https://img.shields.io/badge/RuneLite-1.12.38-blue)](https://runelite.net)
[![Java](https://img.shields.io/badge/Java-11-orange)](https://runelite.net)
[![License](https://img.shields.io/badge/license-BSD--2--Clause-green)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-206-brightgreen)](#development)

</div>

> [!NOTE]
> **Not on the Plugin Hub yet.** Build and run it yourself — see
> [Development](#development) below.

<!-- SCREENSHOT: the overlay panel right after a kill, framed alongside the bank with its
     highlighted withdraw quantities in the same shot. Save as docs/img/panel.png and
     replace this comment with:  ![Panel and bank highlight](docs/img/panel.png) -->

---

## What it does

Kill something and Gunnar's Tools watches what leaves your inventory, your worn
equipment and your Dizana's quiver, and works out what that kill actually cost —
not what a table says it should cost. A table keyed on a monster's *name* would
have to average across a whole Wilderness Slayer task, and those span monsters
whose hitpoints differ by as much as a factor of 425: Krystilia's "spider" task
alone runs from a plain Spider at 2 hp to Venenatis at 850. Reading the monster
in front of you instead means there's nothing to get wrong.

Once it has seen enough kills, an overlay panel says what a trip of N of that
monster will cost, with a safety margin on top:

```
Spindel
for 100 kills                +10%
Rune arrow                  2,750
  25.0/kill           n=37 fair
  spread              24/25/26/40
  at 90th pct               2,860
```

`spread` is the cheapest kill, the median, the ninetieth percentile and the
dearest, in that order — four close numbers mean a monster that costs about the
same every time, and a wide spread means it doesn't. The word next to the
sample count (`anecdotal` / `thin` / `fair` / `solid`) is a reading aid for how
much to trust the figure, not a statistical guarantee — the raw count `n` is
always printed beside it.

Under area damage, one spell can kill several monsters in one priced window. The
plugin doesn't split that window's cost between them — it can't know how to —
but it does divide by how many of the *same* monster died in it, so the number
above is priced per monster killed rather than per window. With no area damage
the two are identical.

The same figures also outline the items you need in the bank: green once you
have enough, red if you don't.

Not implemented, and deliberately: an estimate for a monster you've never
fought, live Slayer task reading, or anything that survives a restart — see
Known limitations.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Trip size | 100 | How many kills to plan for |
| Safety margin | 10% | Extra padding added on top of the measured amount |
| Show overlay panel | On | The per-monster panel described above |
| Show bank highlight | On | The green/red outline in the bank |

The margin is the only dial that widens the answer, on purpose. Planning
against your single worst kill instead of the average would triple what you
carry into a place where every surplus arrow is a gift to whoever kills you.
Use the sample count and spread printed next to the figure to judge whether the
default margin is wide enough — four kills wants a lot more padding than four
hundred, and the plugin does not widen it for you.

## Why

Wilderness Slayer trips are a carrying-capacity problem: dying to a PKer keeps
three items unskulled and none skulled, so an arrow you carry in and don't fire
is an arrow you're quite likely to hand over. Bring too few and the trip ends
early; bring too many and you're carrying a surplus for whoever kills you.
(Whether an ammunition *stack* specifically is always lost outright isn't
verified against the wiki, and this plugin doesn't depend on it either way —
carrying less into the Wilderness is good regardless of which is true.)

The governing decision is **measure, do not model**. Predicting consumption from
accuracy, damage and attack speed produces error bars too wide to act on, and it
silently ignores whatever your gear, prayers and boosts are actually doing.
Watching the stack go down is automatically correct about all of it.

## Known limitations

- **Revenant ether isn't measured.** The charged Wilderness weapons (craw's bow
  and webweaver, viggora's chainmace and ursine, thammaron's sceptre and
  accursed) spend ether from a charge counter, not from your inventory —
  charging is a bulk action separate from any one kill, and there's no reliable
  per-kill read on it yet. The plugin's estimates never include it.
- **Ammo you pick back up can't be told from ammo the monster dropped**, so
  gains are never subtracted out. The published figure is *gross* consumption,
  which errs toward carrying too much rather than too little; the recovered
  amount is shown alongside it.
- **The looting bag is an untracked source of noise.** Moving a stack into it
  mid-fight books as consumption on whatever you're currently fighting, even
  though nothing was fired — normal Wilderness Slayer practice, and currently
  the largest single source of noise in the numbers.
- **A monster of the same id, finished by someone else while you're fighting
  elsewhere, is counted as if it died to your own splash damage.** A narrow
  case, but on a Slayer task — where everything in the spawn shares one id —
  it's the shape most likely to happen, and it slightly under-counts cost.
- **A kill stolen by another player still counts**, if you damaged it and it
  was your target. Wilderness multi-combat makes this unavoidable without
  reading loot.
- **A monster that despawns without ever dying is recorded as "abandoned", not
  as a kill.** Many abandoned fights next to few kills is the sign something's
  off.
- **Switching targets costs one shot out of the average at the moment of the
  switch, and a shot fired the tick right after a kill is discarded entirely**
  rather than counted anywhere. Both understate cost slightly, and both come
  from the same cause: the game resolves deaths before it applies your click.
- **Nothing survives a restart.** Kills, samples and the confidence word all
  live in memory for the session — a trip planned in your first half hour is
  planned off that half hour alone.
- **The plan is only ever for the monster you killed most recently.** A task
  that mixes species — a Wilderness "spider" task spans several — plans for
  whichever you finished last; the other's numbers are still being kept, just
  not shown.
- **The bank highlight doesn't know what's already in your inventory.** It
  compares the trip's requirement against what's banked, not against what
  you're already carrying.

## Found a bug?

Please open an issue on GitHub. The most useful report names the monster,
roughly how many kills the estimate is based on, and whether the number looked
too high or too low. Every kill writes a one-line sentence to the debug log with
its basis attached, and pasting a stretch of that log almost always settles it.

---

## Development

```bash
./gradlew build   # compile + package; also proves the JDK + wrapper work
./gradlew test    # runs the 206-test JUnit suite
./gradlew run     # launches a full RuneLite dev client with the plugin loaded
```

Every decision here — kill attribution, consumption, the trip estimate and its
order statistics, both overlays — runs with no game client at all.

**Every guard has been proven by breaking it.** 93 separate mutations have been
applied to the shipped source one at a time — a ceiling turned into a floor, a
denominator swapped, a config key renamed — and the suite watched go red for
each. Eight survived the first time they were run, and seven became real fixes:
a spread test built on too few samples, a worst-case figure reading the wrong
end of the array, an untested zero-vs-negative trip size, the tick wiring itself
never exercised end to end, a dead method overload, an overlay handed its data
unwrapped, and an unchecked promise about co-victim counts. The eighth is left
standing on purpose — a field snapshot that now reads identically to the value
it copies, kept as a guard against reintroducing the ordering bug it fixed, with
the reason written at that line. A later independent review ran eight more
mutations; three survived and were fixed, and two more were probes that settled
the target-switch question above into a pinned fact rather than a guess.

Compile target is Java 11 bytecode. The RuneLite client version is pinned in
`build.gradle` (1.12.38) rather than left on `latest.release`, so a local build
is reproducible — see the comment there for how to bump it.

**Filing the Hub submission?** Swap the "not on the Plugin Hub yet" callout above
for the standard install instructions in the same change — that line stops
being true the moment this is listed.

See `AGENTS.md` for the fuller set of conventions this repository follows.

### Wanted from a real client

Reasoned from the API, not yet observed in game:

- that Dizana's quiver decrements its own counter when a matching weapon fires
  from it;
- that a container-change event for a given tick always arrives before that
  tick's game tick (if not, the cost is a tick of latency rather than a wrong
  number — the meter diffs whole containers rather than accumulating events);
- that a normal ranged kill produces exactly one death event, with an arrow
  count matching what the game's own ammo counter says;
- that the bank highlight's quantity lands somewhere legible over the item icon
  rather than under the stack size the game already draws there;
- that the panel stays legible mid-fight at up to fourteen lines, which is what
  a three-item area-damage record produces;
- **whether the ammunition decrement for a shot lands on the same game tick as
  a target switch, or the tick after.** This is the one that decides whether the
  two leaks above exist at all, and it's pinned by tests rather than fixed on a
  guess, so that whichever answer turns out true, the fix is a one-line change
  rather than a rewrite.

## License

BSD 2-Clause — see `LICENSE`.

---

<div align="center">
<sub>Reads your own inventory only. Sends nothing to the server. Not affiliated with Jagex.</sub>
</div>
