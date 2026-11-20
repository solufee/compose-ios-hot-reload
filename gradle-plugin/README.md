# Gradle 插件 `com.solufee.cmp.hotreload`

把 iOS 模拟器热重载做成 Gradle 插件。使用者只需三步：应用插件、指向工具链、写少量 DSL（多数情况下一行都不用写）。
插件**只支持一套钉死的工具链**（我们的 Kotlin/Native、Kaldo LLVM、Compose fork），版本不对就拒绝运行，
并逐项说明哪里不对、怎么修。

> 编译、打包、热重载、启动、守护进程全部在插件内实现，不调用参考脚本 `hotreload.sh`、`run.sh`、`daemon-start.sh`；
> 插件只通过 `docs/INTERFACE.md` 里的工具和协议使用工具链。对接接口 **v8**，支持的工具链发行版是 **2.5.255-hotreload.6**。
> 默认打开 `KALDO_KEEP_COMPACT_UNWIND`，异常可以穿过热替换进来的代码，页面切换（composable dispose）不受限制。

**第一次使用请看 [docs/TRYOUT.md](../docs/TRYOUT.md)**：从下载工具链到第一次热重载的完整步骤和最小模板。本文是插件的参考手册。

## 接入

两种工程：

- **已有的 Kotlin Multiplatform 工程**（0.2.0 起）：插件加在含 `fun MainViewController()` 的模块上（向导模板的 `shared`），
  通常一行 DSL 都不用写。插件用反射读 Kotlin Multiplatform 插件的源码集（`iosSimulatorArm64Main` 和它 `dependsOn` 的全部源码集，
  按片段传给编译器：`-Xmulti-platform -Xexpect-actual-classes -Xfragments -Xfragment-refines -Xfragment-sources`），
  从 `iosSimulatorArm64CompileKlibraries` 解析依赖 klib，生成调用 `MainViewController()` 的 UIKit 入口和 Info.plist，
  把各模块的 Compose 资源拷进 app。依赖的同工程 KMP 模块（含间接依赖）按依赖顺序作为库模块（0.2.1）。
  不编译、也不依赖你的 Kotlin Gradle 插件版本，只读它的设置。步骤见 TRYOUT 第 7 节。
- **不用 KMP 插件的多模块工程**（下面的写法）：插件加在根项目上，模块源码在 `src/main/kotlin`。

`settings.gradle.kts`：插件从 GitHub Pages 上的静态 Maven 仓库解析，不需要 token，也没有其他依赖：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://solufee.github.io/compose-ios-hot-reload/maven/") }
    }
}
include("app", "ui")
```

根目录 `build.gradle.kts`：

```kotlin
plugins {
    id("com.solufee.cmp.hotreload") version "0.2.1"
}

cmpHotReload {
    // 可以什么都不写：有 src/main/kotlin/main.kt 的子工程是 app，其余有 src/main/kotlin 的子工程是库，
    // 按路径排序，后面的库依赖前面的库。需要时可以显式声明：
    // app(":app")
    // library(":core")
    // library(":ui") { dependsOn.add(":core") }
}
```

`local.properties`（不进 git）或 `~/.gradle/gradle.properties`：

```properties
cmp.hotreload.toolchainDir=<TOOLCHAIN>/kotlin-native     # 含 toolchain.json 的目录
# 可选
cmp.hotreload.simulatorUdid=<UDID>      # 默认：唯一一台已启动的模拟器
cmp.hotreload.port=5568                 # 默认取 toolchain.json 的 runtime.reloadPort（5567）
```

用 `scripts/fetch-toolchain.py` 下载的工具链，`toolchain.json` 里带 `tools`（ORC runtime、`libCMPUIKitObjcUtils.a`、
`llvm-tools` 的绝对路径），插件直接读，只需要 `toolchainDir`。自己从源码构建的工具链（清单里没有 `tools`）要另外设
`cmp.hotreload.orcRuntime`、`cmp.hotreload.objcUtilsLib`，以及可选的 `cmp.hotreload.llvmHome`。

app 模块需要 `Info.plist`（`CFBundleExecutable`、`CFBundleIdentifier`、`UILaunchScreen`），模板见 TRYOUT 第 3 节。

在本仓库里开发插件时，示例工程用 `includeBuild("../gradle-plugin")` 直接用源码（见 `example-gradle/settings.gradle.kts`）。

## 命令

```bash
./gradlew hotReloadPackage   # 出包：build/hotreload/<App>.app
./gradlew hotReloadRun       # 安装并启动，app 起来后返回（app 继续运行，控制台写到 launch.log）
./gradlew hotReload          # 改完代码后执行：编译改动、ld -r 合并、符号检查、发送
```

| 任务 | 作用 |
|---|---|
| `hotReloadCheckToolchain` | 读 `toolchain.json`，与插件钉死的版本逐项比对，检查 Xcode、模拟器 runtime 和所需文件；通过结果按清单、Xcode、klib 列表缓存 |
| `hotReloadCompileKlib<Module>` | 每个库模块一个任务，编成 klib；源码和依赖 klib 不变时 UP-TO-DATE |
| `hotReloadPackage` | split 编译 app（走 klib 守护进程；它不可用或编译失败时改用 `kotlinc-native` 重跑，并打印一行提示）→ `merge-payloads.py`（热符号集按各模块源码里的 package 自动生成；不变的库 archive 的处理结果缓存在 `build/hotreload/merge-cache/`）→ `embed-merged.py` → 组装 `.app`。日志最后一行 `package timing ms=… compile=… merge=… embed+bundle=…` |
| `hotReload` | 先分类（见下文“分类器与温重启”）；可热替换时：守护进程 cache-only 编译 → 按 mtime 取本次重建的对象 → 按 `_kfun:<package>` 归到模块 → `ld -r` → 逐模块符号检查 → 发送。`--no-warm`：需要温重启时只报原因并失败 |
| `hotReloadRun` | 安装并启动；首帧标记（`readyMarker`）出现或 Kotlin main 运行 3 s 即算启动成功，失败最多重试 3 次。`--args="..."` 传给 app（如 `--hr-state=2,3`） |
| `hotReloadDaemonStart` / `hotReloadDaemonStop` | 启动或复用、停止本项目的两个编译守护进程 |
| `hotReloadClean` | 停掉本项目的守护进程，删除 `build/hotreload/`（app、编译缓存、merge 缓存、分类器快照）；下次 `hotReloadPackage` 从头建 |
| `hotReloadVerifyRelease` | release 零改动校验：见下文 |

## DSL

| 项 | 默认 | 说明 |
|---|---|---|
| `toolchainDir` | 属性 `cmp.hotreload.toolchainDir`，再查环境变量 `CMP_HOT_RELOAD_TOOLCHAIN` | 含 `toolchain.json` 的 Kotlin/Native dist |
| `enabled` | `true`（`cmp.hotreload.enabled`） | debug 开关：`false` 时所有热重载任务跳过 |
| `useDaemon` | `true` | 用常驻编译进程（插件负责启动、复用、健康检查、停止） |
| `daemonJavaHome` | `JAVA_HOME`，否则运行 Gradle 的 JDK | 守护进程和 kotlinc-native 用的 JDK |
| `orcRuntime` / `objcUtilsLib` | 清单的 `tools.orcRuntime` / `tools.uikitUtils`；没有时看 dist 旁边的 `orc-runtime/`、`cmp-uikit-utils/`；再没有用工程 `tools/` 下同名文件 | 自己构建的工具链才需要设 |
| `llvmHome` | 清单的 `tools.llvmTools`；没有时看 dist 旁边的 `llvm-tools/`；再没有就不设（工具自己的默认值） | 传给打包工具的 `KONAN_LLVM_HOME` |
| `keepCompactUnwind` | `true` | 打包时导出 `KALDO_KEEP_COMPACT_UNWIND=1`（INTERFACE §3.3 第 1 条） |
| `forceRecompose` | `true`（`cmp.hotreload.forceRecompose`） | 工具链 .6 起：每次热替换成功后整个界面完整重组一次，参数没变的调用点也显示新代码，`remember` 状态保留。`false` 时传 `KONAN_HR_FORCE_RECOMPOSE=0`（.5 的行为）；下次启动 app 时生效 |
| `warmRestart` | `true`（`cmp.hotreload.warmRestart`） | 分类器判定需要温重启时：`true` 自动重新打包并带着保存的状态重启；`false` 只报原因、任务失败、不发送 |
| `simulator { udid; port; launchLog; readyMarker; launchTimeoutSeconds; launchRetries; reinstall }` | `booted`、清单端口、`launch.log`、无、120、3、`true` | 端口同时传给发送端和 app |
| `app(":x") { sourceDir; infoPlist; linkerOptions; dependsOn }` | `src/main/kotlin`、`Info.plist`、ImageIO 等、全部库 | |
| `library(":y") { sourceDir; dependsOn }` | `src/main/kotlin`、在它之前声明的全部库 | 库按依赖顺序声明 |
| `app(":x") { sourceSets { create("commonMain") { srcDir(...); dependsOn(...) } } }` | 从 Kotlin Multiplatform 插件读 | 模块的源码片段；写了就不读 KMP 插件。`library` 同样可写 |
| `app(":x") { iosEntry }` | 自动找 `fun MainViewController()`（模块里有 `fun main(` 时不生成入口） | 返回 `UIViewController` 的函数全名；插件生成调用它的 UIKit 入口 |
| `app(":x") { bundleId }` | `infoPlist` 里的；没有 Info.plist 时为入口所在包名 + `.hotreload` | 只用于插件生成的 Info.plist（app 名为根项目名的字母数字部分） |
| `includedModule("<build>", ":<path>") { projectDir; sourceSets; generatorTasks }` | 无 | 复合构建（0.2.2）：included build 里的 KMP 模块从源码编译、参与热重载；按依赖顺序声明。默认按 KMP 默认层级和 Compose 生成目录找源码，资源生成任务通过跨构建任务引用先跑。不声明的 included 模块用那个构建编出的 klib 链接，改动时温重启（`WARM external-klibs`）。见 TRYOUT 第 8 节 |
| `extraKlibs` | 空 | 另外要链进来的 klib 文件或目录；所在目录也作为自动缓存的根（`-Xauto-cache-from`） |
| `resolveDependencyKlibs` | `true` | 从 `iosSimulatorArm64CompileKlibraries` 解析依赖 klib（KMP 模块）。工具链自带的库按 klib 的 `unique_name` 和 Maven 组件整体替换成工具链的那份；klib ABI 不在 1.6.0–2.5.0、metadata 高于 2.6.0、某个 Compose 模块比工具链里同名的那份新时打包前报错（写出拉进它的依赖）；cinterop klib 打警告（0.2.0 不支持） |

`cmp.hotreload.*` 属性（`-P`、gradle.properties、local.properties）可覆盖大多数默认值，
包括 `readyMarker`、`launchTimeoutSeconds`、`launchRetries`、`reinstall`、`keepCompactUnwind`、`probeHost`。

## 分类器与温重启（INTERFACE §10）

- 插件不自己判断改动能否热替换，只调用打进 jar 的 `compat-classify.py`，按退出码走：0 热替换，3 温重启，其他一律报错且不发送。
- `hotReloadPackage` 结束时把 app 编成一份带 IR 的 library klib，用一个 `compat-classify.py baseline` 进程为所有模块写基线
  （`build/hotreload/compat/<模块>.json`）。klib 的 dump 走插件自己的 klib 守护进程（`--daemon`），快照按 klib 的 sha256 缓存（`--cache-dir`）。
- `hotReload`：`compat-classify.py check` 在后台运行，同时编译补丁；库模块直接传当前 klib（sha256 没变就不 dump），
  app 只在源码比上次快照新时才重编 library klib，否则传 `app=-`。补丁准备好后等分类结论，HOT 才发送，发送后新快照成为基线。
  结论是 WARM 时补丁丢弃；补丁本身编不过但结论是 WARM 时照样温重启；app 有编译错误时报编译器的原话。
- 温重启：读出 app 写的 `Documents/hr-state.json`（内容由 app 定，例如 `{"page":N,"count":M,"liked":true}`）→ 停 app →
  重新打包并写基线 → 安装后把这个文件**原样写回**新安装的 `Documents/` → 启动；文件里有 `page`/`count` 时还传 `--hr-state=N,M`，
  并等到首帧标记所在的行同时出现 `page=N` 和 `count=M` → 打印 `WARM OK ms=… ms_package=… ms_relaunch=… page=N count=M`。
  app 不写这个文件也能温重启，只是回到第 0 页；启动日志里出现 `EH FAIL` 时任务失败。
- 原因按分类器的原样输出，每行 `WARM <类别> <符号>: <说明>`。
- 规则的边界用 `test-projects/classifier-cases/cases.py` 固定：44 个用例，用真实 klib（含 Compose 插件生成的声明）逐类比较，
  热替换与温重启各有正反例；`manual_hot.py` 在运行中的 app 上逐步验证放行的几类改动确实能热替换并上屏。

## 发布（静态 Maven 仓库）

```bash
./gradlew -p gradle-plugin publishAllPublicationsToPagesRepository [-Pcmp.publish.pagesDir=<目录>]
```

- 默认写到 `gradle-plugin/build/pages/maven`：插件 jar、`.module`、POM，以及 plugin marker
  `com/solufee/cmp/hotreload/com.solufee.cmp.hotreload.gradle.plugin/<版本>/`。发布流程把这个目录原样放到公开仓库的 GitHub Pages
  （`https://solufee.github.io/compose-ios-hot-reload/maven/`），读取不需要 token。这个任务本身不上传任何东西。
- 插件没有外部运行时依赖（POM 和 `.module` 里没有依赖），使用方的 `pluginManagement` 只需要这一个仓库。
- 使用方：

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://solufee.github.io/compose-ios-hot-reload/maven/") }
        gradlePluginPortal()
    }
}
// build.gradle.kts（根项目）
plugins {
    id("com.solufee.cmp.hotreload") version "0.2.1"
}
```

## 版本校验

钉死的版本在 `src/main/resources/com/solufee/cmp/hotreload/pinned-toolchain.json`：
`interfaceVersion`、三份补丁各自的公开基线 commit 和补丁 sha256、Kotlin/Compose 版本号、Xcode 版本、
iOS 模拟器 runtime。校验同时检查本机 `xcodebuild -version`、`xcrun simctl list runtimes`，
清单里每个文件（编译器、Compose 插件 jar、libKaldo、klib 列表里的每个 klib），以及 orc runtime、
ObjC 工具库、`Info.plist`、各模块源码目录。

不匹配时的真实输出（插件 0.1.0 指向一份旧的下载版工具链 2.5.255-hotreload.2）：

```
CMP hot reload: the toolchain does not match what plugin com.solufee.cmp.hotreload is pinned to.
  manifest: <TOOLCHAIN>/kotlin-native/toolchain.json (downloaded toolchain 2.5.255-hotreload.2)
  pinned:   interfaceVersion=8, Kotlin 2.5.255-SNAPSHOT@8e53ee99fbc0, Compose 1.13.0-beta01@ee243c321ddf, LLVM@2f8844c2b355, Xcode 27.2 Build version 27B52
            patches: kotlin 2d64f7af6749, compose c1de7a107e3f, llvm 3bb8a987ca91 (sha256)
  This plugin works with toolchain 2.5.255-hotreload.4; download it with scripts/fetch-toolchain.py
  (docs/TRYOUT.md §2) and point toolchainDir at <cache>/2.5.255-hotreload.4/kotlin-native.
  - interfaceVersion: expected 8, found 6
      fix: rebuild the toolchain from the baselines in README.md and regenerate the manifest with scripts/gen-toolchain.sh (KOTLIN_DIST, COMPOSE_PLUGIN_JAR, KLIBLIST)
  - llvm.patchSha256: expected 3bb8a987…, found 4941c3ac…
      fix: build LLVM + libKaldo from llvm-project-kotlin@2f8844c2b355 + patches/llvm-kaldo.patch (README.md, step 1), then regenerate the manifest …
```

## 与底层的边界

- **随插件分发的工具**：构建插件时，把 `example-gradle/tools/merge-payloads.py`、`embed-merged.py`、
  `daemon/HrCompileDaemon.java`、`scripts/compat-classify.py` 原样打进 jar，sha256 记在 jar 里的 `tools-manifest.properties`；
  运行时解到 `build/hotreload/tools/<hash>/`，并逐个校验哈希。日志里会有一行
  `tools from plugin jar (sha256 …)`，说明这次用的是哪一份。
- **插件自己实现的协议**：守护进程客户端（INTERFACE §2.1）和发送（§4.1、§6）。契约测试
  `ProtocolContractTest` 会连真实的 `HrCompileDaemon` 编译一次成功、一次语法错误，
  再对一个最小监听器发送两个对象并逐字节比对；测试里钉了 `interfaceVersion`，底层改协议升版本时这组测试先失败。
- **开关**：打包时注入 `KALDO_HOT_PACKAGES`（自动生成）、`KALDO_HOT_SYMBOLS_OUT`；启动时注入
  `KALDO_SYNTH_EH_FRAME=1`、`KALDO_HOT_SYMBOLS`、`KONAN_ORC_RUNTIME_PATH`、`KONAN_HOT_RELOAD_PORT`、`KALDO_PERF_MAP`；
  守护进程按用途分别设或不设 `KONAN_HOT_RELOAD_CACHE_ONLY`。从调用者 shell 继承来的这些开关一律先清掉，
  防止例如 `CACHE_ONLY` 漏进完整构建。

## 打包方义务（INTERFACE §3.3）逐条落实

| # | 要求 | 插件里的实现 | 检查 |
|---|---|---|---|
| 1 | merge 时 `KALDO_KEEP_COMPACT_UNWIND=1` | `Pipeline.mergeEnvironment`，`keepCompactUnwind` 默认 true | `PackagerDutiesTest`；打包后 bundle 检查要求合并对象里有 `__kaldo_cu` |
| 2 | 启动时 `SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME=1` | `Launcher.appEnvironment` | `PackagerDutiesTest`；验收看启动日志 `EH check: N FDE …` 与 `registered N (section)` |
| 3 | 启动时传收窄的 `KALDO_HOT_SYMBOLS` | `Launcher.appEnvironment`，清单由打包自动生成 | `PackagerDutiesTest`；bundle 检查要求清单非空；启动前清单缺失直接报错 |
| 4 | host 链接不带 `CACHE_ONLY` | `Pipeline.compileEnvironment(false)`，`Proc` 清掉调用者 shell 里的同名变量 | `PackagerDutiesTest` |
| 5 | 拷 libKaldo、删绝对 rpath | 打包最后一步 | bundle 检查：库在 `Frameworks/` 且 sha256 等于清单的 `kaldoIossimSha256`，无绝对 rpath，有 `@executable_path` rpath |
| 6 | ad-hoc 重签 | `codesign --force --deep --sign -` | bundle 检查：`codesign --verify --deep` |
| 7 | 打包前校验清单 | `hotReloadCheckToolchain`：`interfaceVersion`、三个 `patchSha256`、重算 libKaldo 的 sha256 | `ToolchainValidatorTest` |

bundle 检查（`Pipeline.verifyBundle`）每次 `hotReloadPackage` 都跑，任何一条不满足就让构建失败，日志里有一行
`bundle check (INTERFACE §3.3): …`。工具链里的所有路径（包括 libKaldo）一律按 `toolchain.json` 读，不写死。

## release 零改动

插件只新增 “hot reload” 组里的任务，不改任何已有任务、配置和变体。`PluginFunctionalTest` 比对了同一工程应用插件前后
`tasks --all` 的输出（去掉 hot reload 组后完全一致）。

产物层面用 `hotReloadVerifyRelease` 比对：它按 release 方式（`-opt`、不用 split、不用增量和自动缓存、不带任何
热重载开关、干净环境、固定 `-module-name`）编出 app，记录各段大小、符号表、`__TEXT,__text` 字节的指纹。
`-Pcmp.hotreload.releaseBaseline=true` 记录基线；之后不带参数运行就与基线比对，不一致即失败。
判定口径（0.2.0 起）：**各段大小、符号表和 `__text` 摘要必须完全一致**；原始 `__text` 哈希只记录、不判定。
原因是 release 产物本身不可逐位复现：Apple 链接器会给同一个导入（libobjc 的 `_objc_msgSend`）建两个 `__got` 槽，先后顺序每次链接可能不同，
读这两个槽的 `ldr` 立即数随之变化，`__text` 有几十个字节不同，而两个槽在运行时绑定到同一个地址（REPORT §28）。不应用插件的对照构建里同样如此，与热重载无关。
摘要由随 jar 分发的 `release-text-digest.py`（需要 Xcode 的 `dyld_info`）计算：把每条读 GOT 的指令换成它绑定的符号名再求哈希，
GOT 顺序不同的两次构建得到同一个值，改一个代码字节就会变（`ReleaseDigestTest` 用真实的 arm64 二进制验证这两点）。
所以记基线只构建一次（`cmp.hotreload.releaseBaselineRuns` 可以设成多次，用来确认几次构建彼此一致）。0.1.x 记的基线没有摘要，0.2.0 会要求重记一次。
同时写出 `build/hotreload/release/<label>/commands.sh`，可以脱离 Gradle 用 `env -i` 复现同一构建再比对。

> 固定 `-module-name` 的原因：不指定时，程序的模块名每次构建都会变，而它会进入 cinterop 桥接符号名
> （`…_kncfunN`），符号排序随之改变，进而让数据槽顺序和 `__text` 字节不同；与热重载无关。

验收脚本 `example-gradle/accept-release.sh`：干净状态记基线 → 完整热重载会话（打包、启动、9 次热替换，守护进程和 app 都在）
→ 再比对 → 用 `env -i` 在 Gradle 外复现比对。

## 守护进程

- socket 放在项目内 `.hr/`；路径超过 macOS 的 104 字节上限时，改放
  `~/Library/Caches/cmp-hot-reload/<项目路径 sha1>/`。目录 0700、socket 0600，每次复用前检查 owner 和权限。
- 复用条件：两个 socket 都能连上，且记录的编译器 jar、dist、`HrCompileDaemon.java` 哈希都没变；否则只停本项目的
  两个进程（按 socket 路径匹配），再重新启动。从不按编译器 jar 批量杀进程。
- 编译出错时，把守护进程日志里本次请求的编译器输出贴进 Gradle 的报错；守护进程不退出，下一次照常编译。

## 测试

```bash
../example-gradle/gradlew -p . test -Pcmp.hotreload.toolchainDir=<KOTLIN_NATIVE_DIST>
```

共 63 个测试；不给工具链时，需要真实编译器的两项跳过，没有 Xcode 时 `ReleaseDigestTest` 跳过，其余照常运行。

| 测试类 | 数量 | 内容 |
|---|---|---|
| `ToolchainValidatorTest` | 9 | 版本、基线 commit、补丁哈希、libKaldo 实际哈希、Xcode、缺文件、bundle 布局拒绝 |
| `PipelineTest` | 5 | 模块归类（最长包名匹配、根包）、源码 package 读取、内置工具哈希、工具位置的查找顺序（显式设置 → 清单 `tools` → 下载布局 → `tools/`） |
| `PackagerDutiesTest` | 4 | INTERFACE §3.3 的打包方义务（合并开关、启动开关、`CACHE_ONLY` 不漏进完整构建） |
| `ProtocolContractTest` | 4 | 钉住的 `interfaceVersion`；守护进程回复解析；发送协议逐字节；连真实 `HrCompileDaemon` 编译成功和语法错误（要工具链） |
| `CompatContractTest` | 8 | 分类器随 jar 分发且哈希一致；源码里的 `expect` 声明（增删一对判温重启）；非快照输入不会判成 HOT；用真实 klib 验证“只改函数体退出 0、加字段退出 3”（要工具链）；`hr-state.json` 解析；带参数启动时找 app 进程；已启动模拟器列表；dist 旁边的 klib 会被链接 |
| `PluginFunctionalTest` | 9 | TestKit：版本不匹配和下载版旧工具链的报错、没配工具链、DSL 配错时其它任务照常、debug 开关、应用插件前后 `tasks --all` 一致、`warmRestart` 与 `--no-warm`、加在单个模块上并声明源码片段、任务都在自己的组里 |
| `KmpTest` | 10 | 版本比较；每个 Compose 模块和工具链里同名那份比版本（写出拉进它的依赖）；cinterop klib 警告；依赖 klib 按 `unique_name` 和 Maven 组件替换、去重、比工具链新时提示；klib ABI 高于 2.5.0 / 低于 1.6.0、metadata 高于 2.6.0 的报错；`MainViewController` 入口识别；多片段编译参数；按符号归模块 |
| `ExpectActualCasesTest` | 5 | 增删一对 expect/actual 判温重启的 12 例用例表（6 正 6 反）；Compose 资源改动判温重启；回归：只含初始化桩的目标文件、生成的 UIKit 入口（根包）在多模块时归 app、失败编译后的待发送列表 |
| `ProjectDependenciesTest` | 1 | 同工程 KMP 模块依赖按传递关系、依赖在前的顺序找出，环路不死循环 |
| `ReleaseDigestTest` | 1 | release 校验比 `__text` 摘要：同一二进制摘要一致，改一个代码字节必须失败；原始 `__text` 哈希只记录 |
| `CompositeBuildTest` | 7 | 复合构建（0.2.2）：included 模块的默认层级源码集和 Compose 生成目录；included 模块排在前、按声明顺序、依赖关系；声明顺序保留（容器按名字迭代）；外部 klib 改变判温重启；included build 自己编出的 klib 按坐标、位置、unique_name 排除（不重复链接）；included build 的 klib 同样过 ABI 预检；写错 build 名时的报错（TestKit，真实 includeBuild） |

## 验收脚本与证据

| 脚本 | 用途 | 证据 |
|---|---|---|
| `test-projects/endpoint/endpoint.py` | 使用者视角的终点验收：改代码 → `hotReload` → 读模拟器屏幕上的文字（AXe）；温重启后真实点击；冷启动看 EH 检查 | `test-projects/endpoint/evidence/`（见其中的 README：全新工程两种工具链来源、模板工程、状态恢复、发布后冒烟、插件 jar 扫描） |
| `example-gradle/accept-b4-plugin.py` | 分类器回归：16 种改动加温重启后点击，结果须与参考实现一致 | `example-gradle/accept-plugin-p2a-b4.txt`、`accept-plugin-p2a-v8-smoke.txt` |
| `example-gradle/accept-p1-full.sh` | 示例工程的完整矩阵（ui/app/both、多文件、点击翻页、冷启动、三模块、release） | `example-gradle/accept-plugin-p1-v7full-*.txt` |
| `example-gradle/accept-release.sh` | release 零改动校验 | `example-gradle/accept-plugin-p1-release*.txt` |
| `test-projects/three-modules/accept-three.py` | 三模块（app → ui → core） | `test-projects/three-modules/accept-three.txt` |
| `test-projects/composite/accept-composite.py` | 0.2.2 复合构建：根 build（app、convention plugin 应用 KMP 的 feature）加 included build lib-build（util、core、demo）；声明/不声明 includedModule 两档、只声明 core、插件装在 included build 里从根 build 运行、资源、Dialog、release 校验、配置缓存、写错 includedModule 的报错 | `test-projects/composite/evidence/` |
| `test-projects/kmp-template/accept-kmp.py` | 0.2.0：KMP 向导模板（只选 iOS）加一对 expect/actual 和 kotlinx-datetime，只按 TRYOUT 第 7 节接入：打包、启动、commonMain/iosMain 改动 HOT、expect 签名和增删一对 WARM、改过 manifest 的 klib 报错、release 校验 | `test-projects/kmp-template/evidence/` |
