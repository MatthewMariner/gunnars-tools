# Gunnar's Tools

A RuneLite plugin for Old School RuneScape. **It measures how much ammunition
each kill of a given monster actually costs you, and turns that into "for a trip
of N, bring X".** Nothing here is submitted to, or available on, the Plugin Hub.

## Why

Wilderness Slayer trips are a carrying-capacity problem, and a lopsided one.
Dying to a PKer keeps three items unskulled and none skulled, and an
ammunition stack never occupies one of those slots, so **every arrow you carry
in and do not fire is simply given away.** Bring too few and the trip ends
early; bring too many and you hand the surplus to whoever kills you.

The answer wanted is "for N kills of this monster, how much do I need?", and
the decision that governs the whole design is **measure, do not model.**
Predicting consumption from accuracy × damage × attack speed produces error
bars wide enough to make the answer un-actionable, and it silently omits
whatever your gear, prayers and boosts are doing. Watching the stack go down is
automatically correct about all of it.

## What is implemented

**The measurement (M1).**

- **Kill attribution.** A death becomes your kill only if the game marked a
  hitsplat on that monster as yours *and* it was the monster you were fighting.
  A despawn is not a death. Every event is buffered and resolved at the game
  tick boundary, so almost no ordering within a tick is load-bearing — the one
  exception is which of two simultaneous hitsplats opens a window when none was
  open, described in `KillAttribution`'s class doc.
- **Consumption measurement.** Inventory, worn equipment and Dizana's quiver
  are summed into one multiset of stackable items and differenced once per
  tick, so equipping a stack — a loss in one container and a gain in another —
  is arithmetically invisible, as it deserves to be.
- **A per-monster record**, in memory for the session: consumed per kill by
  item id, the sample count behind it, and the monster's six combat stats read
  from the live NPC.

**The estimate and its uncertainty (M2).** Every kill now leaves a sample
behind rather than only moving a total, and the record publishes a
`ConsumptionEstimate` per item id: the gross rate, the sample count, how many of
those kills spent any of the item at all, the recovered volume, and the spread.

**The answer (M3).** `TripPlanner` turns a record into one line per item —
"for a trip of 100, bring 2,750" — with a configurable safety margin, ordered
biggest first. `TripPlan.describe` writes it as a sentence with its basis
attached, which is what lands in the debug log on each kill.

**Bank highlighting (M4).** The items the trip needs are outlined in the bank
with the quantity to withdraw, green when the bank holds enough and red when it
does not.

**Two surfaces.** A small overlay panel for the monster you last killed, and the
bank highlight. Both are switchable; both draw from a projection computed once
per kill rather than once per frame.

Not implemented, and deliberately: cold-start estimates for a monster you have
never fought, live Slayer task reading, and anything that survives a restart.

### What you see

The panel appears once you have killed something, and is about whatever you
killed last. It is at most six lines:

```
Spindel
for 100 kills                +10%
Rune arrow                  2,750
  25.0/kill           n=37 fair
  spread              24/25/26/40
  at 90th pct               2,860
```

`spread` is the cheapest kill, the median, the ninetieth percentile and the
dearest, in that order. Four numbers close together mean a monster that costs
the same every time; `10/50/90/900` means one that does not, and no single
figure can say that. `at 90th pct` is what the trip would cost if every kill
were as dear as the ninetieth percentile — see below for the one case where it
is withheld instead of guessed at.

Two settings, plus a switch for each surface: **trip size** (how many monsters,
default 100) and **safety margin** (extra on top, default 10%). The honest input
to the margin is the sample count and the spread printed next to the figure: a
number measured over four kills wants a much wider margin than one measured over
four hundred, and the plugin does not widen it for you.

### How the spread is expressed, and why not a confidence interval

The governing principle is *measure, do not model*, and it applies to the error
bars as much as to the figure. So the spread is **nearest-rank order
statistics** over the observed per-kill samples — the cheapest kill, the median,
the ninetieth percentile, the dearest — and every one of those is a quantity
that actually happened on an actual kill. Nothing is interpolated and no
distribution is assumed.

A mean and a standard deviation, read as a normal interval, was the obvious
alternative and it would have been wrong in a way that matters. Ammunition per
kill is discrete, floored at zero and right-skewed: most kills cost about the
same, an unlucky one costs several times that, and none can cost less than
nothing. A symmetric interval fitted to that reports a lower bound below zero
and understates the long tail — which is the side you actually have to carry.

Next to the spread sits a word — `anecdotal`, `thin`, `fair`, `solid` — derived
from the sample count alone. It exists so a figure from two kills and a figure
from two hundred cannot look alike, and it is **a reading aid rather than a
statistical claim**: nothing here says a `fair` figure is within any particular
distance of the truth. The raw `n` is printed beside it everywhere.

The safety margin is the only dial that widens the answer, and that is
deliberate. Planning against the ninetieth-percentile kill instead of the mean
would treble what you carry into a place where every surplus arrow is a gift to
whoever kills you, because a hundred kills is not a hundred bad kills. The
ninetieth-percentile total is offered beside the recommendation instead, and
only when it is exactly true — see below.

### Cost per monster, not cost per attributed kill

This is the claim the previous README had to correct, so the correction is now
in the code rather than only in prose.

Under area damage a single window of runes kills several monsters. The plugin
still refuses to split that window — one window cannot be divided between two
monsters without inventing the division — but it now **counts** the other
monsters that died inside the window, and the kill carries that count. So
`consumed / (1 + co-victims)` is a cost per monster derived entirely from
measurements: no split invented, just the right denominator over the same
window. The estimate publishes both rates, labelled, and the trip projection
multiplies the per-monster one, because "how many will I kill" is a question
about monsters. With no area damage the two are the same number, exactly, which
is every ranged and melee trip there is.

**The co-victim count is not `unattributedDeaths`,** which is what this file
previously suggested would recover the figure. That formula leaks in two ways
this one does not. An unattributed death is filed against the *dead* monster's
id, so a cross-species barrage would apply the correction to a record holding
none of the ammunition. And a monster you damaged, walked away from, and which
died later to somebody else is an unattributed death whose ammunition went into
the *abandoned* column — counting it raises the denominator without raising the
numerator, and the per-monster figure comes out low. Low is the direction that
ends a trip early. Both are covered by tests.

### Bank highlighting: the supported mechanism

`net.runelite.client.ui.overlay.WidgetItemOverlay` with `showOnBank()`. The base
class asks `OverlayManager.getWidgetItems()` for the items currently laid out and
calls `renderItemOverlay` once per visible one, already clipped to the container;
`showOnBank()` is a `drawAfterLayer` on the two bank item layers. Three plugins
shipped with the client do exactly this — `ItemIdentificationOverlay` (which
pairs `showOnInventory()` with `showOnBank()`, the closest match),
`InventoryTagsOverlay` and `ItemChargeOverlay`. All of that was read out of the
pinned 1.12.38 client jar with `javap` rather than remembered.

Nothing in it touches a menu entry, a click zone or a hidden component, so none
of `AGENTS.md`'s interface or menu restrictions apply: it draws a number over an
item you are already looking at.

### Why the stats come from the live NPC

There is no bundled stats table and there is not going to be one. Nineteen of
Krystilia's thirty-six tasks are umbrellas — "spider" spans Venenatis, Spindel
and every giant spider in the game; "bear" spans Callisto, Artio and ordinary
bears; "skeleton" spans Vet'ion, Calvar'ion and the Wilderness skeletons — and
hitpoints across one of those differ by a factor of ten to twenty. A generated
table keyed on a task name has to pick one, and a draft of exactly that
resolved "spider" to a giant spider with **two** hitpoints. Asking the monster
standing in front of you cannot make that mistake, because there is no name to
resolve.

## Known limitations

Written down rather than rounded off.

- **Revenant ether is not measured, and cannot be by this approach.** The
  charged Wilderness weapons — craw's bow and webweaver, viggora's chainmace
  and ursine, thammaron's sceptre and accursed — spend ether from a charge
  counter (`VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY`), not from the
  inventory. Ether only ever leaves a container when you *charge* the weapon,
  which is one bulk action at a bank rather than a per-kill cost. A container
  diff therefore reports zero ether per kill, correctly and uselessly. Reading
  the varbit would work, but a swap between two charged weapons moves that
  counter for reasons that are not attacks, and shipping an unverified channel
  that can charge thousands of units to one kill is worse than a stated gap.
  The plugin's tags no longer claim ether for the same reason.
- **Ammunition picked back up cannot be told from ammunition dropped by the
  monster.** Both are a gain in a stack, and no ground item a plugin can read
  carries ownership. So gains are never netted off: the published figure is
  *gross* consumption, which errs toward carrying too much, and the recovered
  volume sits beside it as a disclosed contaminant. Erring the other way ends a
  trip early, which is the failure the plugin exists to prevent.
- **The looting bag is an untracked, and previously undocumented, contaminant.**
  `InventoryID.LOOTING_BAG` is correctly left out of the summed containers —
  it is not the player carrying the ammunition, it is storage — but that also
  means moving a stack from the inventory into the bag is a real decrement of
  the tracked sum, and if it happens while a window is open it books as
  consumption on whatever you are currently fighting. Bagging loot mid-fight
  is normal Wilderness Slayer practice, and when the monster you are on drops
  the ammunition you use, the pickup off its corpse books as a gain and
  bagging that same stack a moment later books as consumption of the same id
  — both charged to that monster's record, neither one a shot fired. This is a
  larger source of contamination than several of the limitations already
  listed here.
- **Area attacks still under-count kills; the cost per monster is now corrected
  for it, and `consumedPerKill` still is not.** When a spell damages four
  monsters and three die, one window cannot be split four ways without
  inventing the split, so those deaths are counted separately as
  "unattributed" and the whole window's ammunition is charged to the fourth.
  `consumedPerKill` is therefore cost per *attributed* kill — four barrages of
  four runes, each killing three monsters, is 16 runes over 12 monsters, a true
  1.333 each, and `consumedPerKill` reports 4.0. `consumedPerMonster` and every
  figure the trip projection multiplies now divide by the co-victim-corrected
  count and report 1.333. Both are published and both are labelled; the panel
  shows the second and names the first when they differ.
- **A monster you damaged and never abandoned, which somebody else finishes
  while you are mid-fight elsewhere, is counted as a co-victim.** It is
  indistinguishable from one caught by your splash damage — both are a death of
  something you damaged, during a window that is open. Each one dilutes the
  per-monster figure by one monster. That is a much narrower leak than counting
  every unattributed death (a fight you *did* abandon is excluded, because its
  ammunition went to the abandoned column), but it is a leak, and it errs
  toward carrying too little.
- **A kill stolen by another player still counts.** If you damaged it and it
  was your target, it is recorded, whether or not you got the loot or the
  Slayer count. Multi-combat Wilderness makes this unavoidable without reading
  loot, which is a later milestone's problem.
- **A monster that dies without the client receiving a zero-health update**
  produces a despawn and no death, and lands in the "abandoned" column instead
  of the kill count. That column doubles as the detector: a monster with many
  abandoned fights and few kills is the symptom.
- **Switching targets mid-tick charges one attack to the wrong monster.**
  Interaction changes are applied after deaths resolve, which costs one shot at
  each switch and saves a whole kill when the previous target dies on the same
  tick. On a Slayer task the two monsters share an id and the error is zero.
- **Everything is still in memory, and the session is the sample.** Nothing is
  written to disk, on purpose: a plugin that writes files is reviewed by hand at
  the Plugin Hub instead of automatically. So the confidence word resets to `no
  data` every time the plugin is restarted, and a trip planned in the first
  half-hour of a session is planned off whatever that half-hour measured.
- **The plan is for the monster you last killed, and only that one.** A task
  with two monsters in it — a Wilderness "spider" spawn that mixes ids — plans
  for whichever you finished most recently. The other's record is still being
  kept; nothing surfaces it.
- **The bank highlight cannot tell you what you already have on you.** It
  compares the trip's requirement against the *banked* stack, not against the
  banked stack plus the four hundred arrows already in your quiver.

### Wanted from a real client

Reasoned from the API, not yet observed in game:

- that Dizana's quiver decrements `InventoryID.DIZANAS_QUIVER_AMMO` when a
  matching weapon fires from it;
- that `ItemContainerChanged` for a given server tick always precedes that
  tick's `GameTick` (if it does not, the only cost is a tick of latency — the
  meter compares full container contents rather than accumulating per-event
  differences, so a late event can be neither doubled nor lost);
- that a normal ranged kill produces exactly one `ActorDeath`, and that its
  arrow count matches what the ammo counter in game says;
- that `showOnBank()` covers the bank layout this account actually sees, and
  that the withdraw quantity lands somewhere legible over the item icon rather
  than under the stack size the game already draws there;
- that the panel is legible at the size and position it defaults to, and that
  six lines is not five too many mid-fight.

## Development

```bash
./gradlew build   # compile + package; also proves the JDK + wrapper work
./gradlew test    # runs the JUnit suite
./gradlew run     # launches a full RuneLite dev client with the plugin loaded
```

199 tests, all of them runnable with no game client — kill attribution,
consumption measurement, the estimate and its order statistics, the projection
and its arithmetic, and both overlays' switches and the colour the bank
highlight chooses. That is on purpose: the decisions that most easily go
quietly wrong are the ones worth being able to run a hundred times.

Every guard here has been proven by breaking it. Ninety-three distinct
mutations were applied to the shipped source one at a time — the ceiling turned
into a floor, the per-monster denominator swapped for the per-kill one, the
co-victim count never cleared, the two bank colours transposed, a config key
renamed — and the suite was watched go red for each, with the applied diff
recorded as evidence that the mutation actually landed.

Eight of them survived the first time they were run, and seven of those became
changes rather than excuses:

- a spread test built on too few samples. Below ten kills the ninetieth
  percentile and the maximum are the same sample, and below eleven so are the
  minimum and the tenth, so a spread test on a handful of kills stays green with
  any of the four statistics wired to the wrong end of the array;
- the worst-case figure reading the single worst kill instead of the shoulder,
  hidden by the same collision;
- a trip size of zero being harmless while a *negative* one was not, so the
  guard was tested at zero and never at the value it exists for;
- the plugin's own tick wiring, which nothing had ever exercised end to end —
  a tick that handed the attribution an empty delta would have measured nothing,
  silently, forever;
- a one-argument `recordKill` overload with no production caller at all, deleted
  rather than tested;
- the estimate list and the bank lookup being handed to an overlay unwrapped;
- the promise that only a kill ever carries a co-victim count.

The eighth is an equivalent mutant and is left standing on purpose: the
`windowOwner` snapshot in `KillAttribution` reads identically to the field it
copies, because nothing in that loop writes the field any more. It is there to
stop the next edit reintroducing the ordering bug the deferred application
fixes, the comment at that line says exactly that, and no test can be written
that fails when it is swapped back.

What still needs a client is small, and is named above.

Compile target is Java 11 bytecode regardless of which JDK compiles it —
`build.gradle` pins `options.release.set(11)`. The RuneLite client version is
pinned explicitly in `build.gradle` rather than left on `latest.release`, so a
local build is reproducible; see the comment there for why and how to bump it.

See `AGENTS.md` for the fuller set of conventions this repository follows.

## License

BSD 2-Clause — see `LICENSE`.
