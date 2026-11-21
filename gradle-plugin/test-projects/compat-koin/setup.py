#!/usr/bin/env python3
"""#75: turn a wizard-template project (KmpHr: shared + core, plugin applied in shared) into one compat group.

  A  Compose only
  B  + koin-core 4.1.1 + koin-annotations 2.3.1 through KSP 2.3.14 (koin-ksp-compiler 2.3.1)
  C  + kotlinx-serialization (Kotlin plugin = the project's Kotlin) + kotlinx-serialization-json 1.13.0
  D  B + C
  E  + Koin compiler plugin 1.2.3 (io.insert-koin.compiler.plugin) + koin-core/koin-annotations 4.2.2, no KSP
  F  + power-assert (Kotlin's compiler plugin, the project's Kotlin version) for kotlin.require: never tested before #76,
     the proof that plugins pass through without CMP code for them (2.4.21's jar does not load in the 2.5.255 compiler:
     the expected outcome is the named incompatibility error)
  G  F on the project's Kotlin 2.5.0-RC (power-assert 2.5.0-RC): the same plugin built for the toolchain's Kotlin line
  H  Poko 0.23.2 (dev.drewhamilton.poko, a third-party compiler plugin, never tested before): @Poko generates
     toString/equals/hashCode; the probe shows `Pk(a=1, b=x) eq=true` only when the plugin ran in the hot reload build

The app prints one line per composition into the launch log:
  COMPAT ui=<UI_TAG> greet=<Greeter.greet()> gid=<instance> extra=<Extra from Koin or none> json=<round trip or ERR>
  R  kotlinx.rpc 0.10.4 (org.jetbrains.kotlinx.rpc.plugin, its compiler plugin is published per Kotlin version) +
     serialization: an @Rpc interface; the probe reads the service descriptor the compiler plugin generates
Usage: setup.py GROUP PROJECT_DIR
"""
import os
import re
import sys

G, P = sys.argv[1], os.path.abspath(sys.argv[2])
KOIN = G in "BDE"
KSP = G in "BD"
SER = G in "CDR"
PKG = os.path.join(P, "shared/src/commonMain/kotlin/org/example/kmphr")
IOS = os.path.join(P, "shared/src/iosMain/kotlin/org/example/kmphr")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)


# root build: every plugin on one classloader
root = os.path.join(P, "build.gradle.kts")
s = open(root).read()
extra = ""
if KSP:
    extra += '    id("com.google.devtools.ksp") version "2.3.14" apply false\n'
if SER:
    extra += '    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false\n'
if G == "E":
    extra += '    id("io.insert-koin.compiler.plugin") version "1.2.3" apply false\n'
if G == "H":
    extra += '    id("dev.drewhamilton.poko") version "0.23.2" apply false\n'
if G == "R":
    extra += '    id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.4" apply false\n'
if G in "FG":
    extra += '    id("org.jetbrains.kotlin.plugin.power-assert") version "%s" apply false\n' % ("2.5.0-RC" if G == "G" else "2.4.21")
s = re.sub(r"\n    // compat group plugins.*?// end compat\n", "\n", s, flags=re.S)
s = s.replace("plugins {\n", "plugins {\n    // compat group plugins\n" + extra + "    // end compat\n", 1)
write(root, s)

b = os.path.join(P, "shared/build.gradle.kts")
s = open(b).read()
s = re.sub(r"\n    // compat plugins.*?// end compat\n", "\n", s, flags=re.S)
s = re.sub(r"\n        // compat deps.*?// end compat\n", "\n", s, flags=re.S)
s = re.sub(r"\n// compat ksp.*?// end compat\n", "\n", s, flags=re.S)
plug = ""
if KSP:
    plug += '    id("com.google.devtools.ksp")\n'
if SER:
    plug += '    id("org.jetbrains.kotlin.plugin.serialization")\n'
if G == "E":
    plug += '    id("io.insert-koin.compiler.plugin")\n'
if G == "H":
    plug += '    id("dev.drewhamilton.poko")\n'
if G == "R":
    plug += '    id("org.jetbrains.kotlinx.rpc.plugin")\n'
if G in "FG":
    plug += '    id("org.jetbrains.kotlin.plugin.power-assert")\n'
s = s.replace("    alias(libs.plugins.composeCompiler)\n", "    alias(libs.plugins.composeCompiler)\n    // compat plugins\n" + plug + "    // end compat\n", 1)
deps = ""
if G in "BD":
    deps += '            implementation("io.insert-koin:koin-core:4.1.1")\n            implementation("io.insert-koin:koin-annotations:2.3.1")\n'
if G == "E":
    deps += '            implementation("io.insert-koin:koin-core:4.2.2")\n            implementation("io.insert-koin:koin-annotations:4.2.2")\n'
if G == "R":
    deps += '            implementation("org.jetbrains.kotlinx:kotlinx-rpc-core:0.10.4")\n'
if SER:
    deps += '            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")\n'
s = s.replace("        commonMain.dependencies {\n", "        commonMain.dependencies {\n        // compat deps\n" + deps + "        // end compat\n", 1)
if KSP:
    s += ('\n// compat ksp\ndependencies {\n    add("kspIosSimulatorArm64", "io.insert-koin:koin-ksp-compiler:2.3.1")\n'
          '    add("kspIosArm64", "io.insert-koin:koin-ksp-compiler:2.3.1")\n}\n'
          + '// end compat\n')
s = re.sub(r"\n// compat power-assert.*?// end compat\n", "\n", s, flags=re.S)
if G in "FG":
    s += ('\n// compat power-assert\n@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)\n'
          'powerAssert {\n    functions.set(listOf("kotlin.require"))\n'
          '    includedSourceSets.set(listOf("commonMain", "iosMain", "iosSimulatorArm64Main", "iosArm64Main"))\n}\n// end compat\n')
write(b, s)

# G: the project on Kotlin 2.5.0-RC (the Kotlin Gradle plugin and the plugins that follow its version)
toml = os.path.join(P, "gradle/libs.versions.toml")
t = open(toml).read()
t = re.sub(r'^kotlin = "[^"]*"', 'kotlin = "%s"' % ("2.5.0-RC" if G == "G" else "2.4.21"), t, flags=re.M)
open(toml, "w").write(t)

# sources
for f in ("di/Di.kt", "Probe.kt"):
    p = os.path.join(PKG, f)
    if os.path.exists(p):
        os.remove(p)
if KOIN:
    di = "package org.example.kmphr.di\n\n"
    if G in "BDE":
        di += "import org.koin.core.annotation.ComponentScan\nimport org.koin.core.annotation.Factory\nimport org.koin.core.annotation.Module\n\n"
        di += ("interface Greeter { fun greet(): String }\n\n@Factory(binds = [Greeter::class])\nclass GreeterImpl : Greeter {\n"
               '    override fun greet(): String = "g1"\n}\n\n'
               '// not a definition yet: the "add a single" step puts @Single on it\nclass Extra {\n    fun tag(): String = "x1"\n}\n\n'
               '@Module\n@ComponentScan("org.example.kmphr.di")\nclass AppModule\n')
        if G == "E":
            di = di.replace("import org.koin.core.annotation.Module\n", "import org.koin.core.annotation.KoinApplication\nimport org.koin.core.annotation.Module\n")
            di += "\n@KoinApplication(modules = [AppModule::class])\nobject KoinApp\n"
    write(os.path.join(PKG, "di/Di.kt"), di)
probe = "package org.example.kmphr\n\n"
if G == "R":
    probe += ("import kotlinx.rpc.annotations.Rpc\nimport kotlinx.rpc.descriptor.serviceDescriptorOf\n\n"
              "@Rpc\ninterface Svc {\n    suspend fun hello(name: String): String\n}\n\n"
              "@OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)\n"
              "fun jsonProbe(): String = runCatching {\n    val d = serviceDescriptorOf<Svc>()\n"
              "    \"RPC:\" + d.fqName + \" calls=\" + d.callables.keys.sorted().joinToString(\",\")\n"
              "}.getOrElse { \"ERR:\" + it::class.simpleName + \":\" + it.message }\n")
elif SER:
    probe += ("import kotlinx.serialization.Serializable\nimport kotlinx.serialization.encodeToString\nimport kotlinx.serialization.json.Json\n\n"
              "@Serializable\ndata class Item(val a: Int, val b: String = \"x\")\n\n"
              "fun jsonProbe(): String = runCatching {\n    val t = Json.encodeToString(Item(1))\n    t + \"/\" + Json.decodeFromString<Item>(t)\n"
              "}.getOrElse { \"ERR:\" + it::class.simpleName + \":\" + it.message }\n")
elif G == "H":
    probe += ("import dev.drewhamilton.poko.Poko\n\n@Poko\nclass Pk(val a: Int, val b: String)\n\n"
              # eq: Poko's equals compares the properties (true); without the plugin it is identity (false)
              "fun jsonProbe(): String = \"PK:\" + Pk(1, \"x\").toString() + \" eq=\" + (Pk(1, \"x\") == Pk(1, \"x\"))\n")
elif G in "FG":
    # power-assert rewrites require(...) so that its message shows the expression with the values; without the
    # plugin the message is just "Failed requirement."
    probe += ('fun jsonProbe(): String = runCatching {\n    val x = 1\n    require(x + 1 == 3)\n    "no-error"\n'
              '}.getOrElse { "PA:" + (it.message ?: "").lines().filter { l -> l.isNotBlank() }.joinToString("|") { l -> l.trim() } }\n')
else:
    probe += 'fun jsonProbe(): String = "-"\n'
if KOIN:
    probe += ("\nfun koinProbe(): String = runCatching {\n    val k = org.koin.mp.KoinPlatform.getKoin()\n"
              "    val g = k.get<org.example.kmphr.di.Greeter>()\n"
              "    val x = k.getOrNull<org.example.kmphr.di.Extra>()?.tag() ?: \"none\"\n"
              "    \"greet=\" + g.greet() + \" gid=\" + g.hashCode().toString(16) + \" extra=\" + x\n"
              "}.getOrElse { \"greet=ERR:\" + it::class.simpleName + \":\" + it.message }\n")
else:
    probe += '\nfun koinProbe(): String = "greet=- gid=- extra=-"\n'
write(os.path.join(PKG, "Probe.kt"), probe)

write(os.path.join(PKG, "App.kt"), '''package org.example.kmphr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

const val UI_TAG = "u1"

@Composable
fun App() {
    val k = koinProbe()
    val j = jsonProbe()
    println("COMPAT ui=$UI_TAG $k json=$j")
    MaterialTheme {
        Column(Modifier.safeContentPadding().fillMaxSize()) {
            Text("COMPAT ui=$UI_TAG")
            Text(k)
            Text("json=$j")
        }
    }
}
''')
start = ""
for leaf in ("iosSimulatorArm64Main", "iosArm64Main"):
    p = os.path.join(P, "shared/src", leaf, "kotlin/org/example/kmphr/KoinModules.kt")
    if os.path.exists(p):
        os.remove(p)
kmods = os.path.join(IOS, "KoinModules.kt")
if os.path.exists(kmods):
    os.remove(kmods)
if G in "BD":
    # KSP generates per target into the leaf source sets, which iosMain cannot see: expect in iosMain, actual per leaf
    start = ("    if (org.koin.mp.KoinPlatform.getKoinOrNull() == null) {\n"
             "        org.koin.core.context.startKoin { modules(koinModules()) }\n    }\n")
    imp = ""
    write(kmods, "package org.example.kmphr\n\nexpect fun koinModules(): List<org.koin.core.module.Module>\n")
    for leaf in ("iosSimulatorArm64Main", "iosArm64Main"):
        write(os.path.join(P, "shared/src", leaf, "kotlin/org/example/kmphr/KoinModules.kt"),
              "package org.example.kmphr\n\nimport org.koin.ksp.generated.module\n\n"
              "actual fun koinModules(): List<org.koin.core.module.Module> = listOf(org.example.kmphr.di.AppModule().module)\n")
elif G == "E":
    # Koin compiler plugin: modules<T>() is a stub that the plugin rewrites to the generated module (koin-core 4.2.2,
    # package org.koin.plugin.module.dsl)
    # (inside startKoin { } the member modules(vararg) shadows the modules<T>() extension, so the @KoinApplication form)
    start = ("    if (org.koin.mp.KoinPlatform.getKoinOrNull() == null) {\n"
             "        startKoin<org.example.kmphr.di.KoinApp>()\n    }\n")
    imp = "import org.koin.plugin.module.dsl.startKoin\n"
else:
    imp = ""
write(os.path.join(IOS, "MainViewController.kt"), "package org.example.kmphr\n\nimport androidx.compose.ui.window.ComposeUIViewController\n" + imp +
      "\nfun MainViewController(): platform.UIKit.UIViewController {\n" + start + "    return ComposeUIViewController { App() }\n}\n")
# drop template leftovers that the compat App no longer uses
for f in ("Shape.kt",):
    p = os.path.join(PKG, f)
    if os.path.exists(p):
        os.remove(p)
print(f"group {G}: koin={KOIN} ksp={KSP} serialization={SER} koin-compiler-plugin={G == 'E'}")
