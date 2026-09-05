<div align="center">

# Gunnar's Tools

**Every arrow you carry and don't fire is a gift to whoever kills you. This tells you exactly how many to bring.**

A RuneLite plugin that measures what a Wilderness Slayer kill actually costs you
in ammunition, then turns that into one line — for a trip of N kills, bring X.
It reads only your own inventory and equipment; nothing about anyone else.

[![RuneLite](https://img.shields.io/badge/RuneLite-1.12.38-blue)](https://runelite.net)
[![Java](https://img.shields.io/badge/Java-11-orange)](https://runelite.net)
[![License](https://img.shields.io/badge/license-BSD--2--Clause-green)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-518-brightgreen)](#development)

</div>

> [!NOTE]
> **Not on the Plugin Hub yet.** Build and run it yourself — see
> [Development](#development) below.

<!-- SCREENSHOT: the sidebar lookup open on a search for "spider", showing several
     monsters at different hitpoints, with the overlay panel and the bank's highlighted
     withdraw quantities in the same shot. Save as docs/img/panel.png and replace this
     comment with:  ![Lookup, panel and bank highlight](docs/img/panel.png) -->

---

## What it does

Kill something and Gunnar's Tools watches what leaves your inventory, your worn
equipment and your Dizana's quiver, and works out what that kill actually cost —
not what a table says it should cost. A table keyed on a monster's *name* would
have to average across a whole Wilderness Slayer task, and those span monsters
whose hitpoints differ by as much as a factor of 425: Krystilia's "spider" task
alone runs from a plain Spider at 2 hp to Venenatis at 850. Reading the monster
in front of you instead means there's nothing to get wrong.

An overlay panel says what a trip of N of that monster will cost, with a safety
margin on top:

```
Spindel
last kill                  200 hp
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
have enough, red if you don't. With **Subtract what you carry** on, the number
over a bank slot is what's left to withdraw rather than what the whole trip
needs.

## Look a monster up by name

**The sidebar is where you pick a monster.** Open it — the lookup icon appears
the moment you enable the plugin — start typing, and the list narrows as you go.
You never have to be standing next to the thing you're planning for.

```
[ spider                    ]

Spider                  2 hp
Giant spider            5 hp
Giant spider           32 hp
Spindel               200 hp
Venenatis             850 hp

Spindel               200 hp
for 100 kills            +10%
Rune arrow              2,750
measured             n=37 fair
pinned                  clear
```

Pick one and the answer appears underneath it — the same figure the on-screen
panel shows, minus the spread and the percentile, which are still over there.
`clear` puts the plan back to following whatever you're fighting.

That list is read out of **your own game cache** at startup — no download, no
bundled monster table, no network. It takes about three seconds and then it is
there for the session.

### Spelling is forgiven

You don't have to get the name right. Type `dagganoth` and you get the
Dagannoths:

```
[ dagganoth                 ]

Dagannoth              70 hp
Dagannoth Prime       255 hp
Dagannoth Rex         255 hp
Dagannoth spawn        10 hp
Dagannoth Supreme     255 hp
```

Names are matched exactly first, then by what they start with, then by what they
contain, and only if none of that found anything by how close they are — one
wrong letter in a short name, two in a longer one, with a pair of letters typed
the wrong way round counting as one mistake rather than two. Case and stray
spaces never matter. Because the close matches are a last resort, a search that
was already working is never diluted: `spid` gives you spiders, not Spindel.

### It offers, it doesn't guess

The hitpoints beside every row are the whole reason this is a list rather than a
box that answers. Krystilia's "spider" task means all five of the monsters
further up, and they differ by a factor of 425; a lookup that picked one for you
would be wrong most of the time and confident about it. The same goes for
`dagganoth`: three Kings, an ordinary Dagannoth and a spawn all answer to it, and
which one you meant is not something to be assumed. Where several NPC ids share a
name *and* a size — one monster placed in several regions — they fold into a
single row that says how many, and picking it files the plan under whichever of
them you've actually measured.

Typing into the *Plan for* setting runs the same search, spelling and all, and
settles a name when only one monster answers to it. What it can't do is show you
a list, so a name that means several is reported instead:

```
Gunnar's Tools
several monsters match
pick one in the side panel
```

Open the sidebar after that and it's already searching for what you typed — you
don't have to type it again.

## Before you've killed one

The first version of this plugin could only ever describe the last thing you'd
watched die, which meant it said nothing at a bank with a fresh task — the one
moment you actually want the answer. Three things fix that.

**It plans for a monster you choose.** Find it in the sidebar lookup above,
shift-right-click one in the world and pick **Plan trip**, or type its name into
the *Plan for* setting. That outranks whatever you're fighting, which outranks
whatever you killed last, and it sticks across a logout — so you can choose a
monster at a bank and still have its numbers when you get there.

**It remembers between sessions.** A compact summary of each monster — its
setup, how many were priced, what they cost — lives in your RuneLite profile, so
last week's trip answers today's question.

**It estimates a monster you've never fought**, from one you have, scaled by the
ratio of their hitpoints. Both hitpoint figures come from the game's own data for
the two specific monsters — the one in front of you, or the one you picked out of
the lookup — so nothing has to guess which of Krystilia's thirty-odd "spiders"
she means.

An estimate is never shown as a measurement. It says so on its own line, every
number carries a `~`, and it names what it came from:

```
Venenatis
fighting                   850 hp
estimate             not measured
Rune arrow                ~11,688
  scaled              n=37 fair
  Spindel          200 -> 850 hp
```

Read that as: 37 Spindels of 200 hitpoints cost 25 arrows each, and Venenatis is
four and a quarter times the size. **That last step assumes your damage per shot
is the same against both**, which isn't quite true — defence differs. It's the
one figure here that rests on something other than arithmetic, which is why it's
labelled, why the plugin picks the *closest* monster it has evidence for, and
why both hitpoint figures are on screen. Turn **Estimate before measuring** off
if you'd rather have nothing than that.

And when it can't answer, it says which of the reasons applies — waiting for a
monster, waiting for a kill, hitpoints that didn't resolve, a name in *Plan for*
that several monsters answer to, one that nothing does, or the monster list still
being read in the first few seconds after you log in:

```
Gunnar's Tools
no monster yet
attack or pin one
```

## Settings

| Setting | Default | What it does |
|---|---|---|
| Trip size | 100 | How many kills to plan for |
| Safety margin | 10% | Extra padding added on top of the figure |
| Plan for | *(empty)* | The monster to plan for, by name, spelling forgiven. Empty follows what you're fighting; the sidebar lookup and the "Plan trip" right-click both fill it in for you. A name several monsters answer to is reported rather than guessed at — the sidebar is the only place a choice can be offered |
| Estimate before measuring | On | Show an estimate for a monster you haven't killed yet, scaled from one you have. Always labelled as an estimate |
| Remember between sessions | On | Keep a summary of what each monster cost, so there's an answer at the bank. Turning it off forgets what's stored |
| Subtract what you carry | On | The bank highlight shows what's left to withdraw rather than what the trip needs in total |
| Show the trip panel | On | The panel described above, including what it's waiting for |
| Highlight in the bank | On | The green/red outline in the bank |
| Show the monster lookup | On | The sidebar panel for finding a monster by name — the main way to choose one. Off, the sidebar button goes away and *Plan for* goes back to matching only monsters this plugin has already seen |

The last two sit together under **Choose a monster in the sidebar**, because
they're the same job seen from two sides and the sidebar is the side that works:
it can show you every monster a name could mean, and a settings field can only
tell you there were several.

The margin is the only dial that widens the answer, on purpose. Planning
against your single worst kill instead of the average would triple what you
carry into a place where every surplus arrow is a gift to whoever kills you.
Use the sample count and spread printed next to the figure to judge whether the
default margin is wide enough — four kills wants a lot more padding than four
hundred, and the plugin does not widen it for you.

There's deliberately no "reset after a gear change" setting, which is the
obvious thing to want. Measurements are already filed under the weapon and
ammunition that produced them, so two setups never share an average and nothing
ever has to be thrown away — swap to a special attack weapon and back and the
series you were building carries straight on.

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
Watching the stack go down is automatically correct about all of it. The one
place that gets stretched is the hitpoints estimate above, and it's stretched
from your own measurements, labelled as an estimate, and switchable off.

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
- **Only the totals survive a restart, not the individual kills.** What's kept
  is enough for a rate and not enough for a spread, which is why a restored
  figure is an estimate: there's no honest way to call it measured without the
  samples behind it. The moment this session has measured as many of that
  monster *on the same setup*, the panel switches to the measurement — a stored
  figure about a different weapon never outranks a live one about the weapon in
  your hand, however many kills stands behind it.
- **The hitpoints estimate assumes your damage per shot doesn't change between
  monsters.** It does — defence differs, and so does whether a monster is weak
  to your style. It's the reason that figure is labelled and the reason the
  stretch is printed beside it.
- **A gear change is only ever a change of weapon or ammunition.** Swapping
  gloves, drinking a brew or turning a prayer on changes what a kill costs and
  is invisible here, so a series can quietly straddle a change that mattered.
- **The monster lookup is only as current as your game cache.** It is read from
  the client's own files at startup, so it is right about the version you are
  playing and says nothing about a monster added after your last update. It is
  also read fresh every session rather than cached between them, for the same
  reason. There is still no bundled monster list, on purpose.
- **A name is matched against the cache's spelling, not the wiki's.** If the
  game calls something "Spider (Level 2)" then "spider" finds it and "Level 2
  spider" does not. Type less of the name rather than more.
- **Your Slayer task isn't read.** Trip size is a number you set, not a count
  remaining, and the monster is one you choose rather than one the task names —
  for the same reason as above, since the task only ever names the umbrella.
- **The lookup lists every NPC with a name, not only the ones you can fight.**
  Filtering to monsters would mean deciding what a monster is, and the honest
  signal for that — a populated combat-stats array — is the same one that reads
  as unpopulated for a genuinely all-ones NPC. Showing everything and letting you
  read the hitpoints beside each row is the version that cannot hide the monster
  you were looking for.

## Found a bug?

Please open an issue on GitHub. The most useful report names the monster,
roughly how many kills the estimate is based on, and whether the number looked
too high or too low. Every kill writes a one-line sentence to the debug log with
its basis attached, and pasting a stretch of that log almost always settles it.

---

## Development

```bash
./gradlew build   # compile + package; also proves the JDK + wrapper work
./gradlew test    # runs the 496-test JUnit suite
./gradlew run     # launches a full RuneLite dev client with the plugin loaded
```

Every decision here — kill attribution, consumption, the trip estimate and its
order statistics, the choice of what to say and what to wait for, the
persistence format, both overlays — runs with no game client at all.

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

Target selection, persistence and the estimate added 70 more, of which six came
back green. One wasn't really a mutation — weakening the ledger key's equality
while its hash still separated the keys changes no behaviour, and it went red as
soon as both halves were cut. Four were real holes and are now tests: hitpoints
reading as the cache's untouched `1` being scaled from anyway, twice over on
both sides of the same filter; an estimate of zero reaching the panel; and a
shutdown that emptied the ledger's idea of what was worn but not the plugin's.
The sixth was a guard clause nothing noticed the loss of, because the arithmetic
underneath already answered zero — deleted, with the reason left where it stood.

The monster lookup added forty-nine more, every one of which went red the first
time it was run except one — and that one was the useful result. Deleting the rule
that files a plan under the id you measured *on the weapon you're holding* changed
nothing, because in every test written for it the id with that record was also the
only id with any record at all. The case that separates the two rules — two ids
measured, one of them on a different weapon, and the wrong one being the lower —
is now a test, and the mutation goes red. Where breaking a guard in both directions
made sense, both directions were run: the pin's "offer a way to clear this" was
forced on and forced off, and each was caught by a different test.

One test in the same batch was deleted rather than kept. It asserted that searching
twice gives the same list both times, which two calls to a pure function over an
unchanged map do whatever the ordering is — including when the ordering is the
accident of a hash bucket. It could not fail, and the order it was supposed to be
guarding is pinned explicitly instead.

Reviewing the finished work then turned up a fifth thing no mutation would have
caught, because it was an assumption rather than a line. `startUp()` had been
rebuilding the plan on the thread it was called from, which is fine until the
rebuild starts resolving item names — `PluginManager` calls both lifecycle methods
straight from the Swing thread, and `Client.getItemDefinition` throws off the
client thread in a shipped client. The rebuild is marshalled now, and the
assertion that it stays marshalled is a test.

A review of the finished work then found four things no mutation would have,
because they were absent behaviour rather than undefended lines. The first kill
of a session handed the archive a one-kill record and the archive replaced three
hundred monsters with it, so the stored figure could never grow past whatever the
current session had reached. A remembered figure measured on one weapon
outnumbered and suppressed a live measurement of another — the averaging failure
the whole gear story exists to prevent, reached from the far side. A pinned
monster was stored under one spelling of its name and matched by another, so any
name holding a separator stopped resolving after a restart. And a config change
did its work on the Swing thread while the client thread was inserting into the
maps it walked. All four are fixed, and the twelve mutations covering them are in
the count above.

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
- that shift-right-clicking a monster really does offer **Plan trip**, and that
  clicking it sends nothing to the server;
- that the weapon and ammunition slots of the worn-equipment container read the
  way this plugin assumes, including with a Dizana's quiver equipped;
- that writing the saved summary once per kill isn't noticeable — it goes
  through RuneLite's own config store, which batches its own writes;
- **whether the ammunition decrement for a shot lands on the same game tick as
  a target switch, or the tick after.** This is the one that decides whether the
  two leaks above exist at all, and it's pinned by tests rather than fixed on a
  guess, so that whichever answer turns out true, the fix is a one-line change
  rather than a rewrite;
- that reading the NPC archive costs what it looks like it costs. The sweep is
  four thousand definitions a tick for four ticks, spread rather than done in one
  because `AGENTS.md` says not to scan everything at once — but "four ticks and
  you don't feel it" is an expectation, not a measurement, and a slow machine is
  where it would show;
- that the archive's NPC group is resident by the time the first tick fires. If
  it isn't, the sweep reads a list of unnamed placeholders, throws it away and
  starts again on the next tick, which is what it's built to do — but how many
  times that happens in practice is unknown;
- that the sidebar's row list stays readable for the widest umbrella name in the
  game. Twenty rows is the cap, and "spider" is nowhere near it, but nothing has
  drawn twenty of them yet.

## License

BSD 2-Clause — see `LICENSE`.

---

<div align="center">
<sub>Reads your own inventory only. Sends nothing to the server. Not affiliated with Jagex.</sub>
</div>
