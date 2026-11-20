# Gradle 示例：app + ui 双模块

一个最小 Gradle 工程，用的是仓库里的插件 `com.solufee.cmp.hotreload`（`../gradle-plugin`，见那里的 README）。
工程里只有三样东西：`settings.gradle.kts` 里 `includeBuild("../gradle-plugin")`，根 `build.gradle.kts`
里应用插件并声明 `app(":app")`、`library(":ui")`，以及不进 git 的 `local.properties`：

```properties
cmp.hotreload.toolchainDir=<KOTLIN_NATIVE_DIST>   # 含 toolchain.json
```

```bash
./gradlew hotReloadPackage   # 出 build/ComposeHR.app
./gradlew hotReloadRun       # 安装并启动，首帧出现后返回
./gradlew hotReload          # 编译改动、做符号检查、ld -r 合并、发送
```

`hotReloadPackage` 会依次执行：`hotReloadCheckToolchain`（版本校验）→ `hotReloadCompileKlibUi`（ui → klib，
经守护进程）→ `hotReloadPackage`（split 编译 app + 合并 payload + 嵌入 guest + 组装 `build/hotreload/ComposeHR.app`）。
这些步骤都在插件里实现，**本工程不再调用 `hotreload.sh`、`run.sh`**；这两个脚本留在这里作为底层工具的参考驱动
（`docs/INTERFACE.md` §5），下面「环境变量」「常驻编译进程」「运行」几节描述的也是它们。

插件接入后的验收：

| 文件 | 内容 |
|---|---|
| `accept-plugin-p0.txt`、`accept-plugin-p0-multifile.txt` | P0（插件包装脚本）：ui 20、app 20、both 3、多文件 3 |
| `accept-plugin-p1.txt`、`accept-plugin-p1-multifile.txt` | P1（插件内实现）：同上 |
| `accept-plugin-p1-coldstart.txt` | P1：`hotReloadRun` 冷启动 10 次，全部首次成功 |

验收脚本可用 `HR_UDID=<UDID>` 只认指定模拟器上的进程，适合多台模拟器同时跑 app 的情况。

## 前置

- 按仓库根 README 构建好 Kotlin/Native dist、Compose klib、libKaldo。
- `tools/klibs.txt` 里是 klib 列表（占位符，按你的路径生成）。
- `tools/libCMPUIKitObjcUtils.a` 和 `tools/liborc_rt_iossim_arm64.a` 不入库，需要自己放进来：
  - `libCMPUIKitObjcUtils.a`：从 Compose fork 的 `CMPUIKitUtils.xcodeproj` 构建；
  - `liborc_rt_iossim_arm64.a`：从 LLVM 构建目录里的 `lib/clang/21/lib/darwin/liborc_rt_iossim.a` 复制。

环境变量：`KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`IC_DIR`、`AUTO_CACHE`；
默认值在 `hotreload.sh` 顶部。

## 常驻编译进程

两个守护进程共用一份编译器 jar，socket 都在项目内 `.hr/`（目录 0700、socket 0600）：

| socket | 模式 | 用途 |
|---|---|---|
| `hr-compile.sock` | `KONAN_HOT_RELOAD_CACHE_ONLY=1` | 编辑器路径：只构建缓存，不生成 host/链接 |
| `hr-klib.sock` | 普通编译 | `:ui:compileKlib`，把 ui.kt 编成 klib |

```bash
HR_WORKDIR=$PWD ../daemon/daemon-start.sh --force
```

两个 socket 默认都在 `.hr/`（`HRC_SOCKET_DIR`，接口 v8 起 `hr-compile.sock` 也有默认路径），存在就自动使用；
没有 socket 时回退到直接起 `kotlinc-native` 并打印一行提示，结果正确，但每次 ui 热替换多约 3 s
（实测，开分类器：8.95 s 对 5.93 s；v7 及以前 `hr-compile.sock` 要显式导出 `HRC_SOCKET` 才会用）。ui klib 的编译从约 2.5 s 降到 **0.25 s**；ui-only 端到端 6.9 s → **4.34 s**。

## 运行

```bash
./gradlew hotReloadPackage
./run.sh                      # 安装并启动，带 KALDO_SYNTH_EH_FRAME=1
```

## 热重载

```bash
# 改 ui 模块
sed -i '' 's/UI_MARK = "ui-A"/UI_MARK = "ui-B"/' ui/src/main/kotlin/ui.kt
./gradlew hotReload

# 改 app 模块
sed -i '' 's/APP_MARK = "app-A"/APP_MARK = "app-B"/' app/src/main/kotlin/main.kt
./gradlew hotReload

# 两个一起改：同一条命令、同一个对象、同一个 tick 生效
```

`hotreload.sh reload` 的流程：cache-only 编译 → 按 mtime 过滤本次真正重建的 archive →
用 `nm` 把对象分成 app / ui → `ld -r` 合并 → **符号检查**（每个改过的模块必须
`defined > 0` 且合并后 `kept > 0`）→ UDP 式的 TCP 短连接发给 app 的 5567 端口。

## 同一模块内改多个文件

ui 模块有两个文件：`src/main/kotlin/ui.kt`（`UI_MARK`）和 `ui2.kt`（`UI2_MARK`）。
同时改两个再 `./gradlew hotReload`，`hotreload.sh` 会把本次重建的**所有** archive 都解开，
用 `nm` 按模块归类，再用 `ld -r` 合成一个对象发送；符号检查按模块并集统计。

`accept-multifile.py` 测 3 轮，要求 `changed objects: app=<n> ui=2`、
`RENDER_UI` 和 `RENDER_UI2` 出现在同一个 tick。结果见 `accept-multifile1.txt`。

## 启动稳定性

`run.sh` 会在启动时传收窄的 `KALDO_HOT_SYMBOLS`（`build/app/hot-symbols.txt`，
由 `merge-payloads.py` 从 app + `expui` 两个用户模块自动收集，依赖库不碰）。
不传这个变量时冷启动约有 10–20% 概率在 JIT 物化阶段 SIGSEGV；传了之后
`startup-supervised.sh` 实测 30/30 首次成功、0 次重试（`startup-supervised.txt`）。
`run.sh` 仍保留最多 3 次重试作为兜底。

## 兼容性分类器与温重启（接口 v7；v8 提速）

`hotreload.sh reload` 在发送前比较新 klib 与基线快照（v8 起与 cache-only 编译并行，dump 和 app klib 编译都在 `hr-klib.sock` 守护进程里做，快照按 klib 哈希缓存）：

- 普通函数正文、文案/颜色、增删 composable、私有方法：直接热替换，PID 不变；
- 类字段布局、函数签名、inline/suspend 正文、新增顶层属性：打印 `WARM <类别> <符号>: <原因>`，
  默认自动重新打包并用 `--hr-state=page,count` 重启 app，恢复页面和计数器；
- 只看拦截、不重启：`bash hotreload.sh reload --no-warm`，需要温重启时退出 3、不发送补丁。

基线快照在 `build/compat/{ui,app}.json`，由 `package` 生成；只有 app 源文件变了才重建 app klib。v8 起分类在后台和编译
同时跑，app klib 约 0.6 s、ui 快照约 0.02 s，都藏在编译后面，发送前只多等约 8 ms。
`package` 发现 `ui.klib` 比 ui 源文件旧时会先重编 ui klib。

**注意（P1 起）**：本工程的 `./gradlew hotReload` 是插件的任务，插件在 P2 才接入分类器；上面说的是参考驱动
`hotreload.sh`。量参考驱动的延迟用 `HR_DRIVER=script python3 accept-gradle.py launch.log 20`；
开/关分类器交替对照用 `python3 accept-abab.py [对数]`（默认就走 `hotreload.sh`）。

参考 app 的状态：每次页面/计数器变化写 `Documents/hr-state.json`，启动参数 `--hr-state=PAGE,COUNT`
优先。其它 `remember` 瞬态不恢复。

## 回归：一条命令跑完改动类型矩阵

```bash
source <toolchain>/env.sh
python3 accept-b4.py
```

16 个改动用例（6 类可热替换、5 类必须温重启，含准备步骤），每条都检查退出码、PID、`RENDER_APP`
的页面/计数器，并用 `simctl io screenshot` 截图比对：可热替换必须换画面，温重启必须回到原画面
（页面 2、计数器 3）。最后一条 `12-tap-after-warm` 在温重启后的 app 上用 AXe 真实点击
Back、+、Next、Back：计数器 3 → 4、两次翻页、PID 不变、日志里没有崩溃或未捕获异常。
AXe 的元素坐标是 app 点（兼容模式窗口 320×480），脚本按屏幕尺寸换算成屏幕点再点；按 label 点会点空。
需要 `axe`（`brew install cameroncooke/axe/axe`）。结果写到标准输出，截图在 `build/regression/shots/`。

单例回归（`ComposableSingletons` 和 `object`）：

```bash
source <toolchain>/env.sh
ROUNDS=5 bash accept-singletons.sh   # 会临时改写 ui.kt，结束时恢复；之后先 package
```

在已有文件里增、删、再加 composable lambda（它们是 `ComposableSingletons$UiKt` 的新字段，只在 IR 里可见，分类器判 HOT），
再改已有 lambda 的正文。每步都要求：退出码 0、没有 WARM、同一 PID、日志里没有崩溃、新 lambda 执行，并且 `object` 恰好重建一次。
已知行为只记录、不判失败：已有静态调用点的画面可能过期（LIMITATIONS 第 11 条）。结果见 `accept-singletons.txt`。

## Kotlin Multiplatform 布局（commonMain / iosMain）

`ui-kmp/src` 和 `app-kmp/src` 是同一个示例的多平台版本：每个模块都按 `commonMain → iosMain → iosSimulatorArm64Main`
分源码集，commonMain 里各有一个 `expect fun` 和一个 `expect class`（ui）或 `expect fun`（app），iosMain 里是调 UIKit / Foundation 的 actual。
用环境变量切换，其余命令不变：

```bash
source <toolchain>/env.sh
export UI_FRAGMENTS="commonMain iosMain iosSimulatorArm64Main" UI_FRAGMENT_REFINES="iosMain:commonMain iosSimulatorArm64Main:iosMain" UI_FRAGMENT_ROOT=ui-kmp/src
export APP_FRAGMENTS="$UI_FRAGMENTS" APP_FRAGMENT_REFINES="$UI_FRAGMENT_REFINES" APP_FRAGMENT_ROOT=app-kmp/src
export KALDO_HOT_PACKAGES=expui,appkmp
bash hotreload.sh ui-klib && bash hotreload.sh package && bash run.sh
```

回归：`bash accept-kmp.sh`（自己设置上面的变量，结束时恢复源码）：ui 和 app 的 commonMain 函数体、iosMain actual 函数体都要 HOT，
改 `expect` 签名要 WARM。加第三方 klib：`EXTRA_KLIB=<…iossimulatorarm64….klib> EXTRA_KLIB_USE='<返回 String 的表达式>' bash accept-kmp.sh`。
结果见 `accept-kmp.txt`、`accept-kmp-json.txt`；能读哪些版本的 klib 见 `accept-kmp-klib-versions.txt`；原理见 TECHNICAL-NOTES §11。

## 验收

`accept-gradle.py` 是这一轮用的脚本：20 次 ui、20 次 app、3 次同时改，
每轮检查新标签、另一个模块标签不变、符号计数非零、PID 不变。
结果在 `accept-gradle1.txt`（直起编译器）、`accept-gradle2.txt`（单个守护进程）、`accept-gradle3.txt`（两个守护进程）：

| 场景 | 成功 | p50 | p90 |
|---|---|---|---|
| 只改 ui | 20/20 | 4,343 ms | 4,422 ms |
| 只改 app | 20/20 | 2,632 ms | 2,652 ms |
| 同时改两个 | 3/3 | 4,248 ms | 4,356 ms |

PID 50848 全程不变。

接常驻编译进程后（`HRC_SOCKET=<socket> ./gradlew hotReload`）：只改 app 约 **2.6 s**，
只改 ui 约 **5.9 s**（`:ui:compileKlib` 仍直接起 `kotlinc-native`）。`compileKlib` 已声明
Gradle 的 `inputs`/`outputs`，app-only 时该任务是 UP-TO-DATE。

开关集中在 `hotreload.env`（`KALDO_SYNTH_EH_FRAME`、`KALDO_KEEP_COMPACT_UNWIND`、
`KONAN_HOT_RELOAD_CACHE_ONLY`），`hotreload.sh` 和 `run.sh` 都读它。
