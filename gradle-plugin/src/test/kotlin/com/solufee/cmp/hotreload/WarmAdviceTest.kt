package com.solufee.cmp.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #18: every WARM reason the classifier and the plugin print gets its own actionable sentence. The reason lines are
 * copied from real runs (classifier-cases/evidence/cases-28-toolchain7.txt, the acceptance scripts' output).
 */
class WarmAdviceTest {
    private val table = listOf(
        "WARM class-removed cases/Data: class removed" to "keep the class until the next restart",
        "WARM signature cases/Foo: supertypes changed: ['kotlin/Any'] -> ['cases/Iface']" to "changed supertype list",
        "WARM signature cases/Foo: modality changed: final -> open" to "making a class open, abstract or final",
        "WARM field-layout cases/Foo: constructor signature changed" to "add a top-level factory function",
        "WARM field-layout cases/Foo: fields added=['b'] removed=[]" to "changes the instance layout",
        "WARM field-layout cases/Outer.Inner: field order changed" to "changes the instance layout",
        "WARM signature cases/Base.g2: method added to existing class" to "add an extension function or a top-level function",
        "WARM signature cases/Holder.held: method removed" to "add an extension function or a top-level function",
        "WARM signature cases/Mode.label: return type kotlin/String -> kotlin/Int" to "add an extension function or a top-level function",
        "WARM signature Screen: signature changed: (tick: kotlin/Int): kotlin/Unit -> (tick: kotlin/Int, rim: kotlin/String /* = ... */): kotlin/Unit (@Composable with forceRecompose off)" to
            "turn forceRecompose back on",
        "WARM signature cases/Sub.g: signature changed: (): kotlin/Int -> (k: kotlin/Int): kotlin/Int (open/override member)" to "add a new final or top-level function",
        "WARM signature privTop: visibility changed" to "internal <-> public stays hot",
        "WARM signature Size: typealias retargeted: kotlin/Int /* = kotlin/Int */ -> kotlin/Long /* = kotlin/Long */" to "adding a new typealias instead stays hot",
        "WARM signature Size: typealias removed" to "adding a new typealias instead stays hot",
        "WARM inline cases/twice: body changed" to "remove `inline` while you iterate",
        "WARM inline twice: signature changed: (x: kotlin/Int): kotlin/Int -> (x: kotlin/Int, y: kotlin/Int /* = ... */): kotlin/Int (inline function)" to "remove `inline`",
        "WARM suspend cases/later: body changed" to "move the code you are changing into a plain function",
        "WARM suspend later: signature changed: (x: kotlin/Int): kotlin/Int -> (x: kotlin/Int, y: kotlin/Int /* = ... */): kotlin/Int (suspend function)" to "plain function",
        "WARM top-level-property topProp2: top-level property added" to "use a function instead",
        "WARM top-level-property topProp: top-level property removed" to "use a function instead",
        "WARM top-level-property topProp: type/mutability changed: {...} -> {...}" to "use a function instead",
        "WARM class-shape cases/Color: unclassified class member changed" to "an enum entry",
        "WARM class-shape <root>: unclassified top-level declaration changed" to "an enum entry",
        "WARM expect-actual Clock: expect class added" to "expect/actual functions stay hot",
        "WARM expect-actual tag: expect val removed" to "expect/actual functions stay hot",
        "WARM compose-resources: 1 file(s) changed since packaging (composeResources/x/values/strings.commonMain.cvr); resources are copied into the app when it is packaged" to
            "always reinstalls the app",
        "WARM external-klibs: 1 changed since packaging (core.klib); declare includedModule(...) for modules of an included build to hot reload them" to "needs a new link",
    )

    @Test
    fun `every WARM reason gets its own advice`() {
        val wrong = table.filter { (reason, phrase) -> phrase !in WarmAdvice.advice(reason) }
            .map { (reason, phrase) -> "$reason\n  want '$phrase'\n  got  '${WarmAdvice.advice(reason)}'" }
        assertEquals(emptyList<String>(), wrong)
        // Every category the classifier and the plugin emit is in the table.
        val categories = table.map { WarmAdvice.parse(it.first)!!.category }.toSet()
        assertEquals(setOf("class-removed", "signature", "field-layout", "inline", "suspend", "top-level-property", "class-shape",
            "expect-actual", "compose-resources", "external-klibs"), categories)
        assertTrue(table.none { WarmAdvice.advice(it.first) == WarmAdvice.GENERIC })
        assertEquals(WarmAdvice.GENERIC, WarmAdvice.advice("WARM something-new x: y"))
    }

    @Test
    fun `reasons are parsed with and without a symbol`() {
        assertEquals(WarmAdvice.Parsed("field-layout", "cases/Foo", "constructor signature changed"),
            WarmAdvice.parse("WARM field-layout cases/Foo: constructor signature changed"))
        assertEquals(WarmAdvice.Parsed("compose-resources", null, "2 file(s) changed"), WarmAdvice.parse("WARM compose-resources: 2 file(s) changed"))
        assertEquals(null, WarmAdvice.parse("HOT no incompatible change"))
    }

    @Test
    fun `annotated output keeps the reason lines and adds one indented advice line each`() {
        val reasons = listOf("WARM field-layout p/Foo: fields added=['b'] removed=[]", "WARM inline twice: body changed")
        val out = WarmAdvice.annotate(reasons)
        assertEquals(4, out.size)
        assertEquals(reasons, out.filter { it.startsWith("WARM ") })   // scripts that grep "^WARM " see the same lines
        assertTrue(out[1].startsWith("    -> adding or removing a field"), out[1])
    }

    @Test
    fun `summary line`() {
        assertEquals("compat: 4 ABI changes, cannot hot-patch; warm restart took 17.1 s", WarmAdvice.summary(4, 17064))
        assertEquals("compat: 1 ABI change, cannot hot-patch; warm restart took 0.9 s", WarmAdvice.summary(1, 900))
        assertEquals("compat: 2 ABI changes, cannot hot-patch; not restarted (--no-warm)", WarmAdvice.summary(2, null, "--no-warm"))
    }
}
