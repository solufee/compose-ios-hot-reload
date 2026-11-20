# 试用指南（0.2.2，实验性）

这份指南带你在自己的 Mac 上，把 Compose Multiplatform 的 iOS 模拟器热重载接进工程：改 Kotlin 代码、执行一条命令，
正在运行的 app 不重启就换上新代码，页面和 `remember` 状态都还在。有两种接法：

- **已有的 Kotlin Multiplatform 工程**（例如 [KMP 向导](https://kmp.jetbrains.com) 生成的 Compose Multiplatform 工程）：
  看第 2 节下载工具链，然后直接看**第 7 节**；
- **从空目录开始的 app + ui 双模块工程**（不用 Kotlin Multiplatform 插件）：按第 2–4 节做。

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

工具链版本：**2.5.255-hotreload.6**。用仓库里的下载脚本，它会逐个校验 sha256、解包，并把路径本地化：

```bash
git clone https://github.com/solufee/compose-ios-hot-reload.git
python3 compose-ios-hot-reload/scripts/fetch-toolchain.py \
  https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.6/toolchain.json \
  --cache ~/Library/Caches/compose-ios-hot-reload/toolchains
```

完成后得到 `~/Library/Caches/compose-ios-hot-reload/toolchains/2.5.255-hotreload.6/`（下文记作 `<TOOLCHAIN>`）：

| 目录 | 内容 |
|---|---|
| `kotlin-native/` | 打过补丁的 Kotlin/Native，里面有 `toolchain.json` |
| `compose-klibs/`、`compose-plugin/` | Compose fork 的 klib 和编译器插件 |
| `kaldo/` | libKaldo（app 里负责加载新代码的库） |
| `orc-runtime/`、`cmp-uikit-utils/`、`llvm-tools/` | 打包和启动要用的运行库与工具 |
| `external-klibs/`、`bundled-klibs/` | 公开依赖 klib（见下） |
| `klibs.txt` | 编译用的全部 klib 清单 |

sha256 对不上时脚本会删掉文件并以退出码 3 结束，不会留下没校验过的文件。网络中断时脚本会自动重试几次；如果最后仍然失败（退出码 5），检查网络后重新运行同一条命令即可，已经下载并校验过的部分会跳过。

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

**第 2 步：根目录 `build.gradle.kts`**，应用插件并声明两个模块：

```kotlin
plugins {
    id("com.solufee.cmp.hotreload") version "0.2.2"
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

// 可选：温重启时保留状态（见下一小节）。page、count 之外的字段（这里的 liked）由 app 自己定义。
object HrState {
    var page = 0
    var count = 0
    var liked = false

    private fun file(): String? =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)?.let { "$it/hr-state.json" }

    fun restore(args: Array<String>) {
        // 温重启时插件把重启前的 hr-state.json 原样放回 Documents/
        val text = file()?.let { NSString.create(contentsOfFile = it, encoding = NSUTF8StringEncoding, error = null)?.toString() }
        if (text != null) {
            page = Regex("\"page\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            count = Regex("\"count\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            liked = text.contains("\"liked\":true")
        }
        // 兼容：插件同时用 --hr-state=page,count 传页面和计数
        val v = args.firstOrNull { it.startsWith("--hr-state=") }?.substringAfter('=')?.split(',') ?: return
        page = v.getOrNull(0)?.toIntOrNull() ?: page
        count = v.getOrNull(1)?.toIntOrNull() ?: count
    }

    fun save(page: Int, count: Int, liked: Boolean) {
        val path = file() ?: return
        NSString.create(string = "{\"page\":$page,\"count\":$count,\"liked\":$liked}").writeToFile(path, true, NSUTF8StringEncoding, null)
    }
}

@Composable
fun App() {
    var page by remember { mutableStateOf(HrState.page) }
    var count by remember { mutableStateOf(HrState.count) }
    var liked by remember { mutableStateOf(HrState.liked) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }   // 每秒一次重组，改动最多 1 秒后显示
    LaunchedEffect(page, count, liked) { HrState.save(page, count, liked) }
    println("RENDER_APP page=$page count=$count tick=$tick")       // readyMarker；温重启时插件在这一行里找 page= 和 count=
    MaterialTheme {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.Center) {
            if (page == 0) {
                Greeting("hot reload", tick)
                Text("Counter: $count")
                Button(onClick = { count++ }) { Text("+") }
                Button(onClick = { liked = !liked }) { Text(if (liked) "Unlike" else "Like") }
                Text("Liked: $liked")
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
        w.rootViewController = ComposeUIViewController { App() }
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

遇到不能热替换的改动（见第 4 节），插件会重新打包、重新安装并重启 app，内存里的 `remember` 状态都会丢失。
上面模板里的 `HrState` 演示了怎么把需要的状态带过温重启：

- 状态一变，`HrState.save` 就把它写到 app 沙盒的 `Documents/hr-state.json`。文件内容由 app 自己决定，模板里是
  `{"page":N,"count":M,"liked":true}`，可以加任何字段；
- 温重启时插件在停掉 app 之前读出这个文件，重新安装后**原样放回**新安装的 `Documents/`，然后启动；
  `HrState.restore` 在 `main` 里读它，作为初始值。文件里有 `page`、`count` 时，插件还会用 `--hr-state=N,M` 传这两个值，
  并等到 `readyMarker` 那一行同时出现 `page=N` 和 `count=M` 才算恢复完成；
- 不写这个文件也能温重启，只是回到初始状态。普通的 `hotReloadRun` 每次都重新安装，不会带上旧文件。

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
`app loaded the patch (… ms)`（app 已经装上）。从执行命令到模拟器上变成 “Hi, hot reload (… s)”，实测 5–6 秒
（启动后的**第一次**热重载要 9–10 秒，因为编译守护进程刚起来还要预热，之后才是 5–6 秒）；
计数器的值、所在页面都不变，进程也不重启。改 `main.kt` 里 `Text("Counter: $count")` 的文字也一样，大约 4–5 秒。

改到下列内容时，热替换不安全，会自动改为“温重启”：重新打包、重启 app，并回到原来的页面（约 17 秒，其中重新打包约 11 秒）：
类的字段布局、函数签名、`inline` 函数、`suspend` 函数，以及新增顶层属性。输出里会逐行列出 `WARM …` 原因，最后是
`WARM OK ms=…`。

### 停止

```bash
./gradlew hotReloadDaemonStop                              # 停掉本工程的两个编译守护进程
xcrun simctl terminate <UDID 或 booted> com.example.myapp    # 停掉 app（换成你的 CFBundleIdentifier）
./gradlew --stop                                           # 可选：停掉 Gradle 自己的守护进程
```

`./gradlew hotReloadClean` 会停掉编译守护进程并删除 `build/hotreload/`（约 1 GB，包括打包缓存），下次打包从头建，约 2 分钟。

编译守护进程闲着时不占 CPU，下次 `hotReload` 会自动复用；只有换了工具链或插件版本时，插件才会自己重启它们。

## 5. 已知限制

- 只支持 iOS 模拟器（arm64）和 Apple Silicon 主机；真机不支持。
- 工具链版本固定，插件只认 2.5.255-hotreload.6。Xcode、模拟器 runtime 不对时，插件会拒绝运行，并说明哪里不对。
- 热替换延迟：在我们的测试机上，只改 ui 约 5 s、只改 app 约 4 s（保存到新画面）。
- 温重启约 17 s（实测 17–24 s），其中约 11 s 是重新打包；清理过 `build/hotreload`（`hotReloadClean`）后的第一次打包要重建缓存，约 2 分钟。温重启只恢复能序列化的状态（例如页面、计数器），其它 `remember` 状态会丢失。
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
| `hotReloadRun` 报 `port … is already in use by …` | 同一台模拟器上另一个 app（别的工程，或者没停掉的旧 app）占着热重载端口；停掉它（`xcrun simctl terminate <UDID> <它的 bundle id>`），或者给这个工程换端口：`local.properties` 里写 `cmp.hotreload.port=<端口>` |
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

## 7. 接入已有的 Kotlin Multiplatform 工程（0.2.0 起）

插件直接读你工程里 Kotlin Multiplatform 插件的设置：iOS 模拟器目标用到的源码集（`commonMain` → `iosMain` →
`iosSimulatorArm64Main`，按 `dependsOn` 关系，`expect`/`actual` 照常用），以及这个模块的 iOS 依赖（例如
kotlinx-datetime）。它只**读**这些设置，不改你的 Gradle、Kotlin Multiplatform 和 Xcode 配置；release 构建、
Xcode 工程（`iosApp/`）和你平时的 debug 构建都和原来一样。热重载用的是插件自己按工具链编出来的一个独立模拟器 app。

### 支持范围

| 项 | 0.2.0 |
|---|---|
| 目标 | 只有 `iosSimulatorArm64()`（Apple Silicon 上的 iOS 模拟器）。模块里还有 Android、iosArm64、桌面等目标没关系，热重载不编它们 |
| 应用插件的模块 | 含 `fun MainViewController()` 的那个模块（向导模板里叫 `shared` 或 `composeApp`）。插件自己生成 UIKit 入口（调用 `MainViewController()`）和 Info.plist |
| Swift / Xcode 那层 | **不参与热重载**。插件生成的入口绕过模板里的 `iosApp/`（`iOSApp.swift`、`ContentView.swift` 和 Xcode 工程设置）：热重载运行的 app 里，Swift 侧的代码不生效，只有 `MainViewController()` 往下的 Kotlin/Compose 部分。正式 app 仍然用 Xcode 工程构建，不受影响 |
| 源码 | 这个模块的 `iosSimulatorArm64Main` 和它 `dependsOn` 的全部源码集，包括默认层级里的 `appleMain`、`iosMain`、`commonMain`，以及 Compose 资源（`composeResources/`，第一次打包时由 Compose 插件生成） |
| 同工程的其他 KMP 模块 | 这个模块依赖的同工程模块（含间接依赖，例如 `shared` → `core` → `base`）都会一起热重载，作为库模块，源码必须写 `package`；它们自己的 `expect`/`actual` 和 Compose 资源也都支持（0.2.1 起，按三模块工程验收） |
| Compose 资源 | 字符串、图片等放在各模块 `composeResources/` 里的资源，打包时拷进 app。只改资源时 `./gradlew hotReload` 会自动温重启（输出 `WARM compose-resources: …`）来换上新资源；资源不能热替换 |
| 第三方依赖 | 自动从 `iosSimulatorArm64CompileKlibraries` 解析（含传递依赖）。Kotlin 标准库、kotlinx、Compose、AndroidX 这些工具链自带的库，一律换成工具链里的那一份（不会链进两份） |
| Compose 版本 | 每个 Compose 模块（`org.jetbrains.compose.*`、`androidx.compose.*`）和工具链里**同名的那一份**比版本（两套版本号各比各的）：比工具链的新就在打包前报错，写出是哪个依赖拉进来的。工具链的 Compose fork 是 **1.13.0-beta01**，所以工程里的 Compose Multiplatform 用 1.13.0-beta01 或更早的版本。工具链里没有的 Compose 模块不做版本判断 |
| 依赖库的 klib 版本 | klib ABI 在 **1.6.0（Kotlin 1.6.10）到 2.5.0** 之间、metadata 不高于 **2.6.0**，即用 Kotlin 2.5 及更早版本编出的库。超出范围的库在打包前报错，报错里有库名、版本和该怎么换 |
| 带原生代码的库（cinterop） | **0.2.0 不支持**。依赖里有 cinterop klib（绑定系统框架、静态库或 C 库的那类，例如 SQLite 驱动）时，打包会打印 `warning: cinterop klibs … are not supported in 0.2.0`，并列出是哪些库、谁拉进来的、要链接什么；它们可能链接失败或运行出错。kotlinx-datetime 这类纯 Kotlin 库没有问题 |
| 你工程里的 Kotlin 版本 | 不受限制（向导模板的 2.4.21 实测可用）：热重载不用它编译，只用它读设置 |

工具链里的 kotlinx、AndroidX 比你工程里用的旧时（例如模板用 lifecycle 2.11.0，工具链里是 2.9.x），打包时会打印一行
`hot reload compiles against the toolchain's older copies of …`。这时只用到了旧版本里也有的 API 就没问题；用到新 API 的
代码在热重载里编译不过。

### 接入（三步）

以向导生成的、只勾选 iOS 的工程为例（模块叫 `shared`，包名 `org.example.kmphr`）。

**第 1 步：`settings.gradle.kts`**，在已有的 `pluginManagement { repositories { … } }` 里**最前面**加上插件仓库：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://solufee.github.io/compose-ios-hot-reload/maven/") }
        // 下面是原来就有的 google { … }、mavenCentral()、gradlePluginPortal()，不动
    }
}
```

**第 2 步：`shared/build.gradle.kts`**（含 `MainViewController()` 的模块），在 `plugins { }` 里加一行：

```kotlin
plugins {
    // 原来的 kotlinMultiplatform、composeMultiplatform、composeCompiler 不动
    id("com.solufee.cmp.hotreload") version "0.2.2"
}
```

不需要写 `cmpHotReload { }`：源码集、入口、依赖都自动找到。

**第 3 步：根目录的 `local.properties`**（没有就新建；不要提交到 git），指向第 2 节下载的工具链，写绝对路径：

```properties
cmp.hotreload.toolchainDir=<TOOLCHAIN>/kotlin-native
# 可选，同第 3 节：cmp.hotreload.simulatorUdid=<UDID>、cmp.hotreload.port=5567
```

你的源码不用改。再把 `shared/.hr/` 和 `shared/launch.log` 加进 `.gitignore`（热重载的 socket 和 app 日志放在应用插件的模块目录下）。

### 运行

在工程根目录执行（Gradle 会找到 `shared` 里的同名任务；也可以写全 `:shared:hotReloadPackage`）：

```bash
./gradlew hotReloadPackage   # 第一次约 2 分钟（解析依赖、建 Kotlin 标准库和依赖库的缓存），之后约 15 秒
./gradlew hotReloadRun       # 安装并启动，首帧出现后返回
```

改代码后：

```bash
./gradlew hotReload
```

- 改 `commonMain` 或 `iosMain` 里函数的**函数体**（包括 `actual` 函数的函数体）：热替换，输出 `compat: HOT`，进程不变；
- 改 `expect`/`actual` 的**签名**，或者**增删一对** `expect`/`actual`：不能热替换，自动温重启（输出里有
  `WARM signature …` 或 `WARM expect-actual …`，最后是 `WARM OK`），约 20 秒；
- 工具链 .6 起，热替换成功后整个界面会完整重组一次（`remember` 的状态保留，最多多几十毫秒），参数没变的调用点、
  已有的 lambda、打开着的 `Dialog` 也会马上显示新代码，不用点任何东西。想对比旧行为时写 `cmpHotReload { forceRecompose = false }`
  （或 `-Pcmp.hotreload.forceRecompose=false`），下次启动 app（`hotReloadRun` 或温重启）生效；
- 只改 `composeResources/` 里的字符串、图片等资源时，`hotReload` 会自动温重启（输出 `WARM compose-resources: …`），
  因为资源是打包时拷进 app 的；
- 其余规则同第 4、5 节。注意向导模板的 `App()` 把问候语放在 `remember { Greeting().greet() }` 里：`remember`
  只算一次，界面重组也不会重算它，改了 `greet()` 以后要让这块重新显示（点两下 “Click me!”）才能看到新文字。

生成的 app 名是根项目名（只保留字母和数字，例如 `KmpHr`），bundle id 是 `MainViewController` 所在包名加
`.hotreload`（例如 `org.example.kmphr.hotreload`），所以不会覆盖你用 Xcode 装的正式 app。停止：

```bash
./gradlew hotReloadDaemonStop
xcrun simctl terminate <UDID 或 booted> org.example.kmphr.hotreload
```

### 已知限制：还在用旧工具链 2.5.255-hotreload.4 时

旧工具链 2.5.255-hotreload.4 在把 Info.plist 里的布尔值交给 Kotlin 时有缺陷：Compose 启动时对 Info.plist 的严格检查
（`CADisableMinimumFrameDurationOnPhone`）会误判，app 一启动就退出，`launch.log` 里能看到这项检查的报错。2.5.255-hotreload.5 起已经修好，插件 0.2.0 起也只认 .5，请按第 2 节换成 .5。
用 0.1.x 插件和 .4 时，临时绕法是把 `MainViewController.kt` 改成下面这样，**只在试用时改，不要提交**（它会关掉正式 app 里的这项检查）：

```kotlin
fun MainViewController() = ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { App() }
```

### 可选配置

只在自动识别不对时才需要：

```kotlin
cmpHotReload {
    app(":shared") {
        bundleId = "com.example.hr"                      // 默认：入口所在包名 + .hotreload
        iosEntry = "com.example.ui.MainViewController"   // 默认：自动找 fun MainViewController()
        sourceSets {                                     // 默认：从 Kotlin Multiplatform 插件读
            create("commonMain") { srcDir("src/commonMain/kotlin") }
            create("iosMain") { srcDir("src/iosMain/kotlin"); dependsOn("commonMain") }
        }
    }
    extraKlibs.from("libs/my-lib.klib")   // 另外要链进来的 klib（不在 Gradle 依赖里的）
    resolveDependencyKlibs = false        // 不从 Gradle 解析依赖，只用工具链和 extraKlibs
}
```

## 8. 复合构建（includeBuild，0.2.2 起）

适用于用 `includeBuild(...)` 把另一个 Gradle 构建拉进来的工程，最常见的是“边改库边跑 app”：app 在主构建里，
KMP 库在被包含的构建（下称 included build）里，app 用 `group:name:version` 依赖它，由 Gradle 自动替换成源码工程。

### 两档支持

Gradle 不让一个构建在配置阶段看到另一个构建的工程模型，所以插件分两档：

| | 不做任何声明（默认） | 在 app 里声明 `includedModule(...)` |
|---|---|---|
| included build 的模块怎么进来 | 由那个构建用它自己的 Kotlin 插件编成 klib，app 链接这个 klib | 插件用工具链直接编译它的源码，和主构建里的库模块一样 |
| 改它的代码 | 能用，`hotReload` 会自动温重启（输出 `WARM external-klibs: …`） | 热替换（`compat: HOT`），规则同第 4、7 节 |
| 它的 `expect`/`actual`、Compose 资源 | 资源会打进 app | 都支持（改签名、增删一对、改资源时温重启） |
| 打包时的提示 | 每个这样的模块打印一行 `… comes from the included build … To hot reload it: cmpHotReload { includedModule(…) }` | 无 |

声明写在应用插件的 app 模块里，按依赖顺序写（被依赖的写在前面）：

```kotlin
cmpHotReload {
    includedModule("lib-build", ":util")   // includeBuild("lib-build") 里的 :util
    includedModule("lib-build", ":core")   // :core 用到 :util，写在后面
}
```

第一个参数是 included build 的名字（默认是 `includeBuild(...)` 目录的名字），第二个是它里面的工程路径。插件默认按
Kotlin Multiplatform 的默认层级找源码：`src/commonMain`、`src/nativeMain`、`src/appleMain`、`src/iosMain`、
`src/iosSimulatorArm64Main` 下的 `kotlin/`，以及 Compose 为它生成的资源访问代码；模块有 `composeResources/` 时，
插件会先让那个构建跑它的资源生成任务。目录或源码集不是默认的写法时：

```kotlin
includedModule("lib-build", ":core") {
    projectDir = "libs/core"                       // 在 lib-build 里的目录（默认由工程路径推出：:core -> core）
    sourceSets {
        create("commonMain") { srcDir("src/commonMain/kotlin") }
        create("iosMain") { srcDir("src/iosMain/kotlin"); dependsOn("commonMain") }
    }
    generatorTasks = listOf(":core:generateComposeResClass")   // 编译前先跑的那个构建里的任务（默认：Compose 资源生成）
}
```

### 插件应用在 included build 里

插件也可以应用在 included build 的某个模块上（比如库自带一个演示 app），在主构建里直接运行：

```bash
./gradlew :lib-build:demo:hotReloadPackage
./gradlew :lib-build:demo:hotReloadRun
./gradlew :lib-build:demo:hotReload
```

这时插件以它所在的构建为准：读 `lib-build/local.properties`（工具链、模拟器、端口），工作目录是
`lib-build/demo/build/hotreload/`；那个构建里它依赖的其他模块照常作为库模块热重载。插件的仓库要写在
**这个构建自己的** `settings.gradle.kts` 的 `pluginManagement { repositories { … } }` 里：included build 不会用主构建的
`pluginManagement`。两个 app 同时运行时，给它们配不同的端口（`cmp.hotreload.port`）。

### 支持范围

- 实测过的组合（`gradle-plugin/test-projects/composite/`）：主构建有两个 KMP 模块（其中一个通过 `build-logic` 里的
  convention plugin 应用 KMP），included build 有三个（`:util`、带 `expect`/`actual` 和字符串资源的 `:core`、应用了插件的 `:demo`）；
  两档、Dialog、release 校验都在里面。
- KMP 由 convention plugin 应用（`pluginManagement { includeBuild("build-logic") }`）没有问题：插件只读 Kotlin 插件的设置，不管它是谁应用的。
  `buildSrc` 的情况同理，但没有单独验收。
- **不支持**：included build 的模块依赖主构建的模块（反方向）；included build 改了构建目录（`buildDir`）时，默认的生成代码目录找不到，
  需要用上面的 `sourceSets` 和 `generatorTasks` 写清楚；不声明的模块不能热替换（只会温重启）。
- Compose 资源生成任务的名字按 Compose Multiplatform 1.12/1.13 的写法；以后的版本改了名字时，用 `generatorTasks` 覆盖。

