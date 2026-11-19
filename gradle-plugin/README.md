# Gradle 插件 `com.solufee.cmp.hotreload`

把 iOS 模拟器热重载做成 Gradle 插件。使用者只需三步：应用插件、指向工具链、写少量 DSL（多数情况下一行都不用写）。
插件**只支持一套钉死的工具链**（我们的 Kotlin/Native、Kaldo LLVM、Compose fork），版本不对就拒绝运行，
并逐项说明哪里不对、怎么修。

> 编译、打包、热重载、启动、守护进程全部在插件内实现，不调用参考脚本 `hotreload.sh`、`run.sh`、`daemon-start.sh`；
> 插件只通过 `docs/INTERFACE.md` 里的工具和协议使用工具链。对接接口 **v8**，支持的工具链发行版是 **2.5.255-hotreload.4**。
> 默认打开 `KALDO_KEEP_COMPACT_UNWIND`，异常可以穿过热替换进来的代码，页面切换（composable dispose）不受限制。

**第一次使用请看 [docs/TRYOUT.md](../docs/TRYOUT.md)**：从下载工具链到第一次热重载的完整步骤和最小模板。本文是插件的参考手册。

## 接入

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
    id("com.solufee.cmp.hotreload") version "0.1.0"
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
| `hotReloadPackage` | split 编译 app → `merge-payloads.py`（热符号集按各模块源码里的 package 自动生成）→ `embed-merged.py` → 组装 `.app` |
| `hotReload` | 先分类（见下文“分类器与温重启”）；可热替换时：守护进程 cache-only 编译 → 按 mtime 取本次重建的对象 → 按 `_kfun:<package>` 归到模块 → `ld -r` → 逐模块符号检查 → 发送。`--no-warm`：需要温重启时只报原因并失败 |
| `hotReloadRun` | 安装并启动；首帧标记（`readyMarker`）出现或 Kotlin main 运行 3 s 即算启动成功，失败最多重试 3 次。`--args="..."` 传给 app（如 `--hr-state=2,3`） |
| `hotReloadDaemonStart` / `hotReloadDaemonStop` | 启动或复用、停止本项目的两个编译守护进程 |
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
| `warmRestart` | `true`（`cmp.hotreload.warmRestart`） | 分类器判定需要温重启时：`true` 自动重新打包并带着保存的状态重启；`false` 只报原因、任务失败、不发送 |
| `simulator { udid; port; launchLog; readyMarker; launchTimeoutSeconds; launchRetries; reinstall }` | `booted`、清单端口、`launch.log`、无、120、3、`true` | 端口同时传给发送端和 app |
| `app(":x") { sourceDir; infoPlist; linkerOptions; dependsOn }` | `src/main/kotlin`、`Info.plist`、ImageIO 等、全部库 | |
| `library(":y") { sourceDir; dependsOn }` | `src/main/kotlin`、在它之前声明的全部库 | 库按依赖顺序声明 |

`cmp.hotreload.*` 属性（`-P`、gradle.properties、local.properties）可覆盖大多数默认值，
包括 `readyMarker`、`launchTimeoutSeconds`、`launchRetries`、`reinstall`、`keepCompactUnwind`、`probeHost`。

## 分类器与温重启（INTERFACE §10）

- 插件不自己判断改动能否热替换，只调用打进 jar 的 `compat-classify.py`，按退出码走：0 热替换，3 温重启，其他一律报错且不发送。
- `hotReloadPackage` 结束时把 app 编成一份带 IR 的 library klib，用一个 `compat-classify.py baseline` 进程为所有模块写基线
  （`build/hotreload/compat/<模块>.json`）。klib 的 dump 走插件自己的 klib 守护进程（`--daemon`），快照按 klib 的 sha256 缓存（`--cache-dir`）。
- `hotReload`：`compat-classify.py check` 在后台运行，同时编译补丁；库模块直接传当前 klib（sha256 没变就不 dump），
  app 只在源码比上次快照新时才重编 library klib，否则传 `app=-`。补丁准备好后等分类结论，HOT 才发送，发送后新快照成为基线。
  结论是 WARM 时补丁丢弃；补丁本身编不过但结论是 WARM 时照样温重启；app 有编译错误时报编译器的原话。
- 温重启：读 app 写的 `Documents/hr-state.json`（`{"page":N,"count":M}`）→ 停 app → 重新打包并写基线 → 用 `--hr-state=N,M` 启动，
  等到首帧标记所在的行同时出现 `page=N` 和 `count=M` → 打印 `WARM OK ms=… ms_package=… ms_relaunch=… page=N count=M`。
  app 不写这个文件也能温重启，只是回到第 0 页；启动日志里出现 `EH FAIL` 时任务失败。
- 原因按分类器的原样输出，每行 `WARM <类别> <符号>: <说明>`。

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
    id("com.solufee.cmp.hotreload") version "0.1.0"
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
  `daemon/HrCompileDaemon.java` 原样打进 jar，sha256 记在 jar 里的 `tools-manifest.properties`；
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
判定口径：**各段大小和符号表必须完全一致；`__text` 的哈希必须落在基线构建出现过的集合里**。
原因是当前钉死的工具链 release 产物本身不可逐位复现：`__DATA_CONST,__const` 里两个数据槽的顺序会在两次构建之间对调，
`__text` 随之有 78 个字节不同。不应用插件、没有守护进程的对照构建（5 次）里同样出现两种哈希，所以这与热重载无关。
所以记基线时会连续构建，直到见到两种 `__text` 变体或达到 `cmp.hotreload.releaseBaselineRuns`（默认 6）次；这几次之间大小或符号不同就直接报错（说明输入在变）。比对时只构建一次。根因在工具链（编译器/链接器）一侧，作为已知问题记录。
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

- `ToolchainValidatorTest`：版本、commit、补丁哈希、Xcode、缺文件。
- `PipelineTest`：模块归类（最长包名匹配、根包）、源码 package 读取、内置工具哈希。
- `ProtocolContractTest`：见上；不给工具链时，连真实守护进程的那一项跳过。
- `PluginFunctionalTest`（TestKit）：报错文本、debug 开关、DSL 配错时其它任务照常运行、configuration cache。

`test-projects/three-modules/` 是三模块（app → ui → core）的验收工程，验收脚本是 `accept-three.py`。
