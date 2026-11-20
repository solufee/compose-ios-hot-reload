# #20: which WARM cases could be safe - written review (plugin 0.2.4)

Every case that the review covers, today's verdict (classifier on the #28 branch, `cases.py` probes), the decision and
the reason. "yes" means the case is relaxed in 0.2.4 after a case-table entry with a counter-example and a simulator
check (`manual_hot.py`, three-module project); "already hot" means the classifier never flagged it (checked here so
that it is covered by a case and a simulator step); "no" keeps the warm restart.

How the runtime treats a patch (what the reasons below rely on): a patch brings new code for changed and new
functions; old functions are redirected to their new versions; classes keep their type info unless they are new;
live objects are migrated field by field by name; a top-level property is initialized once per file, on the first
access to that file (its init guard lives with the file); callers of a changed function are recompiled into the patch
(REPORT §33).

| # | Case | Today | Decision | Reason |
|---|---|---|---|---|
| 1 | private / internal top-level function added | hot | already hot | new symbol; only new code calls it (`add-private-top-fun`, `add-top-fun`) |
| 2 | new private member of a final class | hot | already hot | direct call, no dispatch table (`add-private-method`) |
| 3 | new top-level class, object (also with a property), nested class | hot | already hot | new type info and its own instance global, initialized on first use (`add-class`, `add-nested-class`, `probe-new-file-like-object`) |
| 4 | new file with functions and classes only | hot | already hot | same as 1-3 |
| 5 | default value of a parameter changed | hot | already hot | only the `$default` body changes (`default-value-only`) |
| 6 | annotation-only change (`@Deprecated`, `@Suppress`, `@OptIn`, ...) | hot | already hot | annotations are not part of the layout or the symbol |
| 6a | `@Composable` added or removed | warm/hot | (#28) | the compiled signature changes: judged as a composable signature change (needs forceRecompose) |
| 6b | `@ExportObjCClass` added to or removed from an existing class | hot | no change possible | the annotation is not recorded in the klib metadata (checked with `klib dump-metadata`), so neither this case nor the older `objcExported` flag can see it. Such classes are Kotlin subclasses of ObjC classes: any member change on them is already warm (`platform/*` supertype); only adding the annotation alone stays hot. Listed as a known limit |
| 7 | `const val` value changed | hot | already hot | callers inline the value and are recompiled (`const-change`) |
| 8 | `const val` added or removed | warm | **yes** | a constant has no storage to initialize: callers inline the value; the accessor is a plain function |
| 9 | inline member function **added** to a final class / object | warm | **yes** | no caller outside the patch can have inlined a function that did not exist; changing an existing inline body stays warm |
| 10 | suspend member function **added** to a final class / object | warm | **yes** | no running coroutine can be suspended in a function that did not exist; changing an existing suspend body stays warm |
| 11 | final (non-open, non-override) member added to an open or abstract class | warm | **yes** | final members are called directly, not through the vtable; open members, overrides and interface members stay warm |
| 12 | subclass added to a sealed class | hot | already hot | a new class; `when` over the sealed type is recompiled with its callers (`probe-sealed-subclass-added-2`) |
| 13 | companion object added to an existing class | hot | already hot | the companion is an object with its own instance global; the outer class's layout does not change (`probe-companion-added`) |
| 14 | enum entry appended | warm | no | the entries array (`values()`, `entries`) is built once when the enum is first used; a new entry would be missing there |
| 15 | top-level property added to an existing file | warm | no | the file's initializer already ran; the new property would keep its zero value |
| 16 | top-level property in a new file | warm | no for now | would be safe in principle (the new file's init guard is new), but the snapshot does not record files; needs file information in the snapshot and a check by the low-level line |
| 17 | property without a backing field (getter only) added | warm | no | the metadata does not say whether a property has a backing field |
| 18 | field added / removed / reordered, constructor changed | warm | no | instance layout of live objects |
| 19 | class removed; supertypes or modality changed | warm | no | live objects keep the old type info |
| 20 | typealias retargeted or removed | warm | no | the expanded signatures change; covered by #28 for the functions themselves, but the alias line is still reported |
| 21 | private <-> non-private top-level function | warm | no | different symbol names (internal <-> public is already hot) |
| 22 | inline / suspend body or signature changed | warm | no | callers carry the old inline body; running coroutines keep the old state machine |
| 23 | members of enum classes and Kotlin subclasses of ObjC classes | warm | no | dispatch tables / selector registration |

Implemented in 0.2.4: 8, 9, 10, 11. 6b could not be implemented (not visible in the metadata).

## Results (toolchain 2.5.255-hotreload.7)

| Evidence | Result |
|---|---|
| `evidence/cases-20-toolchain7.txt`: case table with the #20 entries - `relax-*` (8: const added and removed; 9: inline method; 10: suspend method on a class and on an object; 11: final method on an open class, and on an abstract class, which was warm before), `covered-*` (3, 6b, 12, 13 confirmed hot) and the counter-examples `counter-*` (val added to an existing file, open method, abstract method, body of an existing inline and suspend function, final method on an enum: all warm) | 93/93 |
| `evidence/manual-hot-20-three-modules.txt`: `manual_hot.py` on the three-module project with 8 new steps per module - const val added and used; inline method added to a final class and called; suspend method added to an object and called (runBlocking); a new open class with a subclass, then a final method added to the open class and called on the subclass instance; a new sealed class, then a subclass added with the exhaustive `when` updated; a companion object added to an existing class and used | core 26/26, ui 26/26 (same PID, on screen, 0 crash lines; ui from a fresh launch) |
