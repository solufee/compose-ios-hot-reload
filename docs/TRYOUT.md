# 试用指南（0.1.0，实验性）

这份指南带你在自己的 Mac 上，从一个空目录开始，把 Compose Multiplatform 的 iOS 模拟器热重载接进一个 app + ui
双模块工程：改 Kotlin 代码、执行一条命令，正在运行的 app 不重启就换上新代码，页面和 `remember` 状态都还在。

> **实验性质。** 只支持 Apple Silicon Mac + iOS 模拟器；工具链（Kotlin/Native、Compose、LLVM/libKaldo）是
> 打过补丁的固定版本，插件只认这一套；不要用于发布构建。遇到问题请到
> [Issues](https://github.com/solufee/compose-ios-hot-reload/issues) 反馈，附上第 6 节列的信息。

整个过程不需要 GitHub 账号或 token：插件从 GitHub Pages 上的 Maven 仓库下载，工具链从 GitHub Release 下载。

## 1. 前提条件

| 项 | 要求 | 检查命令 |
|---|---|---|
| Mac | Apple Silicon（arm64）。我们只在 macOS 26 上测过 | `uname -m` 输出 `arm64`；`sw_vers` |
| Xcode | **27.2（27B52）**，插件会校验版本 | `xcodebuild -version` |
| iOS 模拟器 runtime | **iOS 27.2（24B8117）**，即 `com.apple.CoreSimulator.SimRuntime.iOS-27-2`。列表里有多个 iOS 27.2 时（例如带 `iPhone19-4` 后缀的设备专用版），用这一个 | `xcrun simctl list runtimes` |
| 模拟器 | 一台已启动的 iPhone 模拟器（arm64） | `xcrun simctl list devices booted` |
| JDK | 17 或 21（两个都实测过；Gradle 和编译守护进程都用它）。PATH 上没有 `java` 时，把 `JAVA_HOME` 设成 JDK 的 Home 目录 | `java -version` 或 `/usr/libexec/java_home -V` |
| Gradle | 只在第一次生成工程的 wrapper 时需要（`brew install gradle`，或从 services.gradle.org 下载 9.7.0 发行包解压后用其中的 `bin/gradle`）；之后一律用工程里的 `./gradlew` | `gradle --version` |
| Python | 3.9 及以上，macOS 自带的 `/usr/bin/python3`（3.9.6）即可（下载工具链的脚本用） | `python3 --version` |
| zstd | 用来解包工具链 | `brew install zstd` |
| 磁盘 | 工具链下载约 155 MB（Release 149 MiB + 公开 klib 5 MiB），解包后约 650 MB；工程第一次打包后 `build/` 约 1.1 GB | |

第一次编译时，Kotlin/Native 会照常把它的官方 LLVM 依赖下载到 `~/.konan/dependencies`；这是 Kotlin/Native
自身的行为，本项目不往那里放任何文件。

## 2. 下载工具链

工具链版本：**2.5.255-hotreload.4**。用仓库里的下载脚本，它会逐个校验 sha256、解包，并把路径本地化：

```bash
git clone https://github.com/solufee/compose-ios-hot-reload.git
python3 compose-ios-hot-reload/scripts/fetch-toolchain.py \
  https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.4/toolchain.json \
  --cache ~/Library/Caches/compose-ios-hot-reload/toolchains
```

完成后得到 `~/Library/Caches/compose-ios-hot-reload/toolchains/2.5.255-hotreload.4/`（下文记作 `<TOOLCHAIN>`）：

| 目录 | 内容 |
|---|---|
| `kotlin-native/` | 打过补丁的 Kotlin/Native，里面有 `toolchain.json` |
| `compose-klibs/`、`compose-plugin/` | Compose fork 的 klib 和编译器插件 |
| `kaldo/` | libKaldo（app 里负责加载新代码的库） |
| `orc-runtime/`、`cmp-uikit-utils/`、`llvm-tools/` | 打包和启动要用的运行库与工具 |
| `external-klibs/`、`bundled-klibs/` | 公开依赖 klib（见下） |
| `klibs.txt` | 编译用的全部 klib 清单 |

sha256 对不上时脚本会删掉文件并以退出码 3 结束，不会留下没校验过的文件。

脚本最后会提示 `source …/env.sh`：那是给仓库里的参考脚本（`example-gradle/hotreload.sh`）用的，用 Gradle 插件不需要。

App 还要用到 21 个公开的 klib（kotlinx-coroutines、atomicfu、kotlinx-datetime、kotlinx-serialization、
androidx collection/annotation/lifecycle/savedstate/navigationevent、Compose runtime 等）。下载脚本会按清单里固定的地址，
直接从 Maven Central 和 Google Maven 下载它们，并逐个校验 sha256，放在 `<TOOLCHAIN>/external-klibs/`。这些仓库里没有的
skiko 预发布版本的 2 个 klib 随工具链一起分发（`<TOOLCHAIN>/bundled-klibs/`）。整个过程不读你本机的 Gradle 缓存。
所以下载时需要能访问 `github.com`、`repo1.maven.org` 和 `dl.google.com`。这些 klib 的来源和许可证列在 [NOTICE](../NOTICE) 里。

## 3. 接入（三步）

插件只做 iOS 模拟器的热重载：它用工具链里的编译器直接编译 `app` 和 `ui` 两个目录里的 Kotlin 源码，
不依赖、也不修改你工程里其他的 Gradle 配置（包括 Kotlin Multiplatform 插件和 release 构建）。所以这两个模块
**不需要自己的 `build.gradle.kts`**；源码能用的库就是工具链 `klibs.txt` 里那些（Compose、kotlinx、androidx 等，见第 2 节）。

工程结构：

```
my-app/
  settings.gradle.kts
  build.gradle.kts
  local.properties          # 本机路径，不要提交到 git
  .gitignore                # 至少写上 local.properties、build/、.gradle/、.hr/、launch.log
  gradlew、gradle/wrapper/  # Gradle 9.x wrapper：第一次用 gradle wrapper --gradle-version 9.7.0 生成（见第 1 节）
  app/
    Info.plist
    src/main/kotlin/main.kt
  ui/
    src/main/kotlin/Greeting.kt
```

**第 1 步：`settings.gradle.kts`**。插件从 GitHub Pages 上的 Maven 仓库解析，不需要账号或 token；插件没有其他依赖，
这里只要这一个仓库：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://solufee.github.io/compose-ios-hot-reload/maven/") }
    }
}
rootProject.name = "my-app"
include("app", "ui")
```

**第 2 步：根目录 `build.gradle.kts`**，应用插件并声明两个模块（插件只能用在根项目上）：

```kotlin
plugins {
    id("com.solufee.cmp.hotreload") version "0.1.0"
}

cmpHotReload {
    app(":app")          // 含 main.kt 和 Info.plist 的模块
    library(":ui")       // 被 app 依赖的模块；有多个时按依赖顺序依次写 library(...)
    simulator {
        readyMarker = "RENDER_APP"   // app 首帧打印的一行字；出现它才算启动完成（可选，推荐）
    }
}
```

**第 3 步：`local.properties`**，指向第 2 节下载的工具链（写绝对路径）：

```properties
cmp.hotreload.toolchainDir=<TOOLCHAIN>/kotlin-native
# 可选：指定模拟器和端口（默认 5567）
# cmp.hotreload.simulatorUdid=<UDID>
# cmp.hotreload.port=5567
```

不写 `simulatorUdid` 时，插件用**唯一一台**已启动的模拟器；一台都没启动或同时启动了好几台，`hotReloadRun` 会列出它们并要你写上 UDID。
新建并启动一台专用模拟器：

```bash
xcrun simctl create hr-sim "iPhone 17 Pro" com.apple.CoreSimulator.SimRuntime.iOS-27-2   # 打印新 UDID
xcrun simctl boot <UDID>
open -a Simulator    # 想看到画面时
```

运行库和工具（ORC runtime、`libCMPUIKitObjcUtils.a`、`llvm-tools`）的位置写在 `<TOOLCHAIN>/kotlin-native/toolchain.json`
里，插件自己读取，不用再配。`./gradlew hotReloadCheckToolchain` 会逐项检查这些文件、Xcode 和模拟器 runtime，不对时
告诉你缺什么、怎么修。

### app 和 ui 的最小代码

`app/Info.plist`（`CFBundleExecutable` 就是 app 的进程名，`CFBundleIdentifier` 用你自己的；没有 `UILaunchScreen`
时 iOS 会把 app 当成老式 app，用 320×480 的小窗口显示）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>MyApp</string>
    <key>CFBundleIdentifier</key><string>com.example.myapp</string>
    <key>CFBundleName</key><string>MyApp</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CADisableMinimumFrameDurationOnPhone</key><true/>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>17.0</string>
    <key>UIDeviceFamily</key><array><integer>1</integer></array>
    <key>UILaunchScreen</key><dict/>
</dict>
</plist>
```

`ui/src/main/kotlin/Greeting.kt`——库模块的源码**必须写 `package`**（插件按包名把重新编译出的代码分到各个模块）：

```kotlin
package myui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Greeting(name: String, tick: Int) {
    // 函数体里读到 tick：它每秒变一次，这块每秒重新执行一次，所以改了这里的代码，最多 1 秒就会显示出来。
    // 只把 tick 当参数传进来、函数体里不读它，Compose 照样会跳过这块（见第 5 节）。
    Text("Hello, $name ($tick s)")
}
```

`app/src/main/kotlin/main.kt`——一个 UIKit 入口加一个 Compose 根视图。app 模块可以不写 `package`（如下）；
`HrState` 那部分是可选的，作用见下一小节，不需要的话删掉它和用到它的三处即可：

```kotlin
@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import myui.Greeting
import platform.Foundation.*
import platform.UIKit.*

// 可选：温重启时保留页面和计数（见下一小节）。
object HrState {
    var page = 0
    var count = 0

    fun restore(args: Array<String>) {
        val v = args.firstOrNull { it.startsWith("--hr-state=") }?.substringAfter('=')?.split(',') ?: return
        page = v.getOrNull(0)?.toIntOrNull() ?: 0
        count = v.getOrNull(1)?.toIntOrNull() ?: 0
    }

    fun save(page: Int, count: Int) {
        val dir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return
        NSString.create(string = "{\"page\":$page,\"count\":$count}").writeToFile("$dir/hr-state.json", true, NSUTF8StringEncoding, null)
    }
}

@Composable
fun App() {
    var page by remember { mutableStateOf(HrState.page) }
    var count by remember { mutableStateOf(HrState.count) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }   // 每秒一次重组，改动最多 1 秒后显示
    LaunchedEffect(page, count) { HrState.save(page, count) }
    println("RENDER_APP page=$page count=$count tick=$tick")       // readyMarker；温重启时插件在这一行里找 page= 和 count=
    MaterialTheme {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.Center) {
            if (page == 0) {
                Greeting("hot reload", tick)
                Text("Counter: $count")
                Button(onClick = { count++ }) { Text("+") }
                Button(onClick = { page = 1 }) { Text("Next") }
            } else {
                Text("Page 2, counter kept: $count")
                Button(onClick = { page = 0 }) { Text("Back") }
            }
        }
    }
}

class AppDelegate : UIResponder, UIApplicationDelegateProtocol {
    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta {}

    @OverrideInit
    constructor() : super()

    private var keyWindow: UIWindow? = null   // not "window": that name is taken by the protocol
    override fun window() = keyWindow
    override fun setWindow(window: UIWindow?) { keyWindow = window }

    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
        w.rootViewController = ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { App() }
        w.makeKeyAndVisible()
        keyWindow = w
        return true
    }
}

fun main(args: Array<String>) {
    HrState.restore(args)
    memScoped {
        val argv = (arrayOf("MyApp") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(args.size + 1, argv, null, NSStringFromClass(AppDelegate))
        }
    }
}
```

### 可选：温重启时保留状态

遇到不能热替换的改动（见第 4 节），插件会重新打包并重启 app。上面模板里的 `HrState` 让 app 重启后回到原来的页面和计数：

- 页面或计数一变，`HrState.save` 就把它写到 app 沙盒的 `Documents/hr-state.json`（`{"page":N,"count":M}`）；
- 温重启时插件读这个文件，用参数 `--hr-state=N,M` 启动 app，`HrState.restore(args)` 把它当作初始值；
- 插件等到 `readyMarker` 那一行同时出现 `page=N` 和 `count=M`，才算状态已经恢复。

不写 `HrState` 也能温重启，只是回到初始页面。插件只保存和恢复这两个整数；其他 `remember` 状态在温重启后都会丢失。

## 4. 运行

```bash
./gradlew hotReloadPackage   # 第一次较慢（实测约 4 分钟）：要现建 Kotlin 标准库缓存；之后快得多
./gradlew hotReloadRun       # 安装并启动，app 出现首帧后命令返回，app 继续运行
```

然后改代码、再执行一条命令：

```bash
# 例：把 ui/src/main/kotlin/Greeting.kt 里的 "Hello" 改成 "Hi"，保存
./gradlew hotReload
```

成功时输出里依次有 `compat: HOT …`（这次改动能热替换）、`sent …`（补丁已发给 app）和
`app loaded the patch (… ms)`（app 已经装上）。从执行命令到模拟器上变成 “Hi, hot reload (… s)”，实测 5–6 秒；
计数器的值、所在页面都不变，进程也不重启。改 `main.kt` 里 `Text("Counter: $count")` 的文字也一样，大约 4–5 秒。

改到下列内容时，热替换不安全，会自动改为“温重启”：重新打包、重启 app，并回到原来的页面（约 50 秒）：
类的字段布局、函数签名、`inline` 函数、`suspend` 函数，以及新增顶层属性。输出里会逐行列出 `WARM …` 原因，最后是
`WARM OK ms=…`。

### 停止

```bash
./gradlew hotReloadDaemonStop                              # 停掉本工程的两个编译守护进程
xcrun simctl terminate <UDID 或 booted> com.example.myapp    # 停掉 app（换成你的 CFBundleIdentifier）
./gradlew --stop                                           # 可选：停掉 Gradle 自己的守护进程
```

编译守护进程闲着时不占 CPU，下次 `hotReload` 会自动复用；只有换了工具链或插件版本时，插件才会自己重启它们。

## 5. 已知限制

- 只支持 iOS 模拟器（arm64）和 Apple Silicon 主机；真机不支持。
- 工具链版本固定，插件只认 2.5.255-hotreload.4。Xcode、模拟器 runtime 不对时，插件会拒绝运行，并说明哪里不对。
- 热替换延迟：在我们的测试机上，只改 ui 约 5 s、只改 app 约 4 s（保存到新画面）。
- 温重启约 64 s，其中约 53 s 是重新打包；温重启只恢复能序列化的状态（例如页面、计数器），其它 `remember` 状态会丢失。
- 兼容性检查（判断能不能热替换）和编译同时进行。只改 app 时检查本身约 0.6 s，但被编译时间盖住，端到端只多十几毫秒。
- 被替换的 `@Composable` 要在下一次重组时真的被执行，新代码才会显示。Compose 会跳过参数没变、也不读任何 state 的 composable：只传一个会变的参数还不够，**函数体里必须读到它**（或者读一个 `State`），否则热替换已经生效，画面却要等这块被重新绘制（例如翻页再回来）才会变。
- 长时间运行（几个小时、上万次重组）后，热替换会慢 1 s 左右，重启 app 就恢复。
- 详见 [docs/LIMITATIONS.md](LIMITATIONS.md)。

## 6. 出问题时收集什么

### 常见情况

| 现象 | 处理 |
|---|---|
| `hotReloadCheckToolchain` 失败 | 报错里逐项列出“期望 / 实际 / 怎么修”：多半是 Xcode 或模拟器 runtime 版本不对，或者 `toolchainDir` 指错（要指到 `<TOOLCHAIN>/kotlin-native`） |
| `hotReloadRun` 报 “app did not start after 3 attempts” | 看 `launch.log` 最后几行；`readyMarker` 写的字 app 有没有打印出来；模拟器有没有启动 |
| `hotReload` 打印 `compat: WARM ...` | 这次改动不能热替换（原因按行列出），插件会自动温重启；不想自动重启就在 DSL 里写 `warmRestart = false`，或者运行 `./gradlew hotReload --no-warm`，这时只报原因、不发送 |
| `hotReload` 报编译错误 | 修好代码后再执行一次 `hotReload`，不用重新打包；编译守护进程不会因此退出 |
| 怀疑状态乱了 | `./gradlew hotReloadDaemonStop`，删掉 `build/hotreload/`，再 `hotReloadPackage` 和 `hotReloadRun` |
| `hotReload` 成功、画面没变 | 被改的 composable 这次没有重新执行：它读的值都没变（见第 5 节）。按第 3 节模板让它读 `tick`，或者翻页再回来看 |
| 输出里有 `no reaction from the app … within 5 s` | 补丁发出去了，但 app 没有反应：app 没在运行，或者端口不对。先 `./gradlew hotReloadRun` |

打包日志里的 `hot packages: myui` 列的是库模块的包名（以及 app 里写了 `package` 的包）。app 模块不写 `package` 时不会出现在这一行，
但它的代码同样能热替换（app 的代码在根包里，插件始终会把它算进去）。

### 提 Issue 时请附上

先把输出里你的用户名、本机路径替换成占位符：

1. 环境：`uname -m`、`sw_vers`、`xcodebuild -version`、`xcrun simctl list runtimes`、`java -version`。
2. 工具链校验：`./gradlew hotReloadCheckToolchain --console=plain` 的完整输出。
3. 出错命令的完整输出，加 `--info`：例如 `./gradlew hotReload --info --console=plain > hotreload.txt 2>&1`。
   插件版本和它用的内置工具会在输出的 `tools from plugin jar (sha256 …)` 这一行里。
4. app 的控制台日志：工程目录下的 `launch.log`（`hotReloadRun` 和温重启写的，每次启动会清空重写）。
   `[kaldo]` 开头的是负责装载新代码的库的输出：启动时应该有 `EH check: … 0 pc-out-of-range` 和
   `bootstrap objects loaded with success!`；每次热替换会多出几行（如 `KotlinSymbolExternalizer has externalized …`），
   插件看到它们才打印 `app loaded the patch`。热重载失败时这几行最有用。
5. 编译守护进程日志：`build/hotreload/daemon-compile.log`（app 的增量编译）和 `build/hotreload/daemon-klib.log`
   （库模块和兼容性检查）。守护进程的 socket 在工程下的 `.hr/`；工程路径太长时改放在
   `~/Library/Caches/cmp-hot-reload/<12 位哈希>/`。
6. 兼容性检查的快照：`build/hotreload/compat/*.json`（可选，判断“为什么被判成要温重启”时有用）。
7. 能复现的最小改动：改了哪个文件的哪一行，改前改后。
