# 限制与已知问题

> 截至 2026-11-19，接口 v8。本文件只写**现状**；已经解决的问题列在文末「已解决」表里，
> 原理见 `docs/TECHNICAL-NOTES.md`，测量结果见 `docs/VALIDATION.md`。
> 标【参考脚本】的条目只涉及仓库里的参考驱动（`example-gradle/hotreload.sh`、`run.sh`、`daemon/daemon-start.sh`）；
> 用 Gradle 插件时，插件自己负责这些开关和进程，可以跳过。

## 支持范围

| 场景 | 状态 |
|---|---|
| 改文案、颜色、普通（非 composable）函数体 | ✅ 10/10 |
| 增删 composable、改 if 分支 | ✅ 10/10 |
| 保留页面与 `remember` 状态、PID 不变 | ✅ |
| 顶层函数、final 成员改签名（含 `expect`/`actual` 函数、增删一对 `expect fun`） | ✅ 0.2.4 起热替换，调用方一起重编；`@Composable` 改签名要求 forceRecompose 开着：0.2.4 / .8 时它自己的 `remember` 重置一次，0.2.5 / .9 起保留（稳定 group key） |
| 类字段布局 / 构造函数 / open、override、接口成员的签名 / inline / suspend / 新增顶层属性 | ✅ 分类器拦下并自动温重启 |
| 温重启恢复页面与计数器 | ✅ 回到原页面，状态从 `hr-state.json` 恢复（`--hr-state` 启动参数） |
| iOS 模拟器（arm64），macOS arm64 主机 | ✅ |
| Gradle 双模块示例（app + ui），一条命令出包 | ✅ |
| 已有的 Kotlin Multiplatform 工程（向导模板，插件加在 `shared` 上） | ✅ 0.2.0：commonMain/iosMain 函数体热替换；0.2.4 起 `expect`/`actual` 函数改签名、增删一对 `expect fun` 也热替换，增删一对类等仍自动温重启 |
| KMP 工程的第三方 iOS 依赖 | ✅ 从 Gradle 解析（含传递依赖）；工具链自带的库（stdlib、kotlinx、Compose、AndroidX）只链工具链那一份 |
| 依赖库版本 | klib ABI 1.6.0–2.5.0、metadata ≤ 2.6.0；Compose 模块不比工具链里同名的那份新（插件 0.2.7 / 工具链 .11：Compose Multiplatform ≤ 1.13.0-rc01，1.13.0-beta02 和 rc01 的工程都能用，1.13.0 及以后打包前报错）；超出时打包前报错，写出库名和拉进它的依赖 |
| KMP 工程的 Swift / Xcode 层（`iosApp/`） | ➖ 热重载时不运行：插件生成的入口直接调用 `MainViewController()`，Swift 侧代码不生效；正式 app 仍走 Xcode 工程 |
| 带原生代码的依赖库（cinterop：系统框架、静态库、C 库绑定） | ❌ 0.2.0 不支持；打包时警告并列出这些库 |
| Compose 1.13 的 `ComposeUIView`（嵌在 app 自己的 `UIViewController` 里） | ✅ 0.2.3 实测（工具链 .7，CMP 1.13.0-beta02）：插件生成的入口只调用 `MainViewController()`，和它不冲突；给定宽高时正常显示，热替换后不用点就刷新。⚠️ 两点：`ComposeUIView` 里的内容不进无障碍树；只设 `preferredSizeReportingStrategy = IntrinsicContentSize`、不给宽高时，这个视图在热重载 app 里没有得到尺寸（空白），是否 Compose 本身的行为没有核实，暂时请给定宽高 |
| 复合构建（`includeBuild`） | ✅ 0.2.2：included build 的模块声明 `includedModule(...)` 后热重载；不声明时用那个构建编出的 klib，改动时温重启；插件也可以应用在 included build 里、从主构建运行（TRYOUT 第 8 节） |
| 多个 KMP 模块互相依赖 | ✅ 0.2.1：间接依赖也一起热重载；库模块的 `expect`/`actual` 和 Compose 资源都支持（三模块工程验收） |
| Compose 资源（strings、drawables） | ➖ 不能热替换：打包时拷进 app；只改资源时 `hotReload` 自动温重启 |
| 只编 `iosSimulatorArm64`；Android、iosArm64、桌面目标 | ➖ 不参与热重载，也不受影响 |
| 多模块热替换（改 ui、改 app、同时改） | ✅ 20/20、20/20、3/3 |
| 同一模块内多个文件同时改 | ✅ 3/3，同一 tick |
| 异常穿过 JIT 代码（EH） | ✅ 每次启动由 `EH OK` 探针检查 |
| 预构建工具链（打包 → 发布 → 下载） | ✅ 本地 `file://` 全流程验证；0.1.0 起从 GitHub Release 下载 |
| 真机 | ❌ 未做 |

## 已知问题

1. **温重启只恢复“可序列化状态”**。分类器拦下不兼容改动后自动重新打包、重启。参考脚本恢复页面和计数器
   （`Documents/hr-state.json` + `--hr-state` 启动参数）；插件 0.1.2 起把 app 写的整个 `Documents/hr-state.json` 原样带过重装，
   app 可以在里面放任何自己的字段（TRYOUT 第 3 节模板）。没有写进这个文件的 `remember` 瞬态（动画进度、滚动位置等）不恢复。
   参考 app 实现了这个契约；自带壳的打包方要实现同样的参数和文件才能恢复状态（INTERFACE §10.3）。
   温重启的耗时见下一条；热替换路径不受影响。
2. **温重启比热替换慢得多：参考脚本约 16 s（打包约 10.5 s），热替换是 4–5 s**。11-19 首发后提速前是约 46 s
   （打包约 40 s，其中 28 s 花在 `merge-payloads.py` 对每个对象文件单独跑 `nm`）；提速见 REPORT §25。
   现在 9 次实测总 15.8–22.7 s（中位 16.4 s）= 打包 10.4–10.9 s（编译 5.0–5.4、合并 2.4–2.6、嵌入 0.8、组装签名 1.5–1.6、
   分类器基线 0.5）+ 重启到恢复页面 5.4–12.0 s（主要是 JIT 装载合并对象，与冷启动同一段，这次没动）。
   `warm` 仍然每次跑完整 `package`。【插件】插件随包带的 `merge-payloads.py` 要等插件重新构建发布后才有前一项提速，
   缓存目录和编译走守护进程由插件 0.1.1 接入（INTERFACE §3.1、§5），插件路径的温重启约 17 s（0.1.0 约 50 s）。
3. **分类器对少数改动偏保守**。新增/删除类、包级函数增删、私有方法改动放行；0.1.2 起 final 类和 object 新增方法、新增 typealias、
   `internal`↔`public` 也放行（用例表 `gradle-plugin/test-projects/classifier-cases/`）。enum entry、data class 主构造函数这类
   未识别或改布局的成员变化仍会被拦下（`class-shape`/`field-layout`），少数本可热替换的改动会多一次温重启。宁可多重启，不静默崩溃。
   另外：`object`（含 companion object）在它所在的文件每次热替换后都会用新代码重新构造，`init` 重跑，里面的可变状态回到初值；
   要跨热替换保留的状态不要放在 object 里（放在 `remember`/`State` 或文件外的单例里）。
4. **plist 布尔值桥接（工具链 .5 已修）**。原因是 split 编译下编译器没有绑定 ObjCExport 的转换器，Kotlin 装箱值交给 ObjC 时
   不是 `NSNumber`，Compose 的严格 Info.plist 检查因此在启动时失败（TECHNICAL-NOTES §1.8；KMP 向导模板一启动就退出就是这个原因）。
   .5 起不需要 `enforceStrictPlistSanityCheck=false`，模板源码不用改；仍用 .4 及以前的工具链时需要（TRYOUT 第 7 节“已知限制”）。
5. **【参考脚本】EH 合成必须开**。`KALDO_KEEP_COMPACT_UNWIND=1`（打包）和 `KALDO_SYNTH_EH_FRAME=1`（启动）是
   正确性要求，`hotreload.env` 默认打开并导出。关掉后异常穿过 JIT 代码时进程会终止；`run.sh` 每次启动都要求
   `EH OK eh-probe`，缺了就以退出码 2 失败、不重试。启动时合成并整段注册约 12.6 万条 FDE，开 KEEP 的代价约
   0.2–0.7 s。`KALDO_SYNTH_EH_FRAME` 按值判断：未设、空串、`0` 为关（v5 起）。
6. **启动偶发失败（根因已修，LLVM 补丁，随工具链 .6 发布）**。根因是插件合成的 `_objc_msgSend$…` stub 等定义没有登记到
   materialization，却被放进 dependency group，`simplifyDepGroups` 查找失败后仍读 `end()->second`（越界读，是否崩取决于内存布局）；
   每次启动有 658 个这样的定义（TECHNICAL-NOTES §1.5）。修复后不收窄 `KALDO_HOT_SYMBOLS`、不重试，冷启动 30/30。
   收窄仍是默认：启动更快（p50 5.3 s 对 6.5 s），热替换范围也限定在用户模块；只是不再是稳定性所必需。
   用 .5 及以前的工具链时仍要收窄；`run.sh` 保留最多 3 次重试兜底。
7. **长会话退化（根因已修，工具链 .8 起没有；.7 及以前仍有）**。同一个 app 进程里，每次 reload 在 app 内的加载耗时
   和之前做过的 reload 次数成正比：工具链 .6、3 小时 341 次 reload，app 模块从发出到新帧的 app 内耗时从 0.3 s 涨到 3.2 s，
   ui 模块从 0.08 s 涨到 0.64 s（REPORT §30.1）。根因（REPORT §32）：每个 `reload<N>` JITDylib 都挂着宿主数据和 dlsym 两个
   符号生成器，后一次 reload 的链接顺序里排着之前所有的 `reload<k>`；补丁里大部分外部引用定义在 JIT 的主程序里，宿主和 dlsym
   都找不到，于是每一层的生成器都白跑一遍，链接阶段每多一次 reload 多约 10 ms。修法：下一次 reload 开始时摘掉上一层的这两个
   生成器（它们只在自己那次链接时有用）。同一个库对照 55 次 reload：链接阶段 9.89 → 0.00 ms/次，app 内耗时 app 模块
   10.0 → 0.05 ms/次、ui 模块 10.25 → 0.01 ms/次，0 失败。`KALDO_KEEP_RELOAD_GENERATORS=1` 恢复旧行为（只用于对照）。
   用 .7 及以前的工具链时，重启 app（温重启约 16 s）恢复。
8. **release 构建的确定性**。`__text` 大小和符号表一致，但每次链接整文件 md5 都不同（`LC_UUID`、
   `__objc_methname`、`__LINKEDIT`），不能用整文件 md5 做验收口径，应比较 section 大小 + 符号表 + `__text` 内容。
   另外，同一源码、同样参数两次 release 构建，`__text` 偶发 78 字节差异。根因已查清（REPORT §28）：Apple 链接器给
   `libobjc/_objc_msgSend` 建了两个 `__DATA_CONST,__got` 条目（一个给它合成的 `__objc_stubs`，一个给普通代码），两者的先后
   顺序每次链接可能不同，78 处 `ldr` 的页内偏移随之变成 `#0x458`/`#0x460`。两个槽绑定的是同一个地址，行为完全相同；
   与 Kotlin/Native、热重载无关，我们这边修不了。精确比对用 `scripts/release-text-digest.py`：把每个 GOT 读取换成它绑定的符号名再
   求哈希，两种变体得到同一个值，改一个字节的代码仍会变。插件 0.2.0 起的 release 校验就比这个摘要（各段大小和符号表照旧），原始 `__text` 哈希只记录。
9. **【参考脚本】守护进程**。两个常驻进程：`hr-compile.sock`（cache-only，编辑器路径）和 `hr-klib.sock`（ui klib），
   socket 在项目内 `.hr/`（目录 0700、socket 0600）。崩溃后不会自动重启，需要重跑 `daemon-start.sh`；
   没起守护进程时脚本回退到直接起 `kotlinc-native`（v8 起打印一行提示），结果正确但每次慢 2–3 s；兼容性分类器也会
   退回到每次起 `klib` 工具和 `kotlinc-native`（v7 的代价：ui 约 +0.6 s、app 约 +3.5 s，与编译并行后能盖住一部分）。
   v8 起两个 socket 都有默认路径 `.hr/`（`HRC_SOCKET_DIR`），不用再导出 `HRC_SOCKET`。内存：SerialGC 下连续 200 次编译，
   RSS 1.08 GB 起步、200 次后 1.32 GB 趋平，GC 后堆 38–55 MB，没有泄漏。macOS Unix socket 路径上限 104 字节，
   路径太长时用 `HRC_SOCKET_DIR` 指到 `~/Library/Caches/cmp-hot-reload/<hash>/`（0700）。
10. **构建链**。要么本地构建三件套（LLVM+Kaldo、Kotlin dist、Compose klib），要么用预构建工具链；
   后者从 GitHub Release 下载（见 `docs/TRYOUT.md`）。上游提交一变，补丁就要重新对齐；没有 CI。
   预构建包不带 `klib/cache`，首次打包要现建 stdlib 缓存，比本机构建慢约 1 分钟。
11. **画面过期（工具链 .6 已修，默认开）**。.5 及以前，Compose 会跳过参数没变的 composable：既没有参数、也不读 state 的
   `@Composable`，以及参数全是静态值（常量、单例 lambda）的已有调用点，热替换后继续显示旧内容
   （`example-gradle/accept-singletons.txt`）。.6 起每次 reload 成功后，在主线程上把所有组合的所有作用域失效，下一帧整树重组，
   `remember`/`rememberSaveable` 状态保留（TECHNICAL-NOTES §1.6）。仍然要注意：
   - `remember { … }` 里算出来的值不会重算，这是 `remember` 的语义（第 17 条）；要随代码变化的值不要放进 `remember`。
   - 代价：会话期间每个可重启函数都保留重组作用域（`collectParameterInformation`），每次 reload 多一帧整树重组。实测见 REPORT §29。
   - 只在 debug 二进制里生效。在 app 的环境里设 `KONAN_HR_FORCE_RECOMPOSE=0` 可关闭（参考脚本：`KONAN_HR_FORCE_RECOMPOSE=0 bash run.sh`），
     恢复 .5 的行为。插件以后在 DSL 里开放这个开关。
   - 用 .5 及以前的工具链时，限制照旧：给 composable 传一个会变化的参数**并在函数体里读到它**，或者让它读一个 `State`。
   - .6、.7 有一个时间窗口：reload 恰好打断一帧的重组时，这一帧在恢复后会接着跑新代码，早于强制失效；如果这时只重组某个
     改了参数表的 composable（例如它每帧读一个动画值），会调用迁移过来的旧重启 lambda，进程退出。.8 起 reload 在主队列上、
     两帧之间执行，随后立即失效，窗口关闭（`KONAN_HR_RELOAD_ON_MAIN`，INTERFACE §7；REPORT §34）。
12. **libKaldo 位置与打包义务**。两个 dylib 只装在 `<KALDO_INSTALL>/lib/`，编译器按 `konan.properties` 的
    `kaldoLinkerFlags`（`-L<KALDO_INSTALL>/lib`）直接链接；不往 LLVM 安装目录、也不往 `~/.konan/dependencies` 放。
    打包只认 `KALDO_DYLIB` 或 `toolchain.json` 的 `llvm.kaldoIossim`，并校验 sha256。打包时必须删掉可执行文件里的
    绝对 rpath 并对 `.app` 做 ad-hoc 重签，否则模拟器以 `codesign invalid-page` 拒绝启动（INTERFACE §3.3）；
    替换 dylib 后要重新跑一次 package。
13. **下载路径的验证范围**。公开 klib 的 https 下载（Maven Central、Google Maven）已实测；工具链组件从公开仓库 Release
    走 https 下载、不需要 token，这一段在首次公开发布时第一次实测（之前用本地 `file://` 镜像验证了同样的文件和清单）。
    在同一台机器上，用全新的缓存、空的 `GRADLE_USER_HOME`、禁止读 `~/.gradle` 的条件跑通过；还没在第二台机器上从零验证。
    下载需要 PATH 里有 `zstd` 和 `curl`。
14. **并发保存**。runtime 一次处理一个请求；上一次热重载没完成时再保存，行为没验证。
15. **`object` 和文件级 `var` 在所在文件每次热替换后都会重新创建**。补丁带一份新的实例槽和新的全局变量槽，reload 后第一次访问
   会用新构造函数重建 `object`（`init` 里的副作用再执行一次，可变状态归零；实测计数每次 reload 后回到 1），文件级 `var` 也回到初始值
   （画面过期原型里，存在文件级 `var` 里的句柄每次 reload 后都变回 null）。不会读到旧内存，原因见 TECHNICAL-NOTES §1.6。
   需要跨热替换保留的状态，放进 Compose state 或 `remember`，不要放在 `object` 或文件级 `var` 里。沿用旧槽需要同时改编译器和
   Kaldo，代价大，暂不做。
16. **工具链自带的库比工程里用的旧时，热重载按旧版本编译**（0.2.0 起）。工具链 .6 把 collection、lifecycle、navigationevent、
   savedstate、atomicfu 升到了 Compose 实际用的版本（androidx lifecycle 2.11.0 等），向导模板实测只剩一个：
   `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` 工程里是 2.11.0、工具链里是 2.9.6。打包时会打印
   `hot reload compiles against the toolchain's older copies of …` 列出这类库；只用到旧版本里也有的 API 就没问题，用到新 API 的代码在热重载里编译不过。
17. **`remember { }` 里算出的值热替换后不会自己变**。向导模板的问候语在 `remember { Greeting().greet() }` 里，
   改 `greet()` 后补丁已经生效，要让这块重新进入组合（模板里点两下 “Click me!”）才显示新文字。
18. **`Dialog`、`BackHandler` 在 .5 及以前的预构建工具链里会崩（.6 修复）**。工具链的 klib 集合缺 4 个 Compose 依赖：
   `androidx.navigationevent:navigationevent-compose`、`org.jetbrains.androidx.navigationevent:navigationevent-compose`、
   `androidx.lifecycle:lifecycle-viewmodel-compose`、`androidx.graphics:graphics-shapes`（ui、foundation、material3 等 klib 的
   `depends=` 里有，klib 集合里没有）。编译、链接都能过（partial linkage），用到它们的代码到运行时才报 `IrLinkageError`：打开 `Dialog` 时
   `Can not get instance of singleton 'LocalNavigationEventDispatcherOwner'`，进程退出。`Popup` 不受影响。
   .6 补齐这几个 klib，并在打包时检查 klib 集合是否闭合（`scripts/check-klib-deps.py`，缺一个就不打包）。

19. **只改函数体、但改了 `remember` 的类型或个数：旧值会被当成新类型使用（工具链 .8 及以前；.9 起已修，见本条末尾）**。分类器把这种改动判为
   HOT，热替换后 `remember` 的槽位按位置复用，旧值直接交给新代码。例如把 `remember { intArrayOf(0) }` 改成 `remember { longArrayOf(0L) }`，
   新代码会把旧的 `IntArray` 当 `LongArray` 读；在已有的 `remember` 前面插入一个 `remember { "x" }`，这个 `String` 变量拿到的是旧的
   `IntArray`。Kotlin/Native 不检查这种转换，所以不会报 `ClassCastException`：可能显示错值，对象布局不同时可能崩溃。
   **插件 0.2.5 配工具链 .9 起已修**：插件在热重载构建里自动打开稳定 group key（`HotReloadStableKeys`），`remember` 形状一变这个
   composable 就安全重置（插件验收 `accept-kmp.py --expect-stable-keys`）。
   **.8 及以前的做法**：改了 `remember` 的类型、个数或顺序后，重新打包并重启（插件 `./gradlew hotReloadPackage hotReloadRun`，
   `hotreload.sh warm`），不要用热重载。
   只改 `remember` 里的计算、或不碰 `remember` 的函数体改动不受影响。根治（Issue #29）：`remember` 的类型、个数或顺序一变，
   这个 composable 的状态安全重置，随工具链 .9 发布（热重载构建打开 `HotReloadStableKeys`，TECHNICAL-NOTES §1.9；同时改签名时
   `remember` 能保留，REPORT §36）。

20. **热重载构建的 `.app` 依赖这台 Mac 上的库对象文件（工具链 .10 起）**。为了让温重启打包快（Issue #32），缓存的库对象
   （`build/app/merged-bootstrap.libs.o`，约 130 MB）不再嵌进可执行文件，app 启动时由 libKaldo 按绝对路径读取。所以这个 `.app`
   只能在打包它的这台 Mac 的模拟器上运行；删掉 `build/` 后要重新打包。`HR_LIBS_IN_EXECUTABLE=1` 把它嵌回可执行文件（打包慢约 1 s）。
   插件（0.2.6 起）同样默认从文件读，文件在 `build/hotreload/app/merged-bootstrap.libs.o`；`cmpHotReload { libsInExecutable = true }`
   （或 `-Pcmp.hotreload.libsInExecutable=true`）把它嵌回可执行文件；文件不在时 `hotReloadRun` 报错并提示重新打包。

## 已解决（旧文档里可能还会看到）

| 旧说法 | 现状 |
|---|---|
| 多模块不支持，只热替换含 `#main` 的模块 | 双模块各自热替换，`ld -r` 合并后原子发送 |
| 多文件同时改只替换其中一个 | 按模块收集全部重建对象再合并，3/3 同一 tick |
| 编辑到首帧 10.4 s | 单文件 demo 2.6 s；Gradle 双模块 app 3.9 s、ui 4.9 s |
| 启动约 1/3 静默退出 | 收窄热符号后 30/30 |
| 开 EH 合成后冷启动 52 s | 整段注册，首帧约 5.2 s |
| socket 在 `/tmp`，谁都能连 | 项目内 `.hr/`，0700/0600 |
| libKaldo 放在 `~/.konan/dependencies` | 只在 `<KALDO_INSTALL>`；`~/.konan` 已恢复成官方包原样 |
| `KALDO_SYNTH_EH_FRAME=0` 仍会开启合成 | v5 起按值判断 |
| 开兼容性分类器后 ui 慢 0.6 s、app 慢 3.5 s（v7） | v8：dump 和 app klib 走守护进程、快照缓存、与编译并行；交替对照 ui +2 ms、app +15 ms |
| `merge-payloads.py` 失败时留下约 600 MB 的 `kaldo-merge-*` | v8 起任何退出路径都删除，`KALDO_MERGE_KEEP=1` 保留 |
| libKaldo 每次启动都往 `/tmp/gap-selrefs.log` 追加调试输出 | v8（B11）起只在 `KALDO_DEBUG_SELREFS=1` 时写 |
| 环境开关要手工 export、漏设就崩 | `hotreload.env` 一处配置并导出 |
