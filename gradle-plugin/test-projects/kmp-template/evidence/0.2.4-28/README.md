# #28: signature changes of top-level and final functions are hot - evidence (plugin 0.2.4, toolchain .7)

Rule (Issue #28, basis REPORT §33): every caller of a function whose signature changed is recompiled into the patch,
so the new code only calls the new symbol. HOT for top-level functions and final members of final classes, objects
and companions (parameters added, removed or retyped, default values, return types, `expect`/`actual` functions,
adding or removing an `expect fun` pair). A `@Composable` is HOT only while the running app recomposes fully after a
reload (forceRecompose, default on); its own `remember` state starts over once and the HOT line says so
(`compat: note: … resets once`). Still WARM: inline, suspend, open/override/interface members, members of enum and
ObjC classes, constructors and fields, `expect`/`actual` classes (and objects, interfaces, properties), `@Composable`
with forceRecompose off.

The plugin passes the forceRecompose value the running app was **launched** with (`build/hotreload/launched-force-recompose.txt`)
to the classifier as `--force-recompose`.

| File | What | Result |
|---|---|---|
| `accept-kmp-signatures-toolchain7.txt` | wizard template `--multi --force-recompose --signatures`: expect/actual signature, adding/removing an `expect fun` pair and using it -> HOT (same PID, on screen); an `expect class` pair -> WARM (counter-example); section 4s: top-level parameter added (callers in two files), removed, return type `String -> List<String>`, final member parameter, `@Composable` parameter (remember counter 2 -> 0 recorded, the caller's state kept, the composable keeps working), cross-module callers (base -> core, core `@Composable` -> shared, with the note), no crash; 4f: forceRecompose off -> a `@Composable` signature change is WARM and not sent | 55/55 |
| `../../classifier-cases/evidence/cases-28-toolchain7.txt` | classifier case table: 28 new cases for #28 (hot: final/object/companion member and top-level signature changes, overload added, `@Composable` incl. member, private and annotation added; default value only; warm counter-examples: `@Composable` with forceRecompose off (4), inline, suspend, open, interface, abstract, ObjC subclass, enum member, constructor) | 76/76 |
| `../../classifier-cases/evidence/manual-hot-28-three-modules.txt` | three-module project (app, ui, core), `manual_hot.py` with 7 new signature steps per module: top-level parameter added/removed, return type, final member parameter, object member return type, companion member parameter without default (caller changed), final member removed | core 18/18, ui 18/18 (ui from a fresh launch: restoring core's file removes classes, which warm-restarts first) |
| `../../composite/evidence/accept-composite-28-toolchain7.txt` | composite build, phases A-F: included core's expect signature and `expect fun` pair add/remove -> HOT, `expect class` pair -> WARM; release digest unchanged | 50/50 |

Toolchain .8 (#27, forced invalidation before the next frame) closes the remaining window for `@Composable`
(REPORT §33 item 5); the `@Composable` checks are repeated on .8 before 0.2.4 is released.
