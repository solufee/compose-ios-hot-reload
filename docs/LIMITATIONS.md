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
| 类字段布局 / 函数签名 / inline / suspend / 新增顶层属性 | ✅ 分类器拦下并自动温重启 |
| 温重启恢复页面与计数器 | ✅ 回到原页面，状态从 `hr-state.json` 恢复（`--hr-state` 启动参数） |
| iOS 模拟器（arm64），macOS arm64 主机 | ✅ |
| Gradle 双模块示例（app + ui），一条命令出包 | ✅ |
| 多模块热替换（改 ui、改 app、同时改） | ✅ 20/20、20/20、3/3 |
| 同一模块内多个文件同时改 | ✅ 3/3，同一 tick |
| 异常穿过 JIT 代码（EH） | ✅ 每次启动由 `EH OK` 探针检查 |
| 预构建工具链（打包 → 发布 → 下载） | ✅ 本地 `file://` 全流程验证；0.1.0 起从 GitHub Release 下载 |
| 真机 | ❌ 未做 |

## 已知问题

1. **温重启只恢复“可序列化状态”**。分类器拦下不兼容改动后自动重新打包、重启，恢复页面和计数器
   （`Documents/hr-state.json` + `--hr-state` 启动参数），其它 `remember` 瞬态（动画进度、滚动位置等）不恢复。
   参考 app 实现了这个契约；自带壳的打包方要实现同样的参数和文件才能恢复状态（INTERFACE §10.3）。
   温重启的耗时见下一条；热替换路径不受影响。
2. **温重启慢：约 64 s，其中约 53 s 是重新打包**。B4 验收实测总 63.9 s = 完整 `package` 53.6 s +
   重启到恢复页面 10.3 s，而热替换是 4–5 s。原因是 `hotreload.sh warm` 每次都重跑整条
   `package`（编译 app、合并 payload、嵌入、组装 `.app`），哪怕只改了一个类。优化方向是只重编受影响的部分；
   还没做。
3. **分类器对少数改动偏保守**。新增/删除类、包级函数增删、私有方法改动放行；但未识别的类成员变化
   （`class-shape`）会被拦下，少数本可热替换的改动会多一次温重启。宁可多重启，不静默崩溃。
4. **plist 布尔值桥接**。`NSNumber` 布尔值被桥接成 Kotlin `Boolean` 的检查暂时关闭
   （`enforceStrictPlistSanityCheck=false`）。依赖 plist 布尔值的代码路径可能行为不同。
5. **【参考脚本】EH 合成必须开**。`KALDO_KEEP_COMPACT_UNWIND=1`（打包）和 `KALDO_SYNTH_EH_FRAME=1`（启动）是
   正确性要求，`hotreload.env` 默认打开并导出。关掉后异常穿过 JIT 代码时进程会终止；`run.sh` 每次启动都要求
   `EH OK eh-probe`，缺了就以退出码 2 失败、不重试。启动时合成并整段注册约 12.6 万条 FDE，开 KEEP 的代价约
   0.2–0.7 s。`KALDO_SYNTH_EH_FRAME` 按值判断：未设、空串、`0` 为关（v5 起）。
6. **启动偶发失败（已规避，根因未修）**。全部 Kotlin 符号都外部化时，冷启动约 20% 在 `Konan_constructors` 前
   SIGSEGV（ORC `simplifyDepGroups`）。启动时传收窄的 `KALDO_HOT_SYMBOLS`（`run.sh` 默认传
   `build/app/hot-symbols.txt`）后 30/30、10/10 首次成功；`run.sh` 仍保留最多 3 次重试兜底。
   在 `Core.cpp` 接受重复定义的尝试会让 bootstrap 物化失败，已回退；根因还没修。
7. **长会话退化**。App 连续运行 3.5 小时（tick≈12,400）后，发送到首帧从 0.9 s 涨到 1.86 s；重启进程恢复。
   原因（JIT 符号表、旧模块回收还是 Compose 侧）未定位。KEEP 打开后的长会话还没测。
8. **release 构建的确定性**。`__text` 大小和符号表一致，但每次链接整文件 md5 都不同（`LC_UUID`、
   `__objc_methname`、`__LINKEDIT`），不能用整文件 md5 做验收口径，应比较 section 大小 + 符号表 + `__text` 内容。
   另外，同一源码、同样参数两次 release 构建，`__text` 偶发 78 字节差异（`__DATA_CONST,__const` 里两个数据槽顺序对调）；
   不应用插件、没有守护进程时同样出现，是工具链本身的不确定性。插件的 release 校验因此把基线构建出现过的
   `__text` 哈希都算通过（见 `gradle-plugin/README.md`）。
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
11. **无参 composable 不会自动重组**。Compose 会跳过没有参数变化的 composable；如果一个被热替换的
   `@Composable` 既没有参数、也不读任何 state，替换后旧代码会继续显示，看起来像热重载没生效。
   给它传一个会变化的参数（例如页面每秒递增的 tick）**并在函数体里读到它**，或者让它读一个 `State`；
   只传不读同样会被跳过（独立试用里实测：只传不读时画面不变，读了之后约 5 s 自动生效）。这是 Compose 的跳过逻辑，
   不是热重载的 bug；真实项目里改「不接收参数的叶子组件」时一定会遇到。
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
