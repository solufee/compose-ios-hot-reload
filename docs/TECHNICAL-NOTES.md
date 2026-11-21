# 技术说明：Compose Multiplatform 在 iOS 模拟器上的进程内热重载

本文讲两件事：这套热重载是怎么搭起来的，以及路上几个关键问题的现象、根因、修法和验证结果。
怎么构建、怎么跑见 [DEVELOP.md](../DEVELOP.md)；工具的参数和退出码见 [docs/INTERFACE.md](INTERFACE.md)；
现状和限制见 [docs/LIMITATIONS.md](LIMITATIONS.md)。

## 0. 概要

保存 Kotlin 代码后，模拟器里正在运行的 app 不重启、PID 不变，页面和 `remember` 状态都在，UI 直接更新。

做法：Kotlin/Native 的 split 编译把程序分成 host 可执行文件和 guest 代码；app 启动时由 libKaldo
（基于 LLVM ORC / JITLink 的加载库）把 guest 代码 JIT 链接进进程；改代码后只编译改动的文件，把新的对象文件
发给 app，libKaldo 加载它，并把函数入口重定向到新实现。不是 `dlopen`，也不是解释器。

| 指标 | 数值 |
|---|---|
| 单文件示例，保存 → 首帧 p50 / p90 | 2,611 / 2,827 ms（4 类改动 × 10 次，40/40） |
| Gradle 双模块示例，只改 app | 3,929 / 3,964 ms（20/20） |
| 只改 ui 模块 | 4,945 / 5,093 ms（20/20） |
| 两个模块同时改 | 5,096 / 5,098 ms（3/3，两个模块的改动在同一个 tick 生效） |
| 冷启动到首帧 | 约 5.2 s |
| 冷启动成功率 | 30/30 |
| 温重启（不能热替换的改动） | 参考脚本约 16 s，其中约 10.5 s 是重新打包（首发后提速，见 LIMITATIONS 第 2 条；插件路径更新前约 50 s） |

双模块的数字都是在推荐配置下测的：EH 合成打开、热符号集收窄、两个常驻编译进程。

### 环境与基线

| 项 | 值 |
|---|---|
| 主机 | Apple Silicon Mac，macOS 26 |
| Xcode / 模拟器 | Xcode 27.2（27B52），iOS 27.2 模拟器 runtime，iPhone 18 Pro |
| Kotlin | 2.5.255-SNAPSHOT：`JetBrains/kotlin` 公开基线 `8e53ee99fbc08e264828ab082aee3e448194eca8` + 本项目补丁（已包含 PR #7234 / #7418 / #7606） |
| LLVM / libKaldo | LLVM 公开 fork `llvm-project-kotlin`（地址见 [patches/README.md](../patches/README.md)），基线 `2f8844c2b35557baced9106c50b9b8758fa6a0de`（LLVM 21.1.6）+ 本项目补丁 |
| Compose | `JetBrains/compose-multiplatform-core`：工具链 .7 是官方标签 `v1.13.0-rc01` `f39822e6a77eea5d194e4f9bb56cb6961321b849`（skiko 0.156.1）；.6 及以前是 `jb-main` `ee243c321ddff6b18fcf18916be1b96ef70e2363`（runtime 1.13.0-beta01 一系）。补丁改构建接线，外加 `ui` 模块 iOS 源码的一处强制重组（§1.6）；runtime 用 Google 发布的 klib |

只支持 iOS 模拟器（arm64）、macOS arm64 主机、debug / split 构建。

---

## 1. 总体架构

### 1.1 上游基础

热重载的底子来自上游、都还没合并的工作：

- Kotlin PR **#7234**（热重载 launcher）、**#7418**（split 编译，`-Xcompilation-scheme=split`）、
  **#7606**（热重载 runtime，接入 Kaldo）。公开发布的 Kotlin/Native（实测 2.4.21、2.5.0-RC）不认识 split 参数：
  只打一行 warning，照常产出一个**没有**热重载能力的二进制，属于静默失败。
- **libKaldo**：在上面那个 LLVM fork 的 `llvm/tools/kaldo/` 里，上游 PR 是 `Kotlin/llvm-project` #20，尚未合并。
  它提供 `SymbolsOrchestrator`：加载启动时的 guest 代码、`reloadObjects` 加载补丁，外加若干 JITLink plugin 和符号生成器
  （Kotlin 符号外部化、ObjC selector 修复、主机符号索引等）。

上游 runtime 的一次重载大致是：app 里有一个监听 `127.0.0.1` 的线程，一次只处理一个请求；收到请求后拿 GC 锁，
暂停其他线程（stop-the-world），调用 `reloadObjects`（JITLink 加载对象、建 stub、重定向函数入口），
做状态迁移，恢复线程，再调用 Kotlin 侧 `HotReload.addReloadSuccessHandler` 注册的回调。

**为什么不用 `dlopen`**。更早的上游方案（已关闭的 PR #5592）是 `dlopen` 补丁 dylib + `fishhook` 重绑定。
纯 Kotlin/Native 的小程序上它能跑通，但接 Compose 时卡住：开启它的热重载模式后，编译 skiko 的 ObjC bridge 报
`no function <get-bounds>`。试过把 Compose 预编译成 framework、只编用户模块来绕开，但 framework 里的 Kotlin 符号是本地符号
（导出的 `kfun:androidx.compose*` 为 0），补丁里对 Compose 的调用在运行时变成 `IrLinkageError`；让 Compose klib 一起参与补丁链接，
又回到上面那个编译错误。所以改走 split 编译 + JIT 链接。

本项目在这上面补了：三份补丁（Kotlin、LLVM/libKaldo、Compose 构建）、构建和发送脚本、常驻编译进程、
兼容性分类器和温重启。下面几节按数据流走一遍。

### 1.2 构建：split 编译，再把 guest 合成一个对象

```
源码 ──kotlinc-native -Xcompilation-scheme=split──> base 可执行文件
                                                   ├─ host：K/N runtime、stdlib、ObjC 支持、入口（静态链接）
                                                   └─ __TEXT,__kaldo_boot：bootstrap 对象 + 各个库的缓存 archive
     merge-payloads.py ──ld -r──> 一个 guest 对象（relocatable .o）
     embed-merged.py   ──> 用单条 KALD0 manifest 替换 __kaldo_boot ──> 最终可执行文件
     组装 .app：可执行文件 + Frameworks/libKaldo.iossim.dylib
```

- split 编译要求 `-g`、`-Xenable-incremental-compilation` 和 `-Xic-cache-dir`；不开增量编译会被编译器直接拒绝。
  链接侧带 `-lKaldo.iossim -all_load -export_dynamic`。
- **缓存粒度**：依赖库（Compose、skiko 等）一律用整库（monolithic）缓存，只有正在编译的用户模块用 per-file 缓存
  （`makePerFileCache = library.isSubjectOfIC`）。原因是库也按文件建缓存时，58 MB 的 skiko 原生库会被每份缓存复制一遍：
  增量缓存目录从 14 GB 降到 186 MB，嵌入的 manifest 从 15 GB（超过 2 GB 上限）降到 182 MB。
- **为什么要合成一个 guest**：上游设计是逐个加载每个缓存 payload。接上 Compose 后，JIT 无法物化 `ui` 等缓存对象
  （约 19 条 `Failed to materialize symbols`），而两边符号对比并没有缺任何 Kotlin/skiko 符号。改成在构建时用
  `ld -r` 把 bootstrap 对象和全部缓存 archive 链成一个 relocatable 对象、只嵌入这一个 payload 之后，这类错误消失。
  参考 app 的合并对象约 128 MB，十一万多个函数。
- `merge-payloads.py` 合并时还做这些事（多数对应一个踩过的坑，见第 4、9 节）：按名字去重 archive 成员；
  `ld -r ... -multiply_defined suppress`；把被 `ld -r` 降成本地符号、但 runtime 要按名字查找的符号重新 globalize
  （ObjC 适配器表、unique prefix）；保留并改名 compact unwind，供后面合成 `__eh_frame`（第 4 节）；
  丢掉 host 里已经定义过的 ObjC 静态库成员；写出热符号清单（1.5 节）。
- **KALD0 manifest** 格式（来自上游）：头部 `"KALD0"`（5 字节）+ `manifestSize`（i64）+ `bundleSize`（i64）+
  条目数（i32），共 25 字节；每个条目是 `kind`（u8，0 = object，1 = archive）+ `offset`（i64）+ `size`（i64）；
  之后按顺序拼接各 payload 的原始字节。整段放在 `__TEXT,__kaldo_boot`，由符号 `bootStartManifest` 指向。
  本项目只写一个条目，就是合并后的 guest 对象。

### 1.3 启动：libKaldo 加载 guest

app 启动后，libKaldo 的 `loadBootstrap` 大致按这个顺序做：

1. 用 JITLink 链接 guest 对象。链接过程中的几个 pass：合成每个 selector 的 `objc_msgSend` stub（第 2 节）、
   登记 selector 引用、外部化热符号（1.5 节）、从 compact unwind 合成并注册 `__eh_frame`（第 4–6 节）。
2. 绑定 ObjC 适配器表。**必须在下一步之前**，否则会在创建 ObjC 类时崩溃（第 9 节）。
3. 调用 `Konan_constructors` 和 `RuntimeInit()`（即 `ReinitializeGlobalVariablesAndTLS`，重新执行 guest 的全局初始化）。
4. `RuntimeInit()` 之后：读取 ObjC unique prefix（guest 在全局初始化时才写它）；重新登记 selector 引用（第 3 节）。
5. 执行 guest 里 C++ 的模块初始化函数（`__DATA,__mod_init_func`），跳过 `__Konan_constructors` 和 `injectToRuntime`
   （Kotlin runtime 已经初始化过，再跑会报 runtime 被注入两次）。参考 app 里是 11 个。
6. 调用 `Konan_start`，进入 `main()`。参考 app 的 `main()` 先跑一个 EH 探针（第 4 节）。

### 1.4 改代码之后：编译、合并、发送

```
保存
 └─> 常驻编译进程做 cache-only 编译（只重建改动文件的 per-file 缓存）
      └─> 取本次真正重建的 .a（按修改时间过滤）─> ar x 解包 ─> nm 按模块归类
           └─> ld -r 合并成一个 .o ─> 符号检查 ─> 等兼容性分类结论（第 7 节）
                └─> TCP 127.0.0.1:5567 发送对象路径
                     └─> app：停住线程，JITLink 加载，热符号重定向到新实现，恢复线程
                          └─> 下一次重组执行新代码
```

**cache-only 编译**。一次热重载编译原本要走完整的 `-p program`：生成 host bitcode（1.56 s）、host 目标文件（1.12 s）、
链接出一个 252 MB 的可执行文件（0.48 s），然后整个丢掉，真正要发送的只是增量缓存里约 200 KB 的用户对象。
补丁在 `KonanDriver.run()` 里加了一个分支：设置 `KONAN_HOT_RELOAD_CACHE_ONLY=1` 时，主编译（`libraryToCache == null`，
不影响内部为建缓存拉起的子编译）在缓存构建完之后直接返回。编译段 p50 从 8.87 s 降到 4.91 s。不设这个变量的构建走原路径。

**稳定的中间 klib 名**。split 走一阶段编译，源码先编成一个临时 klib，原来放在临时目录下的 `<UUID>.klib`。
缓存按这个名字命名，每次都是新库，所以增量编译名义上开着，实际 0 次命中（同一份源码连编 3 次，每次都新建缓存）。
改成按 `sha1(target | output | sources)` 的前 16 位命名之后，内容不变的第二次编译从 5.00 s 降到 2.77 s，缓存命中；
内容是否变化仍由缓存元数据里的 klib 指纹判断。

**常驻编译进程**。`HrCompileDaemon` 常驻一个 JVM，加载编译器 jar，在 Unix socket 上收请求（一行一个编译参数，
空行结束），进程内调用编译器入口，回 `OK <ms>` 或 `ERR <code> <ms>`；编译出错不会杀死进程。
`-Xdump-built-caches-to` 让脚本直接拿到本次产出的缓存 archive 列表。实际跑两个进程：`hr-compile.sock`
（cache-only，编辑路径用）和 `hr-klib.sock`（普通 library 编译，给 ui 模块和分类器用）。cache-only 是进程级开关，所以要分开。
socket 放在工程内 `.hr/` 目录，目录 0700、socket 0600，因为守护进程会执行收到的任意编译参数。
用 SerialGC 连续编译 200 次，RSS 从约 1.08 GB 缓慢升到约 1.32 GB 后趋平，GC 后堆稳定在 38–55 MB，没有泄漏。

**只发真正重建的对象**。`-Xdump-built-caches-to` 可能列出这次没有重建的旧 archive，脚本按修改时间过滤，
否则会把旧代码当补丁发回去。

**一次只发一个对象**。协议和 `reloadObjects` 本身支持一次发多个对象，并把它们装进同一个 JITDylib、失败时整体回滚。
但实测把 app 和 ui 两个模块的对象放在同一个请求里，整个请求**静默失败**：两边都没更新，也没有错误输出；单独发都正常。
所以发送前一律用 `ld -r` 把本次所有改动的对象合成一个 `.o`（约 50 ms）。这样多模块、同一模块多文件的改动
都是一次原子替换，在同一个 tick 生效。

**符号检查**。发送前，对每个被改的模块检查：它的原始对象里定义的符号数大于 0，并且全部保留在合并对象里；
不满足就不发送。

**发送协议**。TCP 连接 `127.0.0.1:<端口>`（`KONAN_HOT_RELOAD_PORT`，默认 5567），发送 `u32 对象数`，
随后每个对象是 `u32 路径长度 + 路径字节`。发的是对象文件的路径，不是文件内容。

**对象文件名**。libKaldo 的外部化 plugin 和 `reloadObjects` 的 archive 过滤会跳过名字里带 `-cache.a` 的对象，
而从缓存里解出来的对象叫 `lib<uuid>-cache.a.o`。不改名的话补丁不会被外部化，新实现查找全部失败。
所以发送前把它复制成 `patch.o`。

### 1.5 符号外部化与重定向

libKaldo 的 `KotlinSymbolExternalizer` plugin 对每个“热”函数做两件事：把函数体改名为 `<原名>$knhr`，
再留一个同名 stub。调用方都经过 stub；热重载时把 stub 重定向到补丁里新的 `$knhr` 实现。

**热符号集要收窄**。最初所有 Kotlin 符号都外部化（约 11.6 万个）。冷启动 30 次里有 6 次在
`Konan_constructors` 之前 SIGSEGV，崩溃栈在 ORC 的 `ExecutionSession::simplifyDepGroups`。改成只外部化用户模块的符号：
`merge-payloads.py` 从定义 `#main` 的 archive 和 `KALDO_HOT_PACKAGES` 列出的用户包收集符号，写成 `hot-symbols.txt`；
启动 app 时通过 `KALDO_HOT_SYMBOLS` 传进去。启动时外部化的符号从 115,982 个降到 157 个，冷启动 30/30 成功、0 次重试。
这在当时是规避；代价是只有热符号集里的模块能热替换，Compose 等依赖库的代码不能。

**根因（LLVM 补丁，随工具链 .6 发布）**：`ObjCSelectorFixupPlugin` 合成的 `_objc_msgSend$…` stub 等定义是 `Scope::Default`，
却没有登记到 MaterializationResponsibility。本地对 `LinkGraphLinkingLayer.cpp` 的改动只把这类“额外定义”从 `notifyResolved`
的结果里删掉，`registerDependencies` 仍把它们放进 SymbolDependenceGroup；`ExecutionSession::simplifyDepGroups` 对每个组里的符号
做 `MR.getSymbols().find(sym)`，找不到时仍取 `->second`（release 构建没有 assert），是越界读，是否崩取决于越界处有没有映射。
临时加计数的构建显示：每次 bootstrap 有 658 个这样的定义，收窄与否都一样。修法：`registerDependencies` 只放 MR 拥有的符号
（没有定义的块按匿名块处理，依赖照常传给使用它的块）；`simplifyDepGroups` 跳过 MR 不拥有的符号，不再解引用 `end()`。
修复后不收窄（116,025 个符号全部外部化）、不重试，冷启动 30/30；收窄仍是默认（启动 p50 5.3 s 对 6.5 s）。

外部化 plugin 原先对每个符号都扫一遍整张图的边（符号数 × 边数），十一万个函数时跑 10 分钟以上也没结束。
改成用一个 `DenseMap<Symbol*, Symbol*>` 先算出每个目标、再遍历一次边之后，几秒内完成。

**reload JITDylib 的链接顺序与符号生成器**（2026-11-20，Issue #5）。每次 reload 新建一个 JITDylib `reload<N>`，链接顺序是
它自己 → 之前所有 `reload<k>`（从新到旧）→ StubsJD → MainJD；它自己挂两个定义生成器（`MachOHostDataSymbolGenerator`、
`DynamicLibrarySearchGenerator`），用来在链接时解析宿主进程里的数据和函数。ORC 查找时对链接顺序里的每个 JITDylib，
先查已有定义，查不到就运行它的生成器。补丁的外部引用大多定义在 MainJD（JIT 出来的主程序和 Compose 代码），宿主和 dlsym
都找不到，所以以前每次 reload 都要把之前每一层的两个生成器各跑一遍：链接耗时每多一次 reload 加约 10 ms（55 次后 553 ms）。
这两个生成器只在本层自己那次链接时有用，下一次 reload 开始时把上一层的摘掉（`Kaldo.cpp` `reloadObjects`；归档输入的
`StaticLibraryDefinitionGenerator` 保留），链接耗时稳定在约 15 ms。`KALDO_RELOAD_TIMING=1` 打印分阶段耗时（INTERFACE §7）。

### 1.6 Compose 怎么看到新代码

上游 runtime 只提供“重载成功”的回调，没有 Compose 重组桥。Compose runtime 里按 group key 失效的接口
（`invalidateGroupsWithKey`、`simulateHotReload`）是 `@TestOnly` 的，前者要知道哪些 key 变了，后者会清空内容。
本项目没有用它们。

参考 app 的做法是：用 `LaunchedEffect` 让一个 `tick` 状态每秒加一，并把它作为参数传给页面级 composable，
于是界面每秒重组一次。函数入口被重定向之后，下一次重组执行的就是新代码。组合树、`remember` 状态都在同一个进程里，
没有被拆掉重建，所以页面和计数器保留。

工具链 .5 及以前有一个限制：如果一个被替换的 `@Composable` 既没有参数、也不读任何 state，Compose 会跳过它，旧画面不变，
看起来像热重载没生效。参数全是静态值（常量字符串、Compose 生成的单例 lambda）的**已有**调用点也一样会被跳过：改已有 lambda
的正文、或删掉几个并列 lambda 中的一个之后，屏幕可能保留旧内容（`example-gradle/accept-singletons.txt`：最终源码是 A2、C、B2，
屏幕是 A、B、B2）。

**工具链 .6 起：reload 成功后强制完整重组**（`compose/ui/ui/src/iosMain/.../scene/HotReloadRecompose.ios.kt`，在 Compose 补丁里）。
Compose runtime 用的是 Google 发布的 `androidx.compose.runtime:runtime` klib（公开 klib，我们不重编），所以改动只放在我们从源码
构建的 `ui` 模块里，只用 runtime 的公开 API：

1. 每个场景组合（根组合和 Dialog/Popup 层，都经过 `ComposeSceneMediator.setContent`）第一次组合时调用
   `Composer.collectParameterInformation()`。之后这个 composer 给**每个**可重启的 group 保留重组作用域，而不只是读过 state 的那些；
   在它下面创建的组合（层、LazyColumn 的条目、SubcomposeLayout）通过 `CompositionContext` 继承这一设置。
2. runtime 在 reload 成功后调用 `HotReload.addReloadSuccessHandler` 注册的回调。回调先记下「有待处理的强制失效」，然后：
   - .8 起 Kotlin/Native runtime 把整个 reload 放到主队列上执行（`HotReload.cpp` 的 `ReloadFromServer`，`dispatch_async_f` 到主队列，
     监听线程等它做完），所以 reload 落在两次主 run loop 之间，不会打断一帧；回调也在主线程上，当场失效（`via=reload`）。
     原因（Issue #27，REPORT §34）：reload 让 Kotlin 线程停在最近的安全点，可能在一帧的重组中途；恢复后这一帧接着跑新代码，
     早于回调，可能调用从旧代码迁移过来、捕获的参数和新函数对不上的重启 lambda（签名变了时，REPORT §33），帧入口拦不住已经开始的帧。
     reload 本身不变：仍然先让其他所有 Kotlin 线程（`Dispatchers.Default`/IO 上的协程等）停住，换代码、迁移实例后再恢复（stop-the-world）。
     主队列在 `KONAN_HR_MAIN_WAIT_MS`（默认 2 s）内没接手时补丁不应用，打印 `HR reload: NOT applied: …`，下一次热重载再发。
   - 回调不在主线程（`KONAN_HR_RELOAD_ON_MAIN=0` 时在监听线程上 reload），或者有组合正忙时，
     主线程上谁先到谁做：iOS 每个窗口的重组帧入口（`FrameChoreographer.performRecomposerFrameIfNeeded`，`via=frame`）或回调派过去的
     任务（`via=task`）。
   主线程也是跑 Recomposer 的线程，所以几条路都不会落在一次 composition 或 apply 的中间；在主线程上通过
   `RecomposerInfo.observe()` 拿到每个运行中的 Recomposer 的全部组合，任何一个正在组合或有未应用的改动就重新排到主线程、打一行
   `deferred` 日志；否则对每个组合调 `ControlledComposition.invalidateAll()`。下一帧里每个可重启函数都用调用方**现在**传的参数重新执行，
   静态参数也不例外。slot table 不拆，`remember`、`rememberSaveable` 的状态都保留。

只调 `invalidateAll()` 不够（11-20 的原型）：不读 state 的 group 默认不保留作用域，`invalidateAll()` 碰不到它们，父函数重跑时它们照样被跳过；
所以第 1 步必不可少。runtime 里另一条路 `composer.forceRecomposeScopes()`（Live Edit 用的 `forciblyRecompose`）是 internal 的，
要改 runtime 才能用，没有走。

每次 reload 打一行 `HR forceRecompose recomposers=… compositions=… invalidated=… recomposed=… deferred=… via=reload|frame|task dispatchMs=… forceMs=… frameMs=… totalMs=…`，
`via` 是谁做的失效（主队列上的回调、帧入口或派过去的任务），`invalidated` 是被失效的作用域数，`recomposed` 是强制那一帧里实际进入的作用域数，`totalMs` 是从 reload 成功到所有 Recomposer 重组并应用完。
只在 debug 二进制里生效（`Platform.isDebugBinary`；热重载构建都带 `-g`）；app 环境里 `KONAN_HR_FORCE_RECOMPOSE=0` 关闭两步
（INTERFACE §7）。`remember { … }` 里算出来的值不会重算（这是 `remember` 的语义），要随代码变化的值不要放进 `remember`。
回归：`example-gradle/accept-stale.py`（首页、滚动过的 LazyColumn、Popup、翻页、600 行的大列表，以及开关两种模式的耗时）、
`example-gradle/accept-singletons.sh`（开着时检查屏幕上的 lambda 与源码一致）、`example-gradle/accept-default-param.sh`
（参数表末尾加带默认值的参数后强行热替换；每帧只重组被改组件时来回改签名 10 次，每次 reload 都要在主队列上完成并当场失效；
反例：`KONAN_HR_RELOAD_ON_MAIN=0` 以及再关掉帧入口，都应退出。两个只给验收用的开关：`KONAN_HR_TEST_TASK_DELAY_MS`、
`KONAN_HR_TEST_NO_FRAME_ENTRY`，后者同时关掉回调里的当场失效）。

**对象与单例在热替换时怎么处理**（2026-11-19 核实，代码加实测）：

- 每次 reload，Kaldo 把补丁装进一个新的 JITDylib（`reload<N>`，`llvm/tools/kaldo/Kaldo.cpp` 的 `reloadObjects`）。
  补丁里的定义（函数、`TypeInfo`、`object` 的实例槽）都是新的一份；函数经 stub 重定向，所有调用方都走新代码。
- runtime 停住所有线程后执行 `ReloadClassesAndInstances`（Kotlin 补丁里的 `runtime/src/hot_reload/impl/cpp/HotReload.cpp`、
  `StateTransfer.cpp`）：对补丁里的每个类取旧、新 `TypeInfo`（Kaldo `collectTypeInfoPatchesFromLatestUnit`），从全局变量、TLS、
  shadow stack 遍历堆找出旧实例，按新 `TypeInfo` 分配新对象（新的实例大小），同名同类型的字段复制过去，新字段为零（null），
  再把堆、全局、TLS、栈里的引用全部改到新对象。所以不会出现“用新布局读旧对象”。
- `object`（包括 Compose 生成的 `ComposableSingletons$<File>Kt`）：实例槽 `_kvar:<Obj>.$instance#internal` 是文件私有的
  （non-external、`__bss`），补丁带一份新的、值为零的槽和新的 `<get-$instance>#static`，所以 reload 之后第一次访问会用**新构造函数**
  重新建实例：`init` 再执行一次，可变状态归零（LIMITATIONS 第 15 条）。对 `ComposableSingletons` 来说，这正好让新增的 lambda 字段由
  新代码初始化，所以在已有文件里增删 composable lambda 是安全的；这个字段只在 IR 里可见，分类器判 HOT 是对的
  （回归 `example-gradle/accept-singletons.sh`：增、删、再加 lambda 和 5 轮增删，13/13 都是同一进程，没有崩溃）。
- 类字段布局等结构性改动仍由兼容性分类器拦下，走温重启（第 7、8 节）：迁移只按字段名复制，构造逻辑和不变量不会重跑。

### 1.7 编译耗时是怎么降下来的

| 改动 | 保存 → 首帧 p50（单文件示例，4 类改动 × 10 次） |
|---|---|
| 最初（每次完整 `-p program` 编译） | 10,443 ms |
| + cache-only（跳过 host 生成与链接） | 7,243 ms |
| + 稳定 klib 名 + 用户模块 per-file 缓存 | 6,982 ms |
| + 常驻编译进程 | **2,611 ms** |

中间两行用的是旧版测量脚本，每次多算约 0.8 s 读日志的开销；最后一行已修正。最后一行的构成：编译 1.29–1.39 s，
解包 + 发送 0.10–0.15 s，发送到首帧 0.93–1.00 s。常驻进程刚起来后的第一次编译要加载编译器类，约 5 s。

per-file 缓存对单文件模块没有收益（模块里只有一个文件，改一行还是整模块重编）。在一个两文件模块上，
只改一个文件比两个都改快约 0.45 s，只占约 8%；剩下的时间是前端分析、IR linking 和缓存收尾，跟脏文件数无关。
真正的大头在别处：每次走 `kotlinc-native` 启动器有约 2.5 s 的固定成本（JVM 启动、类加载）；而在同一个 JVM 里，
内容不变的再次编译只要约 0.73 s，说明依赖 klib 的元数据在进程内能缓存住。常驻进程省下的就是这两块。

### 1.8 ObjC 桥接：split 编译下的 WritableTypeInfo（工具链 .5 修复）

**现象（.4 及以前）**：Kotlin 的装箱值（`Boolean`、`Int` 等）作为 `id` 交给 ObjC 时，变成普通的 Kotlin 包装对象，而不是 `NSNumber`。
`bundle.objectForInfoDictionaryKey("CADisableMinimumFrameDurationOnPhone") as? NSNumber` 得到 null，Compose 默认打开的严格 Info.plist 检查
因此在启动时抛 `IllegalStateException`。同样的代码编成普通（非 split）可执行文件是正常的。

**原因**：ObjC 桥接靠每个类的 WritableTypeInfo（`convertToRetained`、`typeAdapter` 等字段）。普通构建里，程序模块的 ObjCExport
代码生成器生成 `BooleanToNSNumber` 这类转换函数，链接时用带初值的定义覆盖 stdlib 缓存里那份 common 定义（`_ktypew:kotlin.Boolean`）。
split 编译下，那份 WritableTypeInfo 在 host 映像里，程序模块进 JIT 加载的 bootstrap，链接期的覆盖做不了，编译器就直接跳过
（`WritableTypeInfo.kt` 里的 `if (isUsingSplitCompilationScheme) return`，来自上游 hot reload 提交 `064fc19ab02`）。
结果是转换函数生成了却没有绑定，host 里的 `_ktypew:*` 全是零。

**修法**（Kotlin 补丁，只改 split 分支）：引用外部的 `_ktypew:<类>`，由程序模块的静态初始化器在启动时写入非空字段
（`convertToRetained`、`typeAdapter`）。它在 `Konan_constructors` 里运行，早于任何 Kotlin 代码；空字段不写，所以加载器先前绑定的
type adapter 不受影响。普通构建不走这条分支。

**验证**：
- 同一个 app，打开 Compose 默认的严格检查：用 .4 编译器，启动时 Uncaught `IllegalStateException`，探针显示 `isNSNumber=false`；
  用 .5 编译器，正常启动，`Boolean`/`Int`/plist 值都是 `isNSNumber=true`，`String` 是 `isNSString=true`。
- release 不变：同一个 dist 只换编译器 jar，release 产物的 `__text` 逐字节相同，符号名和各段大小相同，整个文件差 48 字节；
  同一个编译器编两次也差 47–48 字节（`LC_UUID` 等）。

---

### 1.9 改签名时保住 `remember`：与签名无关的 group key（工具链 .9 起，Issue #29）

Compose 编译器给每个 composable 的 restart group 一个 key，按“文件/类路径 + 函数签名”算（`DurableFunctionKeyTransformer`），
函数体里内层 group 的 key 再加上源码偏移（从函数声明开头算）。参数表一变 key 就变，运行时把它当成新组，`remember` 全部重置。

热重载构建打开 Compose 的 feature flag `HotReloadStableKeys`（`hotreload.sh` 自动传，INTERFACE §7 `HR_STABLE_KEYS`）后：
- key = 文件/类路径 + 函数名 + **remember 形状**，不含参数表；同一作用域里同名同形状的重载按出现顺序加序号区分；
- 内层 group 的偏移改从函数体开头算，参数表变长不再挪动它们；
- **remember 形状**：函数体里按位置占槽的调用（`remember*`、`Composer.cache`、带类型参数的 `@Composable` 调用），按源码顺序记下
  被调用方、类型参数和参数个数。形状一变（`remember` 的类型、个数、顺序变了）key 就变，整组安全重置。没有这一条时旧槽位会被当成
  新类型直接使用——Kotlin/Native 不检查这种转换（LIMITATIONS 第 19 条，.8 及以前只改函数体时也会发生）；
- 嵌套 lambda 和局部函数有各自的 key，各自算形状。

于是：加参数（带或不带默认值）、改参数类型、改签名后再改函数体，`remember` 都保留；参数比较（`$changed`）不挤占 `remember` 的槽位。
函数挪到别的文件或类时 key 变，状态重置。release 构建不传这个 flag，key 计算和原来逐字节相同（release 摘要不变）。
改动在 Kotlin 补丁里的 `plugins/compose/compiler-hosted`（`ComposePlugin.kt` 的 `FeatureFlag`、`DurableFunctionKeyTransformer.kt`、
`ComposableFunctionBodyTransformer.kt`、`AbstractComposeLowering.kt`）。验收：`example-gradle/accept-remember-signature.sh`。

## 2. 合成的 `objc_msgSend` stub 共用了同一块缓冲区

**背景**。Compose 的缓存对象引用了大量 `_objc_msgSend$<selector>` 形式的符号（合并对象里有 142 个以上未定义）。
正常构建时，这些 stub 由 Apple 的链接器在最终链接时合成到 `__TEXT,__objc_stubs`。JIT 路径绕过了这个链接器，
JITLink 也没有对应的合成步骤，于是在 fixup 阶段报 `Symbols not found`。原有的 selector 修复 pass 在 fixup **之后**才跑，
而且只改写 `__objc_selrefs`，赶不上。所以 libKaldo 在 fixup 之前加了一个 pass：为每个被引用的 selector 合成一个 stub，
定义原来那个 `_objc_msgSend$<selector>` 符号，并把已有的引用指过去。

stub 的最终形态是 7 条指令、28 字节：先用 4 条 `movz/movk` 把**已登记的 SEL 直接编码成立即数**，再 `adrp/ldr`
取 `objc_msgSend` 的地址，最后 `br x9`。最早的版本从一个 selref 数据槽里读 SEL，碰到过两个问题：JIT 写进数据槽的 SEL
会被 guest 的全局初始化覆盖（第 3 节）；主机符号索引把 host 镜像里的 `_objc_msgSend$*`（那是 `__objc_stubs` 里的代码）
当成绝对数据符号暴露出来，会遮住 JIT 合成的 stub。当时的表现是执行到某个 stub 的第 5 个字时，那里不是跳转指令而是数据
（`EXC_BAD_INSTRUCTION`）。改成立即数编码、并让主机符号索引不再收录 `_objc_msgSend$*` / `_objc_msgSendSuper$*` 之后，
这个崩溃消失。

**现象**。app 走到第一帧 Metal 渲染时崩溃：`-[MTLSimTexture setLodMaxClamp:]: unrecognized selector`。
符号化后，发出这条消息的是 skia 的 `GrMtlTextureInfoSampleCount`，它本该发 `sampleCount`，重定位目标也确实是
`_objc_msgSend$sampleCount`。也就是说某个 stub 发出的 selector 和它名字里的 selector 不一致。启动时的自检却一直报 `STUB-SEL check: 657 stubs, 0 mismatch`。

**根因**。`llvm::jitlink::LinkGraph::createContentBlock` **不复制**传入的 `ArrayRef<char>`，块里只保存
`Content.data()` 这个指针。合成 stub 的循环把指令写进一个栈上数组（`uint32_t Code[7]`）再建块，于是循环结束后，
所有 stub 块都指向同一块栈内存，内容是**最后一个**写进去的 selector。自检比较的是缓冲区被复用之前的值，所以永远报 0 mismatch。
运行时读出来的证据：`_objc_msgSend$sampleCount` 和 `_objc_msgSend$setLodMaxClamp:` 编码的是同一个 SEL
（`setLodMaxClamp:` 的值），而合成时的日志里两者的 SEL 明明不同。

**修法**。stub 的字节从图自己的分配器里拿：`G.allocateBuffer(sizeof(Code))` 再 `memcpy`，生命周期和块一样长。

**验证**。修完后启动日志 `SELREF check: 671 slots, 0 unmapped`、`registered 670 embedded selector constants`、
跑了 11 个 C++ 模块初始化函数，没有崩溃；Compose 第一帧上屏（`BUILD v1`，计数器在走）。随后完成了第一次真实热重载：
`BUILD_TAG` 从 v1 改成 v2，界面显示 `BUILD v2`，计数器继续累加没有归零，PID 不变。

---

## 3. 全局变量重新初始化后，要重新登记 selector 引用

**背景**。ObjC 代码里的 selector 引用（`__objc_selrefs` 里的槽）在编译产物里存的是指向 `__objc_methname` 字符串的指针。
正常加载镜像时，dyld 会把每个槽改写成运行时登记过的唯一 `SEL`。JIT 进来的代码没有 dyld，所以 libKaldo 的
`ObjCSelectorFixupPlugin` 在 JITLink 的 PostFixup 阶段对每个 selref 块调 `sel_registerName` 并写回。

**现象**。app 启动后在 `_UIApplicationMainPreparations` 里崩溃：`-[CHRAppDelegate0 init]: unrecognized selector`。
可是这个类明明实现了 `init`：`class_respondsToSelector` 为真，方法表里有 `init`，实现地址在 JIT 代码里；
发送前刷新方法缓存也没用。在 `_CF_forwarding_prep_0` 上断下来看，`x1` 里是一个指向 `"init"` 的普通 C 字符串指针，
而 `sel_registerName("init")` 返回的是 ObjC selector 区里的另一个地址。也就是说，发送时传的是 `char*` 而不是 `SEL`，
方法查找永远对不上。反汇编发出消息的 thunk，SEL 是从一个 JIT 数据槽里读的，槽里还是原始的 methname 指针。
在 PostFixup 阶段，这个槽不在 plugin 处理过的 843 个 selref 块里，扫描所有数据块也找不到指向 `init` 字符串的值。

**根因**（两个）：

1. `RuntimeInit()`（`ReinitializeGlobalVariablesAndTLS`）会重新执行 guest 的全局初始化，把嵌入 payload 里
   `__objc_selrefs` 的原始内容（methname 指针）恢复回去。PostFixup 写进去的 SEL 在 app 真正运行前就被全部覆盖了。
2. `__DATA,__objc_selrefs` 段的实际范围比 JITLink 报告的块大：0x2780 字节 = 1,264 个槽，而块只有 843 个，
   有 420 多个槽在 fixup 时根本没有被任何块覆盖。

**修法**（libKaldo 的 selector plugin + `loadBootstrap`）：

1. PostFixup 时记下每个改写过的槽 `{地址, 原始字符串指针, 登记后的 SEL}`，并记下每个 `__objc_selrefs` 段的地址范围
   （最小块地址到最大块末尾）。
2. 在 `RuntimeInit()` 之后再走一遍：记下的槽如果又变回原始字符串，就写回 SEL；记下的段范围内逐个 8 字节槽检查，
   内容是可打印的 selector 字符串（`[A-Za-z0-9_:]`，长度小于 128）就 `sel_registerName` 并写回。
   这一步做的就是 dyld 加载镜像时做的事，只是放在 guest 全局变量重新初始化之后；扫描不出 `__objc_selrefs` 的范围。
3. 后来又修了一处判断：最初用 `sel_isMapped()` 跳过“已经登记过”的槽，但常量 selector 的 SEL 恰好就是原始 methname
   指针，会被误判成已登记。改成只看“可打印 + `sel_getName` 往返一致”之后，参考 app 补登记的数量从 1,263 增加到 2,070。
4. 嵌在其他数据段里的 selector 常量（不只 `__objc_selrefs`）也一并登记；每次启动打印 `SELREF check: N slots, M unmapped`。

**验证**。修复后的第一次启动打印 `re-registered 1263 selector references after runtime init`（842 个记下的槽 +
421 个空隙槽），空隙里登记的都是真实 selector（`hash`、`description`、`initWithBool:` 等）；app 越过了 `init` 崩溃，
app delegate 正常执行到 `didFinishLaunching`。作为回归，最小 ObjC 复现程序的 split 构建也正常启动（启动时补登记 63 个）。
后来的构建里，启动自检为 `SELREF check: 671 slots, 0 unmapped`。调试时可以设 `KALDO_DEBUG_SELREFS=1`，
让 libKaldo 把补登记的 selector 写到调试日志（默认不写）。

---

## 4. 从 compact unwind 合成 `__eh_frame`

### 4.1 现象

真实点击 Back（第 2 页被移除，Compose 回收这些节点）时进程退出：`ModifierNodeDetachedCancellationException`
从组合里抛出，没有被接住。不做任何热重载也能复现，所以是 split/JIT 构建本身的问题。热重载时，凡是改变 slot table 形状的
改动（增删 composable、会增减节点的 `if` 分支）也会在新画面出来后崩溃；改文案、改颜色则正常。

栈是 `ThrowException` ← `Result.getOrThrow` ← `BaseContinuationImpl.resumeWith` ← 一个 JIT 帧。异常本该穿过这个 JIT 帧，
被协程机制的 `catch` 接住，实际却走到了 Kotlin 的 terminate handler，然后 `abort`。

### 4.2 根因：JIT 代码只有 compact unwind

合并后的 guest 对象里，十一万多个函数的 `__eh_frame` 一共只有 0x810 字节（约 2 KB）。Kotlin/Native 在 Darwin 上
生成的是 compact unwind；`ld -r` 会保留它，DWARF FDE 只为少数需要它的函数生成。

JIT 代码用不了 compact unwind：libunwind 通过 Mach-O 镜像头找到 `__unwind_info`，而 JIT 出来的内存没有镜像头。
对 JIT 代码可用的机制只有一个：把真正的 `__eh_frame` 注册给 libunwind。可是几乎没有 FDE 可注册。
（另外，对象里带着 `__LD,__compact_unwind` 时 JITLink 会报 “needs DWARF, but no FDE was found”，所以最初合并时把它删掉了。）

**先试过让编译器直接生成 `__eh_frame`，走不通**：

1. 只在 split 构建里给每个函数加 `uwtable=sync`。用 `LLVMAddTargetDependentFunctionAttr` 加的是字符串属性，后端不认；
   改成枚举属性后属性加上了，目标文件里仍然没有 `__eh_frame`。
2. 原因是 Darwin 上的 AArch64 后端只有在 `MCOptions.EmitDwarfUnwind = Always` 时才写 DWARF unwind 信息，
   `uwtable` 本身还是选 compact unwind。LLVM 自己的 JIT 路径会设这个选项（源码注释说 libunwind 不能动态加载 compact unwind，
   所以 JIT 必须生成 DWARF unwind），但它没有暴露在 Kotlin/Native 用的 LLVM C API 里（`LLVMCreateTargetMachine` 没有这个参数）。
3. 自建一份 LLVM 21.1.6（在 `AsmPrinter` 里加了针对热重载模块的改动），连 clang 自带的 `-femit-dwarf-unwind=always`
   开关编出的 `arm64-apple-ios15.0-simulator` 目标文件里也只有 `__compact_unwind`、没有 `__eh_frame`。
   要让 Darwin arm64 同时输出 FDE，得改后端，不是翻一个开关的事。

### 4.3 修法：JIT 时由 compact unwind 记录合成 FDE

**构建侧**（`KALDO_KEEP_COMPACT_UNWIND=1`）：`merge-payloads.py` 保留 `__LD,__compact_unwind`，但把它改名为
`__KALDO,__kaldo_cu`（直接改 Mach-O section 头，因为 `llvm-objcopy --rename-section` 的参数里写不了带逗号的 segment,section 名），
这样 JITLink 自己的 compact unwind 处理不会去要求 FDE。

**运行侧**（`KALDO_SYNTH_EH_FRAME`，未设、空串、`0` 为关，其他值为开）：libKaldo 的 plugin 遍历 compact unwind 记录
（每条 32 字节），只为图里**定义**的函数生成 FDE（外部函数沿用 host 自己的 unwind 信息）。Kotlin/Native 编译时用
`frame-pointer=non-leaf`，两种常见模式的映射如下：

| compact unwind 模式 | 合成的 CFI |
|---|---|
| `UNWIND_ARM64_MODE_FRAME` | CFA = fp + 16；fp、lr 在 CFA−16、CFA−8；x19–x28、d8–d15 成对保存，按 compact unwind 的顺序排；d8–d15 的 DWARF 寄存器号是 72–79 |
| `UNWIND_ARM64_MODE_FRAMELESS` | CFA = sp + stack_size；返回地址在 lr |
| `UNWIND_ARM64_MODE_DWARF` / 未知编码 | 计数并打日志，跳过（这类函数本来就有 FDE，在原有的约 2 KB `__eh_frame` 里；参考 app 有 28 条） |

**CIE 有三种**，按记录有没有 personality / LSDA 选：

| CIE | 用于 | 增强数据 | 大小 |
|---|---|---|---|
| `zR` | 既无 personality 也无 LSDA | 增强长度 1：`R` = 0x1B | 17 字节 |
| `zPR` | 只有 personality | `P` + personality 指针、`R` | — |
| `zPLR` | personality + LSDA | 增强长度 11：`P` = 0x00（absptr）+ 8 字节 personality 指针、`L` = 0x1B、`R` = 0x1B | 29 字节 |

公共字段：version 1，code alignment 1，data alignment −8，返回地址寄存器 x30。用 `zR` 的 FDE 增强长度为 0，
用 `zPLR` 的为 4（放 LSDA 指针）。参考 app 里 122,781 条 FDE 用 `zR`、3,800 条用 `zPLR`。

必须按记录分开选的原因：CIE 一旦声明了 `L`，它名下**每一个** FDE 都必须带 LSDA 字段。CIE 声明的和 FDE 实际带的对不上时，
libunwind 在展开时报 “unknown pointer encoding” 并中止；按记录实际有没有 personality / LSDA 选这三种 CIE 之后，这个中止消失。

**指针编码**：

- **FDE 的 `pc_begin`**：`pcrel|sdata4`（0x1B）。目标取合成时捕获的函数 `Symbol*`，而不是按名字查：
  外部化之后，同名符号是 stub，函数体已经改名成 `$knhr`，按名字查会描述到 stub 上。
- **personality**：`absptr`，8 字节，在 PostFixup 时写入。`__gxx_personality_v0` 在 libc++abi 里，离 JIT 内存超过 2 GB
  （地址分别在 `0x25a…` 和 `0x11…` 一带），`pcrel|sdata4` 编不下；它也不在 JIT 的链接图里，按名字在图里找会失败，
  所以 libKaldo 用自己对 `__gxx_personality_v0` 的直接引用作为回退来取地址。修之前这个字段一直没填上（值为 0），
  按 pcrel 解码出来就是字段自己的地址；进程于是跳进了我们自己合成的 CIE（`__kaldo_cie_1 + 0x13`，正好是 personality 字段所在的偏移），
  执行的是数据。
- **LSDA**：`pcrel|sdata4`，基准是字段自己的地址；目标是 guest 的 `__gcc_except_tab`。细节见第 5 节。
- **`CIE_pointer`**：DWARF 规定它是从该字段**往回**到 CIE 起点的正偏移，即 `(CIE_pointer 字段地址) − (CIE 地址)`。
  符号写反时 `__register_frame` 会读到一个野 CIE，进程直接崩溃。

**每个 FDE 内联一份 CIE**。最初所有 FDE 共用两个 CIE 块，PostFixup 时按块地址回填 `CIE_pointer`。结果回填出来的距离是
0x110A4（约 70 KB），而按设计第一个 FDE 应该在它的 CIE 后面约 46 字节，说明 PostFixup 时块的实际地址和假设的不一样
（具体原因没有再查，改了布局绕开）。改成每个块都是 `[CIE][FDE]`
之后，`CIE_pointer` 固定等于 FDE 在块内的偏移加 4，跟块怎么排无关。代价是十二万多份 17 或 29 字节的 CIE，
合计约 4.7 MB，只在 debug 热重载构建里有。

**常驻自检**。打开合成时，每次启动打印
`EH check: N FDE, L lsda, M lsda-out-of-range, P pc-out-of-range, U unresolved-personality`；并对每个 LSDA 做回读校验：
`字段地址 + 符号扩展(sdata4)` 必须等于目标 LSDA，且目标的第一个字节必须是 0xFF（LPStart 省略）。

### 4.4 验证

- 启动日志：`EH check: 126581 FDE, 3800 lsda, 0 lsda-out-of-range, 0 pc-out-of-range, 0 unresolved-personality`，
  随后全部注册成功。
- EH 探针：参考 app 的 `main()` 调用 `ehProbe(6)`，让 `RuntimeException` 穿过 6 层递归的 JIT 帧再被 `catch`，打印 `EH OK eh-probe`。
  修复前这个异常未被捕获、进程终止；修复后打印 `EH OK`，app 继续进入 UI。
- 真实点击（用 AXe 在无界面模拟器上点）：Next 进第 2 页，`+` 点三次到 `Counter: 3`，Back 回第 1 页；
  日志里没有 `ModifierNodeDetachedCancellationException`、没有 `Uncaught`，PID 不变。
- 4 类改动 × 10 次热重载 40/40 通过，其中包括之前必崩的增删 composable 和改 `if` 分支。
- Gradle 示例把探针做成启动门槛：每次启动必须看到 `EH OK`，否则启动脚本以退出码 2 失败、不重试。10 次冷启动 10/10 都有 `EH OK`。
  反向测试：启动时不设 `KALDO_SYNTH_EH_FRAME`，日志里没有合成记录、没有 `EH OK`，启动脚本退出码 2。

自检只能证明数量和地址范围对，不能证明语义对。如果以后编译器生成新的 compact unwind 记录类型，可能出现“能展开但进不去 catch”，
所以 EH 探针一直保留。

---

## 5. LSDA 偏移

LSDA 指针前后修了三处，前两处是“指错了地方”，第三处是“写错了地方”。

**1）LSDA 指进了 host 镜像**

- 现象：personality 已经能被调用，`_Unwind_GetRegionStart` 是正确的 JIT 地址，但 `_Unwind_GetLanguageSpecificData`
  返回的是 host 镜像里的地址，landing pad 跳到数据上（`udf`）。
- 根因：compact unwind 记录里的 LSDA 引用被解析到了 host 的异常表。合并对象里本地符号 `GCC_except_table0`
  出现了 3,883 次，按名字找 LSDA 本来就没有意义。
- 修法：LSDA 地址 = guest 的 `__TEXT,__gcc_except_tab` 段基址 + 记录里的对象内偏移；这个段的块用本地 live 符号保活。
- 结果：personality 拿到的 region start、IP、LSDA 都是 guest 地址，`EH check` 里 `0 lsda-out-of-range`。

**2）pcrel 的基准差了一个 CIE**

- 现象：注册成功，但展开时 libunwind 报 `getEncodedP - DW_EH_PE_textrel pointer encoding not supported` 并中止，
  说明它读到的“LSDA”开头字节不对。
- 根因：改成内联 CIE 后，`LSDAOff` 是相对**整个块**（CIE 在前）的偏移，而计算 pcrel 值时用的基准是 `FDE 地址 + LSDAOff`，
  正好多算了一个 CIE 的长度。
- 修法：字段地址 = `块地址 + LSDAOff`，pcrel 值以这个地址为基准。同时加上第 4 节说的回读校验。
- 结果：不再有任何解析中止，回读校验全部通过；但探针的异常仍然找不到 handler。

**3）LSDA 写到了下一个块里**

- 现象：`#main` 的 compact unwind 记录明明带着 `___gxx_personality_v0` 和 `GCC_except_table28`（编码 `0x44000010`，
  FRAME + HAS_LSDA），但把它的块 dump 出来，CIE 的增强串只剩 `zP` 加乱码，FDE 里的 LSDA 槽是 0，所以找不到 handler。
- 根因：回填 LSDA 时写入地址用的是 `Data + LSDAOff`，`Data` 指向 FDE 起点而 `LSDAOff` 相对块起点，
  于是写到块尾之后 29 字节处（恰好一个 `zPLR` CIE 的长度）。合成的块是连续排放的，每次写 LSDA 都把**下一个**块的 CIE 写坏了。
  `#main` 的 CIE 被前一个块写坏，它自己的 LSDA 又写到了别处，所以读回来是 0。
- 修法：写入位置改成 `块起点 + LSDAOff`。
- 结果：`EH OK eh-probe`，探针的 `catch` 第一次真正执行；Back 回收节点不再崩溃（第 4.4 节的全部验证都在这之后）。

---

## 6. 把合成的 FDE 整段注册：启动从约 52 s 降到约 5 s

**现象**。打开 `KALDO_KEEP_COMPACT_UNWIND` 后，Gradle 示例冷启动到首帧要 **52.06 s**（126,543 条 FDE、3,800 条 LSDA）：
compact unwind 扫描 + 合成 21.7 s，PostFixup 回填 + 逐条 `__register_frame` 26.8 s。

**根因**：

1. `Block::edges_at(offset)` 每次调用都从头扫一遍整个块的边。每条记录要查 3–4 次（函数、personality、LSDA），
   12.6 万条记录就是 O(N²)。
2. 每条 FDE 一个 content block、一个符号、一次 `__register_frame`。Darwin 的 `__register_frame` 一次只接受**一个 FDE**，
   不接受整段，所以要调用 12.6 万次。（更早还试过给每个块一个强符号并强制 live，12.6 万个合成强符号改变了物化依赖图，
   bootstrap 直接物化失败。）

**修法**：

1. 每个块只遍历一次 `edges()`，建一个 `offset → {第一个具名, 第一个已定义具名, 第一个已定义}` 的索引。
   扫描从 20.8 s 降到 15 ms。
2. 全部 CIE/FDE（仍是内联 CIE 布局）连续写进**一个** section 块（约 5.3 MB），只建一个 live 符号；PostFixup 结束后调用一次
   `__unw_add_dynamic_eh_frame_section(段地址)`，由 libunwind 整段解析。这个函数由 libSystem 导出（模拟器 SDK 的
   `libSystem.B.tbd` 里有），libKaldo 以 weak import 声明它，运行时没有它时回退到逐条 `__register_frame`。注册从 26.8 s 降到 9–11 ms。
3. 三种 CIE 变体各只构建一次并复用，不再为每条记录重建；名字用指向图内字符串的 `StringRef`，不再每条复制一份 `std::string`。

**结果**：

| libKaldo | compact unwind | 启动到首帧 | 合成 | 注册 |
|---|---|---:|---:|---:|
| 修复前 | 保留 | 52.06 s | 21.7 s | 26.8 s |
| 修复后 | 保留 | 6.12 s | 26 ms | 11 ms |
| 修复后 | 不保留 | 5.38 s | — | — |
| 修复后（干净 clone） | 保留 | **5.18 s** | 21 ms | 9 ms |

打开 EH 合成的启动代价约 0.2–0.7 s。

**验证**（干净 clone）：10 次冷启动 10/10 首次成功、0 次重试，每次日志都是 `EH check: 126563 FDE, 3800 lsda,
0 lsda-out-of-range, 0 pc-out-of-range` 和 `registered 126563 (section)`；热替换只改 ui 20/20、只改 app 20/20、
同时改两个 3/3，PID 全程不变；真实点击 `+` 三次到 3，Next/Back 来回三次，计数器保持 3，无崩溃。

---

## 7. 兼容性分类器：哪些改动能热替换

### 7.1 为什么要分类

函数入口重定向只换代码，不动已经存在的对象和全局状态。类字段布局、函数签名、inline / suspend 函数正文、
新增顶层属性这几类改动，编译能过、补丁能发出去，看起来热替换成功了，但旧对象仍按旧布局被访问，可能崩溃或静默算错。
inline 还有一层：Kotlin 2.5.0-RC 起默认在 klib 阶段跨模块内联，inline 函数一改，内联了它的其他调用方也要变。
所以在**发送之前**先判断，拿不准就走温重启（第 8 节）。

### 7.2 判定规则

`compat-classify.py classify` 比较改动前后两份快照，退出码 **0 = 可热替换**、**3 = 需要温重启**、**2 = 工具或参数错误**
（这时不发送补丁）。需要温重启时每条原因一行：`WARM <类别> <符号>: <原因>`。

| 类别 | 触发条件 |
|---|---|
| `field-layout` | 类字段新增 / 删除 / 类型变化 / 顺序变化，或构造函数签名变化 |
| `signature` | 函数签名变化、已有类新增方法、超类型变化 |
| `inline` | 已有 inline 函数的正文变化（IR 哈希不同） |
| `suspend` | 已有 suspend 函数的正文或签名变化 |
| `top-level-property` | 顶层属性新增 / 删除 / 类型变化 / `val`↔`var` |
| `class-removed` | 类被删除（旧实例可能还活着） |
| `class-shape` | 未识别的类成员或顶层成员发生变化（保守兜底） |

明确放行：普通函数正文、包级函数增删（也就是增删 composable）、私有方法改动、新增类、`const val` 的值变化
（初始化值不属于布局）。

规则偏保守：`class-shape` 会拦下少数本可以热替换的改动；只改构造函数默认参数值也会算 `field-layout`。宁可多重启一次，不冒错布局的险。

### 7.3 快照怎么来

`compat-classify.py snapshot` 调 Kotlin/Native 的 `klib` 工具跑 `dump-metadata` 和 `dump-ir`，整理成 JSON：

- `classes`：每个类（含嵌套类、companion）的可见性、超类型、构造函数签名、字段列表（名字、类型、`val`/`var`、可见性、
  **声明顺序**，私有字段也记）、方法签名（带 `inline`/`suspend` 标记）；
- `topProperties` / `topFunctions`：包级声明；
- `bodies`：所有 inline 和 suspend 函数的 IR 正文哈希，用来发现正文改动；
- `topOther` / `classes[].other`：认不出的成员原文，用于保守拦截。

`dump-metadata` 里的注解行不参与比较。第一版把 Compose 生成的 `@Composable`、`@StabilityInferred` 等注解算进了
“未识别成员”，结果新增一个 composable 也被判成温重启，已修正。

基线在打包时写出（每个模块一份）。ui 模块的快照来自 ui 的 klib；app 模块要单独用 `-p library` 编一个带 IR 的完整 klib
再 dump，只有 app 源文件比上次新时才重编。热替换成功后，新快照提交为基线。

### 7.4 接入 reload，以及接口 v8 的提速

接口 v7 是“先分类，再编译、发送”。开分类器后，同一工程上开/关交替对照，端到端多出：只改 ui **+554 ms**、
只改 app **+3,535 ms**；多出的时间就是分类器那一步本身，不会拖慢后面的编译和发送。只改 app 时，大头是为了快照另起一个
`kotlinc-native` 编 app 的 library klib（约 3.3 s，主要是 JVM 启动和预热）；ui 快照约 0.58 s，其中两次起 `klib` 工具的 JVM 各约 0.29 s。

v8 做了四件事：

1. **dump 进守护进程**：守护进程新增一种 `@klib` 请求，在已经热起来的 JVM 里调用 `klib` 工具的公开入口
   `org.jetbrains.kotlin.cli.klib.Main.exec(stdout, stderr, args)`（返回退出码，不会结束进程）。每次 dump 的中位数 3 ms。
2. **app klib 走守护进程**：分类用的 app library klib 交给 `hr-klib.sock` 编，从约 3.3 s 降到 0.58 s。
3. **快照缓存**：按“分类脚本自身 sha256 前 16 位 + klib 的 sha256”缓存快照（最多 64 份，按使用时间淘汰），脚本一改旧缓存自然失效；
   klib 的 sha256 和基线里记录的相同就视为没变、不再 dump。所有模块在一个 Python 进程里处理。
4. **和编译并行**：`reload` 在后台启动分类（走 `hr-klib.sock`），同时用 `hr-compile.sock` 做 cache-only 编译，两者互不排队；
   发送前等分类结论。需要温重启时丢掉这次的编译结果。

结果（同一个 app 进程跑完全部轮次，PID 不变）：

| 场景 | v8 开分类器 p50 / p90 | v7 开分类器 p50 | 分类器这一步（v8） | 发送前等分类（v8） |
|---|---|---|---|---|
| 只改 ui | 4,953 / 4,989 ms | 5,931 ms | 18 ms | 7 ms |
| 只改 app | 3,919 / 3,961 ms | 7,961 ms | 576 ms | 7 ms |
| 两个模块同时改 | 5,290 / 5,313 ms | 9,305 ms | 594 ms | 8 ms |

开/关交替对照（ui、app 各 10 对，取中位数）：只改 ui **+2 ms**，只改 app **+15 ms**，目标是不超过 300 ms。
app 的分类（约 0.6 s，主要是编 app library klib）完全藏在约 2.5 s 的 cache-only 编译后面，发送前只多等约 8 ms。
同一轮里守护进程的 `@klib` 请求 166 次全部成功。

### 7.5 验证

一条命令跑完“改动类型 → 期望结果”的回归表，16 项全部符合：

| 改动 | 期望 |
|---|---|
| 改文案、改颜色、改 `if` 分支（及改回）、改普通函数体、增 composable、删 composable | 热替换：PID 不变，新内容上屏，截图和基线不同 |
| 新增类、新增函数、新增 inline 函数、新增 suspend 函数 | 热替换，PID 不变 |
| 改类字段布局 | `WARM field-layout`，温重启后回到第 2 页、计数器 3，截图和改动前一致 |
| 改函数签名 / 改 inline 正文 / 改 suspend 正文 / 新增顶层属性 | 分别 `WARM signature` / `WARM inline` / `WARM suspend` / `WARM top-level-property`，温重启并恢复 |

截图用 `xcrun simctl io screenshot`，裁掉状态栏后比对。另外：`reload --no-warm` 遇到新增顶层属性时打印
`WARM top-level-property ...`、退出码 3，PID 不变，补丁没有发送。

---

## 8. 温重启

分类器判为“需要温重启”时，`reload` 默认自动转到 `warm`：

1. 读取 app 自己持久化的状态；
2. 完整重新打包（编译 app、合并 payload、嵌入、组装 `.app`）；
3. 带启动参数 `--hr-state=PAGE,COUNT` 重启 app；
4. 等到日志里出现目标页和计数器对应的渲染行，以及 `EH OK`；打印
   `WARM OK ms=<总耗时> ms_package=<打包> ms_relaunch=<重启到恢复>`。

**状态契约**。参考 app 每次页面或计数器变化时写 `<app Documents 目录>/hr-state.json`，内容是 `{"page":N,"count":M}`；
启动时优先用 `--hr-state` 参数，省略时读这个文件。只恢复这部分“可序列化的状态”，`remember` 里的其他瞬态
（动画进度、滚动位置等）不恢复。自带 app 壳的项目要实现同样的参数和文件才能恢复状态；不实现也能温重启，只是回到第 0 页。

**耗时**。实测一次总 63.9 s = 打包 53.6 s + 重启到恢复页面 10.3 s；缓存更热时一次是 60.7 s（54.1 s + 6.6 s）。
热替换是 4–5 s。慢在每次都完整重跑整条打包流程，哪怕只改了一个类；只重编受影响部分的优化还没做。

**踩过的两个坑**：

- 重启前要先结束旧的启动脚本。否则它看到 app 被杀，会把**旧**包重新拉起来，和正在打的新包打架。
- 温重启后立刻截图会拿到黑帧，要等约 2.5 s 首帧合成之后再比对。

**验证**。回归表里 5 次温重启都恢复到改动前的画面（截图与改动前一致）。另外加了一项温重启后的真实点击：恢复到第 2 页、
计数器 3 之后，依次点 Back → `+` → Next → Back，每一步都等对应的渲染行；最后第 2 页显示 `Counter kept: 4`、
第 1 页显示 `Counter: 4`，PID 不变，日志里没有崩溃或未捕获异常。连同前面 16 项共 17/17。

---

## 9. 其他问题速查

下面这些问题也都出现过，按现象列出，方便遇到时对照。

| 现象 | 根因 | 处理 |
|---|---|---|
| 启动时 `libc++abi: ... std::length_error: vector`，栈在 `createTypeInfo` / `CreateKotlinObjCClass` | libKaldo 先跑了 `Konan_constructors` 和 `RuntimeInit()`（会创建 ObjC 类），之后才绑定 ObjC 适配器；建类时找不到 `kotlin.Any` 的适配器，`getVtableSize` 返回 −1 | 先绑定适配器，再跑构造函数和 `RuntimeInit()`；unique prefix 仍在 `RuntimeInit()` 之后读 |
| `Could not initialize Objective-C ClassAdapters or ProtocolAdapters`、`UniquePrefix symbol is null` | `ld -r` 把适配器表和 `_Kotlin_ObjCInterop_uniquePrefix` 降成了本地符号，按名字查不到 | 合并后用 `llvm-objcopy --globalize-symbol` 恢复 |
| JIT 报 `Symbols not found: ___isPlatformVersionAtLeast` | 这个符号在 compiler-rt（`libclang_rt.iossim.a`）里，host 和 libKaldo 都不提供 | 从 Xcode 的 `libclang_rt.iossim.a` 取出 `os_version_check.c.o` 合进 guest 并 globalize（是真实实现，不是桩） |
| JIT 报 `Symbols not found: ___orc_rt_macho_tlv_get_addr` | ORC 的 link order 不传递：bootstrap JITDylib 链到主 JITDylib，却搜不到主 JITDylib 自己链到的平台 JITDylib（ORC runtime 在那里） | 把主 JITDylib 的 link order 整个复制到 bootstrap JITDylib |
| `-[ComposeHostingViewController _lifecycleState]: unrecognized selector` | ObjC 静态库（`CMPUIKitObjcUtils`）的成员被打进了缓存，进而被 JIT；JIT 进来的 ObjC 代码没有经过 dyld 的 ObjC 修正，一个本该被改写成 SEL 的字段还是裸字符串 | 合并时丢掉 host 已经定义过同名 ObjC 类的成员，让这些类从 host 解析；host 里没有的成员保留 |
| 首次布局时 skia `TextStyle::TextStyle()` 解引用空指针 | skiko 的 C++ 代码被 JIT 进 guest，C++ 静态初始化没执行 | libKaldo 收集 `__mod_init_func` 并在 bootstrap 后执行（第 1.3 节第 5 步） |
| 首帧 `IrLinkageError`、C++ ABI 不匹配引起的崩溃，或运行的是旧版 Compose 的类 | klib 版本混用（skiko、Compose runtime 版本和编译时不一致），或旧的自动缓存因模块名相同被复用 | 用和 Compose 源码锁定一致的 klib；换新缓存目录重建；构建前检查每个 klib 的名字、编译器版本、哈希，缺失或重复就失败 |
| 补丁发出后新实现查找失败（`failed to lookup implementation symbol ...$knhr`） | 对象名含 `-cache.a`，被外部化跳过 | 发送前改名为 `patch.o`（第 1.4 节） |
| 冷启动约 20% 在 `Konan_constructors` 前 SIGSEGV | 未登记的合成定义进了 dependency group，`simplifyDepGroups` 越界读 | LLVM 补丁修复（.6，第 1.5 节）；之前靠收窄热符号集规避 |
| 模拟器以 `codesign invalid-page` 拒绝启动 | 可执行文件里有指向 `.app` 外部 LLVM 安装目录的绝对 rpath | 打包时删除所有绝对 rpath，只留 `@executable_path/Frameworks`，再对 `.app` ad-hoc 重签 |
| 用 `simctl` 启动后 ORC 初始化失败（`No such file or directory`） | `simctl` 不转发父进程的环境变量，ORC runtime 路径落到了一个不存在的默认位置 | 环境变量加 `SIMCTL_CHILD_` 前缀传给 app（如 `SIMCTL_CHILD_KONAN_ORC_RUNTIME_PATH`） |

---

## 10. 对 release 构建的影响

所有改动都在开关或 split 分支后面：cache-only 只在设了 `KONAN_HOT_RELOAD_CACHE_ONLY=1` 时生效；compact unwind 保留、
EH 合成、热符号集分别由 `KALDO_KEEP_COMPACT_UNWIND`、`KALDO_SYNTH_EH_FRAME`、`KALDO_HOT_SYMBOLS` 控制；非 split 构建不经过这些路径。

用一个不走 split 的小程序，在打补丁前后的编译器上各编 3 次：`__TEXT.__text` 大小（362,880 字节）和符号数（6,014）完全一致，
整文件差 320 字节，落在调试信息和字符串表里（缓存路径长度不同）。同一个编译器连续编两次，整文件 md5 也不同
（`LC_UUID`、`__objc_methname`、`__LINKEDIT`），所以比对 release 产物应比较各 section 大小、符号表和 `__text` 内容，
不要用整文件 md5。

工具链 .6 的强制重组在 Compose `ui` klib 里（§1.6）。工程的 release 构建由 Kotlin Gradle 插件从 Maven 解析 Compose，用不到这个 klib；用普通 Compose Multiplatform 工程离线编 release 验证过，产物里没有 `forceRecomposeAllForHotReload`（REPORT §29.4）。用工具链自带 klib 编出的 -opt 产物里有这段代码，但只在 `Platform.isDebugBinary` 为真时执行。

---

## 11. Kotlin Multiplatform 工程（commonMain / iosMain、expect / actual）

2026-11-19 摸底。结论：**编译器、两个守护进程、libKaldo 都不用改**。参考脚本加了可选的源码集参数；插件按同样的方式拼参数即可。

### 11.1 参数怎么拼

一个模块（ui 库或 app 程序）仍然是一次编译，各个源码集作为 fragment：

```
-Xmulti-platform -Xexpect-actual-classes
-Xfragments=commonMain,iosMain,iosSimulatorArm64Main
-Xfragment-refines=iosMain:commonMain -Xfragment-refines=iosSimulatorArm64Main:iosMain
-Xfragment-sources=commonMain:/abs/…/commonMain/kotlin/a.kt     # 每个文件一条
-Xfragment-sources=iosMain:/abs/…/iosMain/kotlin/b.kt
/abs/…/commonMain/kotlin/a.kt /abs/…/iosMain/kotlin/b.kt         # 同样的文件也作为自由参数
```

- 不用 `-Xseparate-kmp-compilation`，所以不需要 `-Xfragment-dependency`：所有 fragment 都看得到 `-l` 给的平台 klib（Compose 等）。
- 没有文件的 fragment（例如空的 `iosSimulatorArm64Main`）照样写进 `-Xfragments` 和 `-Xfragment-refines`。
- 路径必须是绝对路径：`hrc.py` 只转发参数，不转发调用方的工作目录。参数原样转发，守护进程和协议都不用改。
- `-Xexpect-actual-classes` 只是关掉 “expect/actual classes are in Beta” 的警告。
- 参考脚本：`UI_FRAGMENTS`、`UI_FRAGMENT_REFINES`、`UI_FRAGMENT_ROOT`，app 模块对应 `APP_*`（INTERFACE §5）。app 模块里不含 `main`
  的文件，它们的包要写进 `KALDO_HOT_PACKAGES`（插件已经按源码里的包自动生成）。

### 11.2 产物和缓存

- ui 的产物是普通的 `ios_simulator_arm64` klib，manifest 带 `language_features=+MultiPlatformProjects`。metadata 里只有实化后的声明：
  expect 不出现，actual 就是普通的 public 声明。
- 增量缓存按源文件建：ui 的在 `IC_DIR/…/ui-per-file-cache/<包>.<hash>/`，app 的在 `IC_DIR/…/<hash>-per-file-cache/`。
  多个源码目录没有影响；merge cache 按 archive 内容缓存，也不受影响。
- `reload` 改为按缓存所在的模块分 app / ui。以前按 `_kfun:#main`、`_kfun:expui` 符号分，app 里不含 `main` 的文件会被当成
  unclassified 拒掉；现在 `IC_DIR` 之外的缓存（库的缓存）仍然拒绝发送。

### 11.3 热重载（`example-gradle/accept-kmp.sh`）

ui 和 app 两个模块都是 `commonMain → iosMain → iosSimulatorArm64Main`。启动时 EH check 126,610 FDE、`EH OK`；
画面上 common、iOS actual（调 UIKit / Foundation）、`expect class` 都生效。

| 改动 | 结果 |
|---|---|
| ui 的 commonMain 函数体 | HOT，同一 PID |
| ui 的 iosMain `actual fun` 和 `actual class` 的方法体 | HOT |
| app 的 commonMain 函数体（经 `hr-compile.sock` 的 cache-only 编译） | HOT |
| app 的 iosMain `actual` 函数体 | HOT |
| `expect` 签名（连同 actual） | WARM（分类器报 `signature platformLabel: signature changed`），温重启后显示新值 |

分类器**不需要** expect/actual 配对或 fragment 归属：它比较的是实化后的 klib。改 expect 签名必然要同时改 actual，actual 的签名变化照常被检出；
只改 actual 的函数体就是普通的函数体变化。新增一对 expect/actual 等于新增函数（HOT）；`expect class`/`actual class`
的形状变化按类的规则处理；`actual typealias` 按 typealias 规则（改指向判 signature）。

### 11.4 第三方 klib

用 `-l` 加入 klib，并把它所在的目录加进 `KLIB_AUTO_CACHE_FROM`（split 编译要给每个库建缓存）。
`kotlinx-serialization-json` 1.7.3（用 Kotlin 2.0.20 编译，ABI 1.8.0，不在工具链的 `klibs.txt` 里）：编译、启动、运行时实际调用
（解析 JSON）、温重启、以及修改调用它的代码后再热替换，都通过（`example-gradle/accept-kmp-json.txt`）。
能读的版本：Kotlin 1.6.10（ABI 1.6.0）到 2.5.x（ABI ≤ 2.5.0），metadata 最高 2.6.0。ABI 更新的 klib 只给一条
`KLIB loader: Incompatible ABI version` 警告就被跳过，用户随后看到的是 “unresolved reference”（`example-gradle/accept-kmp-klib-versions.txt`）。

### 11.5 没做的部分，以及交给插件的部分

- Gradle 侧：从 `kotlin { sourceSets }` 读出 fragment 和 dependsOn 关系，按依赖解析第三方 klib，并把它们加进 auto-cache 根目录（由 Gradle 插件负责）。
- 只验证了 `iosSimulatorArm64` 这一个 leaf；没有测 `-Xseparate-kmp-compilation` 方案；没有测 commonMain 带 Compose resources 的情况。

---

## 12. 已知限制

现状和已知问题统一维护在 [docs/LIMITATIONS.md](LIMITATIONS.md)。要点：只支持 iOS 模拟器（arm64）和 debug / split 构建，
真机未做；温重启只恢复可序列化的状态，而且比热替换慢（参考脚本约 16 s）；分类器对少数改动偏保守；启动偶发崩溃的根因已在 LLVM 补丁里修复（.6，§1.5）；长时间运行（约 3.5 小时）后发送到首帧会从约 0.9 s 涨到约 1.86 s，原因未定位；plist 布尔值桥接已在工具链 .5 修复（§1.8）；runtime 一次只处理一个请求，上一次热重载没完成时再保存的行为没有验证。
