# Gunnar's Tools

A RuneLite plugin for Old School RuneScape. **It measures how much ammunition
each kill of a given monster actually costs you.** Nothing here is submitted
to, or available on, the Plugin Hub, and there is no user interface yet — the
measurement writes to the debug log.

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

Milestone 1: the measurement, and only the measurement.

- **Kill attribution.** A death becomes your kill only if the game marked a
  hitsplat on that monster as yours *and* it was the monster you were fighting.
  A despawn is not a death. Every event is buffered and resolved at the game
  tick boundary, so no ordering within a tick is load-bearing.
- **Consumption measurement.** Inventory, worn equipment and Dizana's quiver
  are summed into one multiset of stackable items and differenced once per
  tick, so equipping a stack — a loss in one container and a gain in another —
  is arithmetically invisible, as it deserves to be.
- **A per-monster record**, in memory for the session: consumed per kill by
  item id, the sample count behind it, and the monster's six combat stats read
  from the live NPC.

Not implemented, and deliberately: the "bring X for N kills" projection, bank
highlighting, cold-start estimates, live Slayer task reading, and any user
interface. Those are later milestones.

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
- **Area attacks under-count kills.** When a spell damages four monsters and
  three of them die, one window of consumption cannot be split four ways
  without inventing the split. Those deaths are counted separately as
  "unattributed" — the cost-per-kill figure stays sound and the kill count is
  understated, visibly.
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

### Wanted from a real client

Reasoned from the API, not yet observed in game:

- that Dizana's quiver decrements `InventoryID.DIZANAS_QUIVER_AMMO` when a
  matching weapon fires from it;
- that `ItemContainerChanged` for a given server tick always precedes that
  tick's `GameTick` (if it does not, the only cost is a tick of latency — the
  meter compares full container contents rather than accumulating per-event
  differences, so a late event can be neither doubled nor lost);
- that a normal ranged kill produces exactly one `ActorDeath`, and that its
  arrow count matches what the ammo counter in game says.

## Development

```bash
./gradlew build   # compile + package; also proves the JDK + wrapper work
./gradlew test    # runs the JUnit suite
./gradlew run     # launches a full RuneLite dev client with the plugin loaded
```

Kill attribution and consumption measurement are unit-tested without a game
client and are held to that standard on purpose — the decisions that most
easily go quietly wrong are the ones worth being able to run a hundred times.
What is left needing a client is small and named above.

Compile target is Java 11 bytecode regardless of which JDK compiles it —
`build.gradle` pins `options.release.set(11)`. The RuneLite client version is
pinned explicitly in `build.gradle` rather than left on `latest.release`, so a
local build is reproducible; see the comment there for why and how to bump it.

See `AGENTS.md` for the fuller set of conventions this repository follows.

## License

BSD 2-Clause — see `LICENSE`.
