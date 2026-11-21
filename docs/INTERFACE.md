# 底层接口（interfaceVersion 8）

本文件是 Gradle 插件（`gradle-plugin/`）使用底层工具链的唯一契约。插件只读取 `toolchain.json`、按下面的命令行约定
调用工具，不复制脚本逻辑。任何接口变化都会在本文件末尾的变更记录里登记，并把
`interfaceVersion` 加一。

- 当前版本：**8**（2026-11-19）
- 产出者：底层工具（`patches/`、`daemon/`、`scripts/`、`example-gradle/hotreload.sh`）
- 消费者：Gradle 插件（`gradle-plugin/`）

## 1. 工具链清单 `toolchain.json`

由 `scripts/gen-toolchain.sh` 生成，放在 Kotlin/Native dist 根目录
（`$KOTLIN_DIST/toolchain.json`）。插件读取后校验 `interfaceVersion`。

v6 起同一份 schema 有两种 `layout`：
- `"local"`（本节示例）：本机已经装好的工具链，路径都是绝对路径；
- `"bundle"`：对外发布的清单，路径都相对于组件解包目录，另带 `toolchainVersion` 和 `components`
  （每个组件的文件名、字节数、sha256、解包目录、下载渠道），见 §9。
`fetch-toolchain.py` 下载、校验、解包之后生成的 local 清单，会原样保留 `toolchainVersion` 和 `components`，用来追溯来源。

```json
{
  "interfaceVersion": 8,
  "layout": "local",
  "generatedAt": "2026-11-19T14:04:21+08:00",
  "kotlin": {
    "version": "2.5.255-SNAPSHOT",
    "dist": "<KOTLIN_NATIVE_DIST>",
    "kotlincNative": "<KOTLIN_NATIVE_DIST>/bin/kotlinc-native",
    "compilerJar": "<KOTLIN_NATIVE_DIST>/konan/lib/kotlin-native-compiler-embeddable.jar",
    "klibDir": "<KOTLIN_NATIVE_DIST>/klib",
    "baseCommit": "8e53ee99fbc08e264828ab082aee3e448194eca8",
    "patch": "kotlin-native-hot-reload.patch",
    "patchSha256": "<SHA256_OF_KOTLIN_PATCH>"
  },
  "compose": {
    "version": "1.13.0-rc01",
    "pluginJar": "<COMPOSE_PLUGIN_JAR>",
    "klibList": "<KLIBLIST>",
    "baseCommit": "f39822e6a77eea5d194e4f9bb56cb6961321b849",
    "patch": "compose-multiplatform-core-build.patch",
    "patchSha256": "335d7a32356b92b1ba395b63235f4b20e804a610411db693bb2858a389eb7a50"
  },
  "llvm": {
    "baseCommit": "2f8844c2b35557baced9106c50b9b8758fa6a0de",
    "kaldoIossim": "<KALDO_INSTALL>/lib/libKaldo.iossim.dylib",
    "kaldoIossimSha256": "<SHA256_OF_LIBKALDO_IOSSIM>",
    "patch": "llvm-kaldo.patch",
    "patchSha256": "<SHA256_OF_LLVM_PATCH>"
  },
  "host": { "xcode": "Xcode 27.2 Build version 27B52", "iosRuntime": "iOS 27.2", "arch": "arm64" },
  "runtime": {
    "target": "ios_simulator_arm64",
    "reloadPort": 5567,
    "reloadPortEnv": "KONAN_HOT_RELOAD_PORT",
    "singleObjectPerReload": true,
    "mergeChangedObjectsWith": "ld -r",
    "readyMarker": "HR READY first-frame"
  }
}
```

`runtime.readyMarker` 只在工具链 .11 起的清单里有，见 §6.1；.10 及更早的清单没有这个字段。

工具链身份 = 公开基线 commit + 补丁 sha256。`baseCommit` 只写官方仓库能 checkout 到的公开提交，不写本地分支；`patch` 是补丁文件名，`patchSha256` 由脚本对 `patches/` 目录现算，不手填。
本地构建用的是 work-7606 线（`eb20031f740`，另有两处未提交改动，已包含在 Kotlin 补丁里），这只是构建来源说明，不是声明的基线。

`llvm.kaldoIossimSha256`（v3 起）是 `kaldoIossim` 指向的实际文件的 sha256。消费者应在使用前对该文件重算一次并比较：
不一致就说明清单过期或库被替换，必须报错，而不是只比 `patchSha256`。
生产者规则：**任何 commit 只要改了 `patches/` 下的文件或重建了 libKaldo，就必须在同一 commit 里重跑
`gen-toolchain.sh`**，并通知插件新的 `patchSha256` / `kaldoIossimSha256`。

生成：

```bash
KOTLIN_DIST=... COMPOSE_PLUGIN_JAR=... KLIBLIST=... KALDO_DYLIB=<KALDO_INSTALL>/lib/libKaldo.iossim.dylib \
  scripts/gen-toolchain.sh
# v4 起 KALDO_DYLIB 必填，没有默认位置（以前默认 ~/.konan/dependencies/...，已取消）
# 可选：TOOLCHAIN_JSON（输出路径）、PATCHES_DIR（默认 <repo>/patches）、
#       KOTLIN_VERSION、COMPOSE_VERSION、KOTLIN_BASE、LLVM_BASE、COMPOSE_BASE、
#       PATCH_KOTLIN、PATCH_LLVM、PATCH_COMPOSE、XCODE_VERSION、IOS_RUNTIME
```

退出码：0 成功；非 0 表示缺环境变量、`KALDO_DYLIB` 不存在或写入失败。生成后可用
`python3 -c 'import json;json.load(open("toolchain.json"))'` 自检。

## 2. 常驻编译进程

### 2.1 `HrCompileDaemon`

```
java -cp <compilerJar>:<classes> HrCompileDaemon <compilerJar> <socketPath>
```

- 启动后打印一行 `READY <socket> pid=<pid>`，然后在 Unix socket 上循环。
- 环境：`KONAN_HOT_RELOAD_CACHE_ONLY=1` 时，主编译（`-p program` 且不是缓存子编译）在缓存构建后
  直接返回；未设置时走完整路径。该开关是**进程级**的，所以缓存路径与 klib 路径要用两个进程。
- 协议（请求）：一行一个编译参数，空行结束。
- 协议（响应）：`OK <ms>` 或 `ERR <exitCode> <ms>`（编译失败/内部异常），连接随响应关闭。
- 单请求串行处理；一次只服务一个连接。
- **工具请求（v8）**：第一行是 `@klib` 时，第二行是输出文件路径，其余各行是 `klib` 工具的参数
  （例如 `dump-metadata`、`<path>.klib`）。守护进程在本 JVM 里调用 `klib` 工具的公开入口
  `org.jetbrains.kotlin.cli.klib.Main.exec(stdout, stderr, args)`（返回退出码、不会结束进程），stdout 写入
  输出文件、stderr 写入守护进程日志；响应同上（`OK <ms>` / `ERR <exitCode> <ms>`），日志行为 `KLIB <命令> -> ...`。
  分类器用它在热 JVM 里做 dump，省掉每次两次 JVM 启动。

### 2.2 `hrc.py`

```
python3 hrc.py <socket> <arg> [arg...]
```

- 把参数按上面的协议发给守护进程，打印响应行。
- 退出码：`0` 编译成功；`1` 编译失败（`ERR`）；`2` 连不上守护进程（socket 不存在/没人监听）。

### 2.3 `daemon-start.sh`

```
daemon-start.sh [--force]
```

- `--force` 先杀掉本工具链的旧守护进程再启动。
- 启动两个守护进程：`hr-compile.sock`（cache-only）和 `hr-klib.sock`（普通 `-p library`）。
- socket 目录 `0700`、socket `0600`，`umask 077`；已存在时检查 owner 并试连，活着就拒绝启动。
- socket 路径受 macOS 限制：**最长 104 字节**。项目路径较深时用 `HRC_SOCKET_DIR` 指到短目录
  （例如 `~/Library/Caches/cmp-hot-reload/<hash>/`），`hr-compile.sock` / `hr-klib.sock` 会建在那里。
- **默认路径（v8）**：`hotreload.sh` 与本脚本取同一套默认值：`HRC_SOCKET_DIR` 默认 `<工程目录>/.hr`，
  `HRC_SOCKET` 默认 `$HRC_SOCKET_DIR/hr-compile.sock`，`HRC_KLIB_SOCKET` 默认 `$HRC_SOCKET_DIR/hr-klib.sock`。
  v7 及以前 `hr-compile.sock` 只有显式导出 `HRC_SOCKET` 才会被 `reload` 使用；v8 起默认路径上有 socket 就用，
  没有才回退到直接起 `kotlinc-native`，并打印一行
  `compile: no daemon at <path>; using kotlinc-native (start daemon/daemon-start.sh to save ~3 s per reload)`。
- `--force` 只结束命令行同时匹配本工具链 jar 和本 socket 的进程，不影响其他工程的守护进程。
- 环境：`KOTLIN_DIST`（必需）、`JAVA_HOME`、`HR_WORKDIR`（socket 与日志所在目录，默认 `$PWD`）、
  `HRC_SOCKET`、`HRC_KLIB_SOCKET`、`HRC_SOCKET_DIR`、`HRC_LOG`、`HRC_KLIB_LOG`。
- 退出码：0 成功；1 拒绝启动或启动失败（打印原因）。

## 3. payload 合并与嵌入

### 3.1 `merge-payloads.py`

```
merge-payloads.py <base.kexe> <out.o>
```

- 输入：split 方案产出的 base 可执行文件（`__TEXT,__kaldo_boot` 里有多个 payload）。
- 输出：`<out.o>` 一个 relocatable 对象（`ld -r` 合并全部 payload）。
- 副作用：写热符号清单到 `KALDO_HOT_SYMBOLS_OUT`（默认脚本同目录 `hot-symbols.txt`），每次运行先清空。
- 环境：
  - `KONAN_LLVM_HOME`（llvm-objcopy/llvm-nm 所在目录）
  - `KALDO_KEEP_COMPACT_UNWIND=1`：保留 compact unwind（改名成 `__kaldo_cu` 供合成 eh_frame）
  - `KALDO_HOT_PACKAGES`：逗号分隔的用户模块包名（如 `expui`），这些包的符号会进热符号清单
  - `KALDO_HOT_SYMBOLS_OUT`：热符号清单路径
  - `KALDO_GUEST_KEEP_OBJC=1`：不丢弃 host 已定义的 ObjC 成员（默认丢弃）
  - `KALDO_GUEST_DROP_CXX=1`：丢弃只含 C++ 的成员（默认保留）
  - `KALDO_MERGE_CACHE_DIR`（可选，首发后新增）：逐 archive 的结果缓存目录。库的 cache archive 每次打包都一样，
    处理结果（去重、ui 重复符号弱化）和热符号按 `sha256(脚本, 开关, archive 内容)` 存下，下次直接用；处理时查过
    host 镜像的 archive（含 `CMP*` ObjC 成员）不缓存。合并成功后删除本次没用到的条目，目录里只留一套（参考 app 约 180 MB）。
    不设就不缓存，行为与以前相同。`hotreload.sh package` 默认设为 `$BUILD/merge-cache`。
  - `KALDO_LIB_PAYLOAD_DIRS`（可选，工具链 .10 起，Issue #32 第二步；要求同时设 `KALDO_PAYLOAD_LIST` 和 `KALDO_MERGE_CACHE_DIR`）：
    冒号分隔的目录，其中的 payload 算“库”。库只在内容变化时 `ld -r` 一次，结果缓存在合并缓存目录（`<key>.o`），并硬链接到
    `<输出名去掉 .o>.libs.o`；每次只把用户 payload 合并进 `<输出>`。处理时查过 host 镜像、不能缓存的库 archive（如带 `CMP*` ObjC 成员的
    ui-uikit）归入用户对象。没有写出 `.libs.o` 时就是以前的单对象合并。`hotreload.sh package` 设为 `$AUTO_CACHE`。
  - `KALDO_PAYLOAD_LIST`（可选，工具链 .9 起，Issue #32）：payload 路径清单（每行一个，顺序即加载顺序），由编译器在
    `-Xbinary=splitPayloadList=<文件>` 时写出。设了就直接从这些路径读 payload，不再从 kexe 的 `__kaldo_boot` 段导出；
    这时 kexe 里只有空清单和同样大小的零填充预留，嵌入合并结果的大小不能超过它（合并结果总是比原 payload 之和小）。
    不设时行为与以前相同。`hotreload.sh package` 在编译器写出了清单时自动设置。
- 退出码：0 成功；2 参数个数不对；其它非 0 表示链接/复制失败。
- 输出：合并结果与不带缓存时相同（符号表、load command、热符号清单逐项一致）；不同的只有调试 stab 里的临时路径和
  mtime（以前两次运行之间也不同），以及符号表里两个被 globalize 的符号的先后次序。
- 首发后提速（不改契约）：热符号收集改为每个 archive 一次 `nm`（以前每个成员一次，约 3000 次、20 s）；
  9 个 `--globalize-symbol` 合成一次 `llvm-objcopy`。

- 工作目录 `$TMPDIR/kaldo-merge-*`（约 600 MB）在任何退出路径上都会删除（v8 起 `try/finally`；v7 及以前只在成功时删，失败或被杀会留下）。调试时设 `KALDO_MERGE_KEEP=1` 保留，并在 stderr 打印路径。

### 3.2 `embed-merged.py`

```
embed-merged.py <base.kexe> <merged.o> [<object> | @file:<path> ...] <out.kexe>
```

- 把合并对象写成 `KALD0` manifest，替换 `__TEXT,__kaldo_boot`，输出新的可执行文件。三个参数时与以前完全相同（单条）。
- 工具链 .10 起（Issue #32 第二步）可以给多个对象，每个一条，按给出的顺序加载；`@file:<path>` 只记下绝对路径（种类 2），
  libKaldo 启动时从这个文件读对象，不嵌进可执行文件（用于缓存的库对象，app 因此依赖这台 Mac 上的这个文件，只适合模拟器开发）。
  种类 2 需要 .10 起的 libKaldo；旧 libKaldo 遇到它会拒绝清单、启动失败。
- `__kaldo_boot` 段放不下时（预留不够）objcopy 报 `new section cannot be larger than previous section`，退出非 0。
- 环境：`KONAN_LLVM_HOME`（llvm-objcopy）。
- 退出码：0 成功；2 参数个数不对；非 0 表示 objcopy 失败。

### 3.3 打包方义务（任何打包实现都必须做，v3 起）

不论用 `hotreload.sh`、Gradle 插件还是别的驱动出 `.app`，下面几条都是契约的一部分；漏掉任何一条，
app 会拒绝启动、启动崩溃或异常无法展开。

| # | 要求 | 不做的后果 |
|---|---|---|
| 1 | 调 `merge-payloads.py` 时导出 `KALDO_KEEP_COMPACT_UNWIND=1` | 没有 `__kaldo_cu`，无法合成 eh_frame，异常穿过 JIT 代码时进程终止 |
| 2 | 启动 app 时传 `SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME=1` | 同上（EH probe 或翻页时终止） |
| 3 | 启动 app 时传 `SIMCTL_CHILD_KALDO_HOT_SYMBOLS=<merge-payloads 产出的 hot-symbols.txt>` | .5 及以前：全部符号外部化时冷启动约 20% 在 ORC `simplifyDepGroups` SIGSEGV。.6 起根因已修，不传也稳定，但仍建议传（启动快约 1 s，热替换限定在用户模块） |
| 4 | 打包（host 链接）那一步**不能**带 `KONAN_HOT_RELOAD_CACHE_ONLY=1` | host 链接被跳过，拿不到 base 可执行文件 |
| 5 | 把 `libKaldo.iossim.dylib` 拷到 `<App>.app/Frameworks/`（来源只能是 `KALDO_DYLIB` 或清单的 `llvm.kaldoIossim`，不得回落到 `~/.konan` 或其他默认位置），并删除可执行文件里**所有绝对路径 rpath**（`otool -l` 读 `LC_RPATH`，以 `/` 开头的逐条 `install_name_tool -delete_rpath`），只留 `@executable_path/Frameworks` 一类的相对 rpath | 加载到 `.app` 外的 dylib，模拟器以 `codesign invalid-page` 拒绝启动 |
| 6 | 上一条改完后对整个 `.app` 做 ad-hoc 重签：`codesign --force --deep --sign - <App>.app` | `install_name_tool` 改过的可执行文件签名失效，模拟器拒绝启动 |
| 7 | 打包前校验 `toolchain.json`：`interfaceVersion`、三个 `patchSha256`，以及对 `kaldoIossim` 重算的 sha256 = `kaldoIossimSha256` | 清单与实际库不一致时会静默用错版本 |

参考实现：`example-gradle/hotreload.sh` 的 `package` 分支（第 5、6 条）、`example-gradle/hotreload.env`（第 1、2 条）、
`example-gradle/run.sh`（第 2、3 条）。

回归检查（v4 起参考 app 固定带上）：`example-gradle/app/.../main.kt` 的 `ehProbe` 在 `main()` 里让异常穿过 6 层 JIT 帧再接住，
打印 `EH OK eh-probe`。`run.sh` 每次启动都要求这一行；出现 `APP MAIN` 却没有 `EH OK` 判为 EH 回归，
直接失败（退出码 2），不当成偶发崩溃重试。插件自己的验收建议照做。

## 4. 发送

### 4.1 `reload.py`

```
python3 reload.py <object path>
```

- 端口取 `KONAN_HOT_RELOAD_PORT`（默认 5567），连接 `127.0.0.1:<port>`，发送**一个**对象路径。
- `hotreload.sh reload` 直接继承该环境变量；`run.sh` 用 `SIMCTL_CHILD_KONAN_HOT_RELOAD_PORT`
  把它传给 app，使 app 与发送端用同一个端口。
- 线上格式与 runtime 一致：`u32 数量` + 每个 `u32 长度 + 路径字节`；runtime 支持多个，
  但本接口约定每次只发一个（多模块先用 `ld -r` 合并）。
- 退出码：0 成功；1 连接/发送异常（stderr 有 traceback）。

## 5. 参考驱动 `hotreload.sh`（插件可复用其参数，不必复用实现）

```
hotreload.sh ui-klib | package | reload
```

| 子命令 | 作用 | 关键环境变量 |
|---|---|---|
| `ui-klib` | 把 ui 源目录编成 `build/ui/ui.klib`；守护进程失败或 socket 失效时改用 `kotlinc-native` 重跑 | `KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`KLIB_AUTO_CACHE_FROM`、`UIKIT_UTILS`、`BUILD_DIR`、`UI_SRC_DIR`、`HRC_KLIB_SOCKET`；多平台布局：`UI_FRAGMENTS`、`UI_FRAGMENT_REFINES`、`UI_FRAGMENT_ROOT` |
| `package` | split 编译 app（多平台布局用 `APP_FRAGMENTS`、`APP_FRAGMENT_REFINES`、`APP_FRAGMENT_ROOT` 代替 `APP_SRC`；`hr-klib.sock` 在就走它，失败时改用 `kotlinc-native` 重跑；设了 `KONAN_SPLIT_MERGED_BOOTSTRAP` 时直接用 `kotlinc-native`；工具链 .9 起带 `-Xbinary=splitPayloadList=$BUILD/app/payloads.txt`，编译器不认得时只警告、不写清单，回到嵌入 payload 的老路径；分类器基线与合并、嵌入、组装并行；.10 起：库对象缓存、作为第二条 payload，libKaldo 认得种类 2 时从文件加载、不嵌入（`HR_LIBS_IN_EXECUTABLE=1` 改回嵌入），预留按上次合并大小给（`-Xbinary=splitPayloadReserve`，放不下时自动用全量预留重打一次；`HR_NO_RESERVE_HINT=1` 不给提示））+ 合并 payload + 嵌入 guest + 组装 `.app` | 上面的 + `IC_DIR`、`AUTO_CACHE`、`APP_SRC`、`OUT`、`KALDO_HOT_PACKAGES`、`KALDO_MERGE_CACHE_DIR`（默认 `$BUILD/merge-cache`）；libKaldo 取 `KALDO_DYLIB`，未设时取 `$KOTLIN_DIST/toolchain.json` 的 `llvm.kaldoIossim`，都没有就退出 1；与清单 `kaldoIossimSha256` 不一致也退出 1 |
| `reload` | 分类（v7；v8 起与编译**并行**）+ cache-only 编译 → 过滤本次重建的 archive → `nm` 分类 → `ld -r` → 符号检查 → **等分类结论** → 发送；需要温重启时默认自动 `warm`，`--no-warm` 时只拦截并退出 3 | 上面的 + `HRC_SOCKET`、`HRC_SOCKET_DIR`、`COMPAT_DIR`、`COMPAT_CLASSIFIER`、`KLIB_TOOL` |
| `warm` | 读取 app 状态 → `package` → 用 `--hr-state` 重启 → 等 `EH OK` 与目标页/计数器，打印 `WARM OK ms=... ready=state\|marker\|fallback`。清单有 `runtime.readyMarker`（.11 起）时，还要等到首帧就绪标记；app 恢复后 `HR_READY_FALLBACK_S` 秒内没等到就按旧规则判定，规则见 §6.1。没有这个字段，或者设了 `KONAN_HR_READY_MARKER=0` 时，规则和以前一样。打包期间会在后台只读目标模拟器的 `ApplicationAccessibilityEnabled`，值为 1 时往 stderr 打一行 `hint: simulator … has accessibility on …`，不改任何设置（Issue #60） | `UDID`、`BUNDLE`、`WARM_LOG`、`HR_READY_MARKER`、`HR_READY_FALLBACK_S`、`KONAN_HR_READY_MARKER`、`HR_AX_HINT` |

**Kotlin Multiplatform 布局（可选，首发后新增）**：设了 `UI_FRAGMENTS`（或 `APP_FRAGMENTS`）时，源码取自
`<*_FRAGMENT_ROOT>/<fragment>/kotlin/**/*.kt`，编译参数为 `-Xmulti-platform -Xexpect-actual-classes -Xfragments=<逗号分隔>`、
每条 refines 一个 `-Xfragment-refines=<from>:<on>`、每个文件一个 `-Xfragment-sources=<fragment>:<绝对路径>`，同样的文件再作为自由参数。
`ui-klib`、`package`、`reload` 三处编译都把这些参数原样交给守护进程（或 `kotlinc-native`），守护进程和协议不变。例：
`UI_FRAGMENTS="commonMain iosMain iosSimulatorArm64Main" UI_FRAGMENT_REFINES="iosMain:commonMain iosSimulatorArm64Main:iosMain"`。
app 里不含 `main` 的文件所在的包要写进 `KALDO_HOT_PACKAGES`。原理和验证见 TECHNICAL-NOTES §11。
`ui-klib` 把这次的源码参数写进 `$BUILD/ui/ui.inputs`；`package` 发现参数变了（例如在普通布局和多平台布局之间切换）就重编 `ui.klib`，不只看文件时间。
`reload` 按缓存所在模块分 app / ui：`IC_DIR` 下 `ui-per-file-cache` 里的是 ui，其余 `*-per-file-cache` 是 app，`IC_DIR` 之外的缓存不发送
（以前按 `_kfun:#main` / `_kfun:expui` 符号分，app 里不含 `main` 的文件会被拒）。

`hotreload.sh` 会 source 同目录的 `hotreload.env` 并**导出**其中的变量（`set -a`），所以
`KALDO_KEEP_COMPACT_UNWIND`、`KALDO_SYNTH_EH_FRAME` 不再依赖调用者手工 export；`package`
会临时 unset `KONAN_HOT_RELOAD_CACHE_ONLY`，保证 host 链接真的执行。
`package` 结束前会删除指向 LLVM 安装目录的绝对 rpath，并对 `.app` 做 ad-hoc 重签
（`codesign --force --deep --sign -`）。这两步以及开关要求对**任何打包方**都是必须的，见 §3.3。

`KLIBLIST`（v6 起）：每行一个 klib 路径；空行、`#` 开头的行，以及 Kotlin/Native dist 自带的 klib（路径含
`/klib/common/` 或 `/klib/platform/`）会被跳过，其余全部作为 `-l` 传入。v5 及以前只认路径含 `/.gradle/caches/` 的行，
所以从工具链包解出来的 Compose klib 会被漏掉。对原来的清单，两种规则选出的 40 行完全相同。
`KLIB_AUTO_CACHE_FROM`：冒号分隔的目录，每个变成一条 `-Xauto-cache-from`，默认 `~/.gradle/caches`。
`UIKIT_UTILS`：被 `-force_load` 的 `libCMPUIKitObjcUtils.a`，默认 `tools/` 下那份。
`run.sh`/`startup-test.sh` 的 `ORC_RUNTIME`：`liborc_rt_iossim_arm64.a` 的路径，默认 `tools/` 下那份。

`reload` 的符号检查：对每个被改模块，合并对象里必须保留该模块的全部定义符号，
否则打印 `symbol check failed for <module>; not sending` 并以 1 退出。

## 6. 运行时协议与约束

- 端口默认 **5567**（TCP，一次连接一个请求），可用 `KONAN_HOT_RELOAD_PORT` 覆盖；
  app 侧的环境变量是 `SIMCTL_CHILD_KONAN_HOT_RELOAD_PORT`。
- 请求：`u32 对象数`，随后每个对象 `u32 路径长度 + 路径`。
- 一次请求里的对象装入**同一个 JITDylib**，失败整体回滚（原子）。
- 多模块必须先把本次改动的对象 `ld -r` 合并成一个再发送；分开发送会静默失败。
- 发送前每个改动模块的符号数必须 > 0，否则不发送。

### 6.1 首帧就绪标记（工具链 .11 起，Issue #60）

热重载构建的 app 画完第一帧后，往 stdout 打一行（模拟器上经 `simctl launch --console-pty` 进启动日志）：

```
HR READY first-frame epochMs=1795240093197
```

- **原文**：行首固定是 `HR READY first-frame`，后面是一个空格和若干 `key=value` 字段。现在只有 `epochMs`：打印时的主机时钟（`CLOCK_REALTIME`），单位毫秒，取整数。以后可能在后面追加字段，所以消费方只匹配行首：去掉 `\r` 后用正则 `^HR READY first-frame( |$)`。
- **在哪一层打印**：Compose 补丁里的 `ui` 模块（iosMain）。两种 Metal 渲染器都接了：`LegacyMetalRedrawer.render` 和 `SurfaceMetalRedrawer.renderAndPresentFrame`，在一帧真正提交给 Metal（`commandBuffer.commit()` 之后）时调用 `hotReloadFrameCommitted()`。
  - 在提交这一帧的线程上打印：主线程，或者 Compose 的渲染队列。
  - 同一进程只打一次，不管有几个窗口或 `ComposeUIViewController`。“已打印”的标志放在进程环境变量 `KONAN_HR_READY_MARKER_SEEN` 里，不新增任何全局状态（原因见下一条）。同一个渲染器的帧是依次提交的，所以实际上只会打一次；万一两个窗口同时出第一帧打了两行，消费方本来就只认第一行。
  - 因尺寸为 0、拿不到 drawable 或 surface 而跳过的帧不算。
  - 与 `KONAN_HR_FORCE_RECOMPOSE` 无关。
- **只在热重载构建里出现**：
  - 调用点写成 `if (isHotReloadBinary()) hotReloadFrameCommitted()`，函数里再检查一次 `Platform.isDebugBinary`。
  - `kotlin.native.runtime.isHotReloadBinary()` 是 .11 新增的顶层函数，带 `@NativeRuntimeApi`，KDoc 写明只给热重载工具用。它直接调用 runtime，返回编译器在 split 编译时写入的 `Kotlin_hotReload` 常量。
  - 同样的值也通过 `kotlin.native.runtime.HotReload.isEnabled`（同样带 `@NativeRuntimeApi`）暴露出来。
  - 调用点用顶层函数而不用 `HotReload.isEnabled`，是因为读对象属性要先做对象初始化检查。release 优化后这段检查代码仍然留在渲染函数里，每处约 156 字节；顶层函数会被折叠成常量 false，整个调用都会消失。
  - 新代码也不能用 ObjC 类（否则会改变 class reference 的顺序），也不能新增顶层属性或对象（每个都会让所有二进制的全局初始化多一条存储记录）。

  release 构建、优化构建，以及不走 split 的普通 debug 构建都不打印。release 的代码和 .10 完全相同：`__text` 摘要、段大小、符号数都一致，证据见 `example-gradle/accept-toolchain-11.txt`。
- **关掉**：在 app 的环境变量里设 `KONAN_HR_READY_MARKER=0`（模拟器上加 `SIMCTL_CHILD_` 前缀；参考 `run.sh` 会透传这个变量）。只用来测试消费方的回退路径。
- **不会出现的情况**：app 不用 Compose（纯 UIKit）；第一帧之前就崩溃；工具链是 .10 或更早。
- **怎么判断工具链支持**：清单 `toolchain.json` 带 `runtime.readyMarker` = `"HR READY first-frame"`。
  - `gen-toolchain.sh` 只在 `KLIBLIST` 里那份 Compose `ui` klib 真的包含这行字时才写这个字段，所以给旧工具链重新生成清单不会误报。
  - `fetch-toolchain.py` 从 bundle 清单原样取这个字段。
  - 没有这个字段，就表示工具链不会打印这个标记。

**消费方规则**（`hotreload.sh warm` 和插件的 Launcher 都照此执行）：
1. 只看本次 `simctl launch` 之后的日志。`run.sh` 每次重试前都会清空日志。
2. 清单里没有 `runtime.readyMarker`，或者设了 `KONAN_HR_READY_MARKER=0`：保持现在的判定，不变。
3. 清单里有这个字段：看到标记行，就表示第一帧已经提交。
   - `hotreload.sh warm` 的判定：
     - 条件是“标记行 + `EH OK` + `count=<N> page=<P>`”三者都在。后两行在第一帧之前就打印了，所以看到标记时它们必须已经在日志里；不在，就是这次温重启失败。
     - 如果 app 已经恢复（`EH OK` + 状态行都在），但过了 `HR_READY_FALLBACK_S` 秒（默认 5）还没出现标记，就按旧规则判定，并往 stderr 打一行说明。纯 UIKit 的 app 就不会因此卡到 90 秒超时。
     - `WARM OK` 行末尾标出走的是哪条路：`ready=marker|state|fallback`。
   - 插件：用户没配 `readyMarker` 时，看到标记就判定就绪；但旧规则（`calling Konan_start` 之后活够 3 s）先满足时也算就绪，所以任何 app 都不会比现在慢。纯 UIKit 的 app 和第一帧特别慢的 app 也是走旧规则。用户自己配了 `readyMarker` 时，以用户的为准。`readyTokens` 在判定就绪的那一刻核对。
4. 进程在标记之前退出，就是启动失败，和现在一样。
5. `epochMs` 减去 `simctl launch` 的开始时间，就是“启动 → 第一帧”，可以作为诊断数字打印出来。

## 7. 环境开关

| 变量 | 默认 | 作用 |
|---|---|---|
| `KALDO_SYNTH_EH_FRAME` | 关（runtime 本身） | 启动时由 compact unwind 合成并注册 `__eh_frame`；**打包方必须开**（§3.3 第 2 条）。v5 起按值判断：未设、空串、`0` 为关，其他非空值为开（v4 及以前只看是否存在，`0` 也算开） |
| `KALDO_KEEP_COMPACT_UNWIND` | 关（`merge-payloads.py` 本身）；`hotreload.env` 开 | 构建时保留 `__LD,__compact_unwind`，供上一条合成。**打包方必须开**（§3.3 第 1 条）；不开时异常穿过 JIT 代码无法展开。B7 起整段注册，12.6 万条 FDE 只多约 0.7 s 启动 |
| `KONAN_HOT_RELOAD_CACHE_ONLY` | 关 | 编译时在缓存构建后返回，跳过 host 生成与链接；守护进程默认开 |
| `KALDO_HOT_SYMBOLS` | 未设 | 启动时的热符号清单；未设 = 全部 Kotlin 符号外部化（.5 及以前启动约 20% 概率 SIGSEGV；.6 起已修）。**打包方应传收窄清单**（§3.3 第 3 条） |
| `KONAN_HR_FORCE_RECOMPOSE` | 开（工具链 .6 起） | app 的环境变量（模拟器上加 `SIMCTL_CHILD_` 前缀；参考 `run.sh` 透传 `KONAN_HR_FORCE_RECOMPOSE`，默认 `1`）。开：每次 reload 成功后在主线程把所有 Compose 组合整树失效，下一帧完整重组，静态参数的调用点也执行新代码，`remember`/`rememberSaveable` 保留；为此每个场景组合在首次组合时调用 `collectParameterInformation()`。值为 `0` 时两者都关，恢复 .5 的行为（LIMITATIONS 第 11 条）。只在 debug 二进制里生效。日志行前缀 `HR forceRecompose`（TECHNICAL-NOTES §1.6）。插件以后在 DSL 里开放，0.2.0 不含 |
| `KONAN_HR_RELOAD_ON_MAIN` | 开（工具链 .8 起） | app 的环境变量（模拟器上加 `SIMCTL_CHILD_`）。开：Kotlin/Native runtime 把每次 reload（停线程、换代码、迁移实例、调成功回调）放到主队列上执行，也就是两次主 run loop 之间，不会落在一帧的中途；Compose 的强制失效随后就在主线程上做完（日志 `via=reload`），不会有一帧用迁移过来的旧重启 lambda 只重组某个组件（Issue #27，REPORT §34）。替换期间其他线程（`Dispatchers.Default`/IO 上的协程等）照样被暂停，stop-the-world 不变。每次 reload 打一行 `HR reload: ran on the main queue requestEpochMs=… waitMs=… runMs=…`（等主队列的时间和 reload 本身的时间）。主队列在 `KONAN_HR_MAIN_WAIT_MS`（默认 2000）毫秒内没接手（主线程卡住或正在长时间计算）时**不应用补丁**，打印以 `HR reload: NOT applied:` 开头的一行并取消这次任务（主线程之后再空出来也不会执行它），app 继续运行；发送方应把这一行当作失败显示（例如 “main thread busy > 2 s, patch not applied, retry”），下一次热重载照常发送。值为 `0` 时在监听线程上执行（.7 的行为，只用于对照），打印 `HR reload: ran on the listener thread …` |
| `KONAN_HR_READY_MARKER` | 开（工具链 .11 起） | app 的环境变量（模拟器上加 `SIMCTL_CHILD_` 前缀；参考 `run.sh` 透传）。值为 `0` 时不打印首帧就绪标记 `HR READY first-frame`（§6.1），只用来测试消费方的回退；`hotreload.sh warm` 看到这个值也改用旧规则。其他值或不设时打印（只在热重载构建里） |
| `HR_READY_MARKER` | 取清单的 `runtime.readyMarker` | `hotreload.sh warm` 的环境变量。用来覆盖清单里的标记原文：设成空串就用旧规则，只看 `EH OK` 和恢复的状态（§6.1）。主要用于测试 |
| `HR_READY_FALLBACK_S` | 5 | `hotreload.sh warm` 的环境变量。清单声明了标记、但 app 恢复（`EH OK` + 状态行）之后过了这么多秒还没出现标记时，按旧规则判定（`ready=fallback`，§6.1） |
| `HR_AX_HINT` | 开 | `hotreload.sh warm` 的环境变量。值为 `0` 时不读取模拟器的无障碍开关，也不打 `hint:` 行。这个检查只读，不改设置 |
| `KONAN_HR_MAIN_WAIT_MS` | 2000 | 上一条等主队列接手的最长毫秒数，超时则这次补丁不应用 |
| `HR_STABLE_KEYS` | 开（工具链 .9 起） | 编译端（`hotreload.sh`）的环境变量。开：工具链里的 Compose 编译器认得 `HotReloadStableKeys` 这个 feature flag 时，热重载构建的每次编译都带上 `-P plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=HotReloadStableKeys`：composable 的 group key 不含参数表，改签名时 `remember` 状态保留；`remember` 的类型、个数或顺序一变就整组重置（TECHNICAL-NOTES §1.9，REPORT §36）。值为 `0` 时不传（.8 的行为）。同一个 app 的所有编译（打包、热重载、分类器）必须用同一设置；插件在热重载构建里传同一个选项，release 构建不传 |
| `KALDO_PERF_MAP` | 关 | 写 `/tmp/perf-<pid>.map`，给 lldb 符号化 |
| `KALDO_RELOAD_TIMING` | 关 | app 的环境变量（模拟器上加 `SIMCTL_CHILD_`）。值为 `1` 时 libKaldo 每次 reload 打一行 `[kaldo] :: reload-timing jd=… linkorder=… pruned=… setup_us=… add_us=… stubs_us=… redirect_us=… total_us=…`（调试用；`redirect_us` 含补丁的 JIT 链接）。工具链 .8 起（LLVM 补丁 `3e0dafa3…`） |
| `KALDO_KEEP_RELOAD_GENERATORS` | 关 | 值为 `1` 时不摘除旧 `reload<N>` JITDylib 上的宿主符号生成器，恢复修复前的行为（链接耗时随 reload 次数线性增长，LIMITATIONS 第 7 条），只用于对照测量。工具链 .8 起 |
| `KALDO_DEBUG_SELREFS` | 关 | 只有值为 `1` 时，才把 selector 区间里补登记的 selector 追加到 `/tmp/gap-selrefs.log`（调试用）。v8 起默认不写；v7 及以前每次启动都写 |
| `KONAN_ORC_RUNTIME_PATH` | 无 | `liborc_rt_*.a` 路径；模拟器启动时需要 `SIMCTL_CHILD_` 前缀 |
| `KALDO_GUEST_KEEP_OBJC` | 关 | 保留 guest 的 ObjC 成员（默认丢 host 已定义的那些） |
| `KALDO_GUEST_DROP_CXX` | 关 | 丢弃纯 C++ 成员 |

## 8. 变更记录

| interfaceVersion | 日期 | 变更 |
|---|---|---|
| 1 | 2026-11-19 | 首版：toolchain.json、两个守护进程、payload 工具、发送协议、环境开关 |
| 2 | 2026-11-19 | 工具链身份改为「公开基线 + `patchSha256`」（kotlin/llvm/compose）；`kotlin.baseCommit` 由本地 `eb20031f740` 改为公开 `8e53ee99fbc0`；`reload.py` 支持 `KONAN_HOT_RELOAD_PORT`（默认 5567），`run.sh` 透传；`runtime` 增加 `reloadPortEnv`。兼容：旧消费者仍按 5567 工作，新增字段可忽略 |
| 3 | 2026-11-19 | 补登 B7（`d42e1fb`）引入的打包方义务，集中写成 §3.3：`KALDO_KEEP_COMPACT_UNWIND=1`、`KALDO_SYNTH_EH_FRAME=1`、收窄 `KALDO_HOT_SYMBOLS`、删除绝对 rpath、ad-hoc 重签（B7 当时只写在 §5 参考驱动里，漏了升版本）。`toolchain.json` 新增 `llvm.kaldoIossimSha256`（实际库文件的 sha256），`llvm.patchSha256` 随 B7 补丁变为 `e138fd16…`。不兼容点：只做 v2 的打包方若不补 §3.3，app 无法启动 |
| 4 | 2026-11-19 | libKaldo 改装到项目内独立目录 `<KALDO_INSTALL>/lib/`（iossim 与 macOS 两份），`<YOUR_LLVM_INSTALL>/lib/` 里放符号链接供编译器 `-L<llvmHome>/lib` 链接；不再往 `~/.konan/dependencies` 放文件。`gen-toolchain.sh` 的 `KALDO_DYLIB` 改为必填；`hotreload.sh package` 只认 `KALDO_DYLIB` 或清单 `llvm.kaldoIossim`，并校验 `kaldoIossimSha256`；`llvm.kaldoIossim` 的值随之变为 `<KALDO_INSTALL>/lib/libKaldo.iossim.dylib`（字段格式不变，sha256 不变 `f4720899…`）。参考 app 增加 EH 回归探针（§3.3）。不兼容点：依赖旧默认路径、不传 `KALDO_DYLIB` 又没有清单的调用会直接失败 |
| 5 | 2026-11-19 | 编译器直接从 `<KALDO_INSTALL>/lib` 链接 libKaldo：Kotlin 补丁里 `konan.properties` 的 `kaldoLinkerFlags.*` 改为 `-L<KALDO_INSTALL>/lib …`（macOS 另加 `-rpath <KALDO_INSTALL>/lib`），LLVM 安装目录里不再放 libKaldo（v4 的符号链接取消）。`KALDO_SYNTH_EH_FRAME` 改为按值判断（未设/空/`0` 为关）。`kotlin.patchSha256`、`llvm.patchSha256`、`llvm.kaldoIossimSha256` 随之变化，字段格式不变。不兼容点：沿用 v4 dist 的 `konan.properties`（`-lKaldo.iossim` 无 `-L`）且 LLVM 安装目录已无 libKaldo 时链接失败；依赖 `KALDO_SYNTH_EH_FRAME=0` 仍开启的行为会变成关闭 |
| 6 | 2026-11-19 | 分发（B6）：`toolchain.json` 增加 `layout`（`local`/`bundle`）；bundle 清单带 `toolchainVersion`、`components`（文件、字节数、sha256、解包目录、`release` 或 `maven` 下载信息）、`tools`（`llvmTools`/`orcRuntime`/`uikitUtils`）和 `llvm.llvmHome`（`konan-dependency` + 依赖名）。新脚本 `gen-bundle-manifest.py`、`publish-toolchain.py`（只有 dry-run 和本地仓库两种模式）、`fetch-toolchain.py`，见 §9。`hotreload.sh` 的 `KLIBLIST` 规则通用化，新增 `KLIB_AUTO_CACHE_FROM`、`UIKIT_UTILS`；`run.sh` 新增 `ORC_RUNTIME`。兼容：local 清单只多一个 `layout` 字段；旧的 `KLIBLIST` 选出的 klib 不变 |
| 7 | 2026-11-19 | B4：新增 `scripts/compat-classify.py`（`snapshot`/`classify`，退出码 0/3/2）；`hotreload.sh reload` 先分类，`reload --no-warm` 只拦截不发送，新增 `warm` 子命令；参考 app 支持 `--hr-state=page,count` 与 `Documents/hr-state.json`，见 §10。只新增工具、参数和退出码，已有命令与清单格式不变 |
| 8 | 2026-11-19 | B10：分类器提速。守护进程新增 `@klib` 工具请求（§2.1）；`compat-classify.py` 新增 `baseline`/`check` 子命令和 `--daemon`、`--cache-dir`，快照增加 `klibSha256`（§10.1）；`reload` 的分类改为与 cache-only 编译并行、发送前等结论，app library klib 走 `hr-klib.sock`（§10.2）。**行为变化**：`hr-compile.sock` 有默认路径 `$HRC_SOCKET_DIR/hr-compile.sock`（`HRC_SOCKET_DIR` 默认 `<工程>/.hr`），存在就用，不再要求显式导出 `HRC_SOCKET`（§2.3）；`reload --no-warm` 在退出 3 前会先完成编译（仍不发送）。`merge-payloads.py` 在任何退出路径上删除工作目录，`KALDO_MERGE_KEEP=1` 保留（§3.1）。`fetch-toolchain.py` 接受 interfaceVersion 6–8 的 bundle 清单（v7 时它仍只认 6，下载 v7 清单会被拒，这是 v7 的遗漏）。清单字段不变。B11：libKaldo 只在 `KALDO_DEBUG_SELREFS=1` 时写 `/tmp/gap-selrefs.log`（§7；以前每次启动都写，首发前必修），`llvm.patchSha256` 变为 `3bb8a987…`、`llvm.kaldoIossimSha256` 变为 `8ed50386…`（macOS 版 libKaldo 同步重建），字段格式不变。公开 klib（v8，0.1.0）：bundle 清单新增 `externalKlibs`、`bundledKlibs`（§9.2），新增组件 `bundled-klibs`，新脚本 `gen-external-klibs.py`；`fetch-toolchain.py` 下载并校验公开 klib，不再读 Gradle 缓存，`--auto-cache-from` 默认为空；`compose-compiler-plugin` 改为 Release 附件；每个组件附 LICENSE 和 NOTICE；fetch 生成的 local 清单带 `tools`（绝对路径）。首发后（不升版本，只新增可选项）：`fetch-toolchain.py` 下载重试；温重启提速——`merge-payloads.py` 新增可选 `KALDO_MERGE_CACHE_DIR`（§3.1），`hotreload.sh package` 的编译走 `hr-klib.sock`、打印 `PACKAGE ms=…` 诊断行（§5、§10.2）；`ComposableSingletons`/`object` 的行为写进 TECHNICAL-NOTES §1.6 并加回归；Kotlin Multiplatform：`hotreload.sh` 的可选源码集参数 `UI_FRAGMENTS`/`APP_FRAGMENTS` 等，`reload` 按模块分 app/ui，`ui-klib` 守护进程失败时回退（§5，TECHNICAL-NOTES §11）。工具链 `2.5.255-hotreload.5`（不升接口）：编译器在 split 编译下于启动时绑定 ObjCExport 的 WritableTypeInfo 字段（TECHNICAL-NOTES §1.8），`kotlin.patchSha256` 变为 `40c81628…`，其余组件和身份字段与 .4 相同。工具链 `2.5.255-hotreload.6`（不升接口，只新增可选开关）：reload 成功后强制完整重组（Compose 补丁里 `ui` 模块的 `HotReloadRecompose.ios.kt`，TECHNICAL-NOTES §1.6），新环境开关 `KONAN_HR_FORCE_RECOMPOSE`（默认开，§7）；LLVM 补丁修启动时 `simplifyDepGroups` 的越界读（LIMITATIONS 第 6 条）。`compose.patchSha256`、`llvm.patchSha256`、`llvm.kaldoIossimSha256` 随之变化，字段格式不变；compose-klibs 组件只有 `ui` 一个 klib 变化。公开 klib（`externalKlibs`）改为 Compose 构建实际解析到的那组：补上缺的 `navigationevent-compose`（androidx 1.1.1 和 org.jetbrains 1.1.0）、`lifecycle-viewmodel-compose` 2.11.0、`graphics-shapes` 1.1.0，并把 collection 1.5.0→1.6.0、lifecycle 2.9.4→2.11.0、navigationevent 1.0.1→1.1.1、savedstate 1.3.3→1.5.0、atomicfu 0.27.0→0.33.0 对齐（.5 缺这几个，`Dialog`、`BackHandler` 运行时 `IrLinkageError`，LIMITATIONS 第 18 条）；`pack-toolchain.sh` 打包前用 `check-klib-deps.py` 检查 klib 集合闭合。工具链 `2.5.255-hotreload.7`（不升接口）：Compose 基线改为官方标签 `v1.13.0-rc01`（`compose.version` `1.13.0-rc01`，`compose.baseCommit` `f39822e6a77e…`），补丁不变（`compose.patchSha256` 仍是 `335d7a32…`）；`compose-klibs` 19 个模块全部重编（公开 ABI 与官方 rc01 klib 相同），`cmp-uikit-utils` 用 rc01 的源码重编；公开 klib 跟随 rc01：Compose runtime 1.13.0-rc01、skiko 0.156.1、atomicfu 0.28.0。skiko 0.156.1 在 Maven Central 上，所以 `bundledKlibs` 为空，不再有 `bundled-klibs` 组件（字段和组件格式不变，`fetch-toolchain.py` 本来就允许它为空）。工具链 `2.5.255-hotreload.8`（不升接口，只新增可选开关和日志行）：libKaldo 每次 reload 摘掉上一层 JITDylib 的宿主符号生成器，加载耗时不再随 reload 次数增长（Issue #5，`llvm.patchSha256` `3e0dafa3…`、`llvm.kaldoIossimSha256` `6810d16b…`）；Kotlin/Native runtime 把 reload 放到主队列上、两帧之间执行，2 s 内主线程没接手就不应用补丁并打印 `HR reload: NOT applied: …`，每次 reload 打印 `HR reload: ran on the main queue requestEpochMs=… waitMs=… runMs=…`，新环境开关 `KONAN_HR_RELOAD_ON_MAIN`、`KONAN_HR_MAIN_WAIT_MS`（§7，Issue #27，`kotlin.patchSha256` `4f560d86…`）；Compose `ui` 在回调里当场失效，`HR forceRecompose` 日志行在 `deferred=` 后多一个 `via=reload|frame|task` 字段（`compose.patchSha256` `42e1eba6…`，compose-klibs 只有 `ui` 变化）。发送方应把 `HR reload: NOT applied` 行显示为这次热重载失败。工具链 `2.5.255-hotreload.9`（不升接口，只新增可选项）：Compose 编译器插件新增 feature flag `HotReloadStableKeys`，热重载构建传 `-P plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=HotReloadStableKeys` 后改签名时 `remember` 状态保留，`remember` 的类型、个数或顺序一变则整组安全重置（Issue #29，§7 `HR_STABLE_KEYS`，release 不传）；Kotlin/Native 编译器新增 binary option `splitPayloadList`，`hotreload.sh package` 用它让 payload 不再经过 LLVM 嵌进 kexe，`merge-payloads.py` 新增可选 `KALDO_PAYLOAD_LIST`（§3.1，Issue #32）；`kotlin.patchSha256` 变为 `a34772eb…`，compose、llvm 补丁和 libKaldo 不变。工具链 `2.5.255-hotreload.10`（不升接口，只新增可选项）：libKaldo 能加载多条 bootstrap payload（每条一个 buffer 名），并新增清单种类 2（条目内容是对象文件的绝对路径，启动时从文件读入，§3.2）；编译器新增 binary option `splitPayloadReserve`；`merge-payloads.py` 新增 `KALDO_LIB_PAYLOAD_DIRS`（库对象缓存成第二条 payload，§3.1），`embed-merged.py` 接受多个对象和 `@file:` 条目（三个参数时不变）；`llvm.patchSha256` `81fa2129…`、`llvm.kaldoIossimSha256` `6340abfe…`、`kotlin.patchSha256` `3fcf2ab7…`，compose 补丁不变（Issue #32、#53）。种类 2 的清单要 .10 起的 libKaldo。工具链 `2.5.255-hotreload.11`（不升接口，只新增可选项）：新增首帧就绪标记 `HR READY first-frame`（§6.1），清单字段 `runtime.readyMarker`（`gen-toolchain.sh` 检测 ui klib 后才写，`fetch-toolchain.py` 从 bundle 取），环境开关 `KONAN_HR_READY_MARKER`、`HR_READY_FALLBACK_S`（§7）。Kotlin runtime 新增 `kotlin.native.runtime.isHotReloadBinary()` 和 `HotReload.isEnabled`，都带 `@NativeRuntimeApi`（`kotlin.patchSha256` `1ccbcca1…`）；Compose `ui` 的两种 Metal 渲染器各加一个调用点（`compose.patchSha256` `8f9229c0…`，compose-klibs 只有 `ui` 变化）；llvm 补丁和 libKaldo 不变。release 代码与 .10 相同（`__text` 摘要、段大小、符号数）。`hotreload.sh warm` 已经支持这个标记，并新增了只读的无障碍提示，以及开关 `HR_READY_MARKER`、`HR_AX_HINT`（§5、§7）。`WARM OK` 行末尾多了一个 `ready=state|marker|fallback` 字段（Issue #60） |

## 9. 分发：打包 → 发布 → 下载（v6）

```
pack-toolchain.sh ──> <OUT_DIR>/*.tar.zst + packaging-manifest.json (schema 3)
gen-bundle-manifest.py ──> toolchain.json (layout "bundle", 发布用)
publish-toolchain.py ──> dry-run 命令清单，或 --local-repo 本地镜像 + file:// 清单
fetch-toolchain.py ──> <cache>/<toolchainVersion>/ 解包 + local toolchain.json + env.sh
```

### 9.1 组件

| id | 解包目录 | 渠道 | 内容 |
|---|---|---|---|
| `kotlin-native` | `kotlin-native` | release | Kotlin/Native 最小子集；`konan.properties` 里是占位符 `<YOUR_LLVM_INSTALL>`、`<KALDO_INSTALL>` |
| `compose-klibs` | `compose-klibs` | release | Compose fork 的 klib（每个子目录一个） |
| `compose-compiler-plugin` | `compose-plugin` | release（0.1.0 起；以前是 maven） | Compose 编译器插件 jar |
| `kaldo-iossim` / `kaldo-macos` | `kaldo`（都解到 `kaldo/lib/`） | release | libKaldo 两份 |
| `orc-runtime` | `orc-runtime` | release | `liborc_rt_iossim_arm64.a` |
| `cmp-uikit-utils` | `cmp-uikit-utils` | release | `libCMPUIKitObjcUtils.a` |
| `llvm-tools` | `llvm-tools` | release | `bin/llvm-objcopy`、`bin/llvm-nm`（必须只链接系统库） |
| `bundled-klibs` | `bundled-klibs` | release | v8，可选：公开 Maven 仓库里找不到的公开 klib，原样放入（工具链 .4–.6 是 skiko `0.157.0-alpha01` 的两个 klib；.7 没有这个组件） |

每个组件里都有 `licenses-compose-ios-hot-reload/LICENSE` 和 `NOTICE`。打包时每个组件都要通过二进制扫描（`scripts/scan-binary-leaks.py`：用户目录路径、本机家目录、内部名称、本机 deny 文件里的人名和主机名，zip 成员也扫），不通过就不打包（见 PACKAGING §1）。

文件名：`<id>-<toolchainVersion>[-macos-aarch64].tar.zst`；Release tag：`toolchain-<toolchainVersion>`；
Maven：`com.solufee.cmp.toolchain:<id>:<toolchainVersion>@tar.zst`。重新打包一定换修订号，不能覆盖同名资产。

### 9.2 bundle 清单（`toolchain.json`，layout "bundle"）

在 local 清单的字段之外：
- `toolchainVersion`；
- `components[]`：`id`、`file`、`extractDir`、`bytes`、`rawBytes`、`sha256`、`download`。
  `download` 有两种：`{type:"release", repo, tag, asset, url}` 或 `{type:"maven", repository, coordinate, path, url}`；
- `tools`：`llvmTools`、`orcRuntime`、`uikitUtils`（相对路径）；
- `externalKlibs[]`（v8）：fetch 时从原始仓库下载的公开 klib，每条 `coordinate`（`group:artifact:version[:classifier]`）、`repository`（`maven-central` / `google`）、`url`、`file`、`sha256`、`bytes`、`license`（取自 POM）。由 `gen-external-klibs.py` 生成：它读 Gradle 模块元数据（`.module`）里同名文件的真实 `url` 和发布的 sha256，下载后要求本机缓存文件、下载文件、发布值三者一致；
- `bundledKlibs[]`（v8）：两个仓库都没有、因而随 `bundled-klibs` 组件分发的 klib，每条 `coordinate`、`file`、`path`（相对工具链根目录）、`sha256`、`bytes`、`license`；
- `llvm.kaldoHome`、`llvm.kaldoIossim`（相对路径）、`llvm.kaldoIossimSha256`；
- `llvm.llvmHome`：`{kind:"konan-dependency", name:"llvm-21-aarch64-macos-essentials-97"}`，表示用 Kotlin/Native
  官方 LLVM 依赖做 `llvmHome`（由 Kotlin/Native 自己下载到 `~/.konan/dependencies`）。

所有路径都是相对路径。`gen-bundle-manifest.py` 遇到本机用户目录下的绝对路径或 `file://` 就拒绝写出；打包时的 libKaldo
sha256 与身份清单的 `kaldoIossimSha256` 不一致也拒绝。

### 9.3 发布（`publish-toolchain.py`）

```
publish-toolchain.py --dist-dir DIR --manifest toolchain.json                 # dry-run（默认）
publish-toolchain.py --dist-dir DIR --manifest toolchain.json --local-repo R   # 本地镜像
```

- 两种模式都会先校验：清单版本和 layout；每个文件存在，字节数、sha256 与清单一致；Release 附件小于 2 GiB；
  清单里没有本机路径。任何一项不过就退出 1。
- dry-run 只打印真正发布时要执行的命令（`gh release create … --draft`、`gh release upload …`、往 GitHub Packages
  PUT 的 `curl`），一条都不执行。
- `--local-repo`：按远端的目录结构复制一份（`releases/<owner>/<repo>/download/<tag>/…`、`maven/<group path>/…`，
  另生成最小 `.pom`），再写出 `toolchain-<version>.json`，其中每个 `download.url` 都改成 `file://`。
- `publish-toolchain.py` 只有显式加 `--publish --confirm-repo <owner/repo>` 才会上传：建 draft release，上传全部组件和清单；公开要另外执行 `gh release edit <tag> --draft=false`。0.1.0 起全部组件都是 Release 附件（`gen-bundle-manifest.py --channel release`），公开仓库下载不需要 token。

### 9.4 下载与本地化（`fetch-toolchain.py`，参考消费端）

```
fetch-toolchain.py MANIFEST --cache DIR [--llvm-home official|PATH] [--extra-klibs FILE] [--auto-cache-from DIRS] [--dry-run]
```

需要能访问 `github.com`（Release）、`repo1.maven.org`、`dl.google.com`（公开 klib）。https 下载用 `curl`（失败重试、断点续传）。

```
```

- 逐个组件下载，校验字节数和 sha256。不一致就**删除文件、退出 3**，不回落到未校验的文件。校验通过后用 `zstd -dc | tar -x`
  解到 `<cache>/<version>/<extractDir>`，并在 `.fetched/<id>` 记下 sha256；之后再运行，已经校验过的组件会跳过。
- 本地化：`konan.properties` 里的 `<KALDO_INSTALL>` 换成 `<cache>/<version>/kaldo`；`llvmHome.macos_arm64` 默认写成
  `$llvm.macos_arm64.user`（官方依赖），也可以用 `--llvm-home` 指定路径。原始文件保存在 `konan.properties.bundle`，
  每次都从这份重新生成。然后核对 libKaldo 的 sha256；按 `externalKlibs` 把公开 klib 下载到
  `<cache>/<version>/external-klibs/<group>/<artifact>/<version>/`（字节数、sha256 不对就删除、退出 3），核对 `bundledKlibs` 的 sha256；
  生成 `klibs.txt`（Compose klib + 公开 klib + 可选的 `--extra-klibs`，**不读任何 Gradle 缓存**）、local 清单 `$KOTLIN_DIST/toolchain.json`（也就是 `<cache>/<version>/kotlin-native/toolchain.json`，`hotreload.sh` 就在这里找；基线和补丁哈希取自发布清单）和 `env.sh`。
- local 清单（v8）带 `tools`：`llvmTools`（目录，含 `bin/llvm-objcopy`、`bin/llvm-nm`）、`orcRuntime`、`uikitUtils`，都是**绝对路径**，和 local 清单里其它路径一样；插件只配 `toolchainDir` 就能找到它们。
- `env.sh` 导出：`KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`KLIB_AUTO_CACHE_FROM`、`KONAN_LLVM_HOME`、
  `ORC_RUNTIME`、`UIKIT_UTILS`，source 之后可以直接用 `hotreload.sh` 和 `run.sh`。
- 退出码：0 成功；1 其他错误（只打印一行原因；设 `FETCH_DEBUG=1` 才显示完整堆栈）；2 清单或参数不对（包括清单不是有效 JSON）；3 字节数或 sha256 不一致；4 缺 `zstd` 或解包失败；5 下载失败。
- 重试：清单和 GitHub API 请求最多尝试 `FETCH_RETRIES` 次（默认 4），间隔按指数增长（`FETCH_BACKOFF`，默认 1 s：1、2、4 s）。传输中断、超时、连接错误和 HTTP 408/429/5xx 会重试，404 等不会。每次重试和最终失败都打印中英文短提示，不打印 Python 堆栈。组件和公开 klib 用 `curl` 下载（自带重试和断点续传）。测试：`scripts/test-fetch-toolchain.py`。
- 解包（Issue #31）：同一版本同时只有一个 fetch 在工作（`<cache>/<version>/.fetch.lock`，后来的等前一个结束，再看到组件已完成就跳过）；组件先解到目标旁边的新临时目录，再逐个文件移进目标目录（几个组件共用一个目录时互不覆盖），目标里不会出现写了一半的文件；解包失败重试一次（`FETCH_EXTRACT_ATTEMPTS`，默认 2），报错带 zstd/tar 的原文，已校验的下载文件留在 `.download`，不标记完成，重新运行会再校验后直接解包。上次中断留下的临时目录在加锁后清掉。
- 公开仓库的 Release 附件和公开 klib 都不需要 token。设了 `$GITHUB_TOKEN` 时，Release 附件会带上它（私有仓库通过 GitHub API 按 tag 查 asset id）。
  Release 走 https 这条路在首次公开发布时第一次实测；公开 klib 的 https 下载已经实测（Maven Central、Google Maven）。
- `KLIB_AUTO_CACHE_FROM` 只包含工具链自己的 klib 目录（`compose-klibs`、`external-klibs`、`bundled-klibs`），`--auto-cache-from` 默认为空（v7 及以前默认是 `~/.gradle/caches`）。
- 消费方义务：使用前必须按清单校验 sha256；校验失败不得使用；不得回落到其他位置的同名文件。

## 10. 兼容性分类器与温重启（v7；v8 提速）

### 10.1 `compat-classify.py`

```
compat-classify.py snapshot --klib <klib> --out <json> [--klib-tool <klib-tool>] [--daemon <sock>] [--cache-dir <dir>]
compat-classify.py classify --old <json> --new <json> [--json] [--force-recompose]
compat-classify.py baseline --baseline-dir <dir> --module <name>=<klib> ... [--klib-tool ..] [--daemon ..] [--cache-dir ..]   # v8
compat-classify.py check    --baseline-dir <dir> --module <name>=<klib|-> ... [--klib-tool ..] [--daemon ..] [--cache-dir ..] [--prior-ms <name>=<ms>] [--force-recompose]   # v8
```

v8 新增（`snapshot`/`classify` 的行为和退出码不变）：

- `--daemon <sock>`：dump 走守护进程的 `@klib` 请求（§2.1）；连不上或返回 `ERR` 时打印一行提示并回退到 `klib` 工具。
- `--cache-dir <dir>`：快照按「本脚本的 sha256 前 16 位 + klib 的 sha256」缓存（最多 64 份，按使用时间淘汰）；
  脚本一改，旧缓存自然失效。快照新增字段 `klibSha256`。
- `baseline`：一个进程里给每个模块写 `<dir>/<name>.json`。
- `check`：一个进程里给每个模块写 `<dir>/<name>.new.json` 并和 `<dir>/<name>.json` 比较；输出与退出码同 `classify`
  （多个模块的 WARM 原因合在一起）。`<name>=-` 表示该模块没变、直接沿用基线；klib 的 sha256 与基线里的
  `klibSha256` 相同也视为没变，不再 dump。

`snapshot` 用 Kotlin/Native 的 `klib` 工具跑 `dump-metadata` 和 `dump-ir`，把模块描述写成 JSON：

- `classes`：类（含嵌套/companion）的可见性、超类型、构造函数签名、字段列表（名字、类型、`val`/`var`、可见性、
  **声明顺序**，私有字段也记录）、方法签名（含 `inline`/`suspend` 标记）；
- `topProperties` / `topFunctions`：包级声明（0.2.4 起 `topFunctions` 按 `名字(参数)` 存，重载各占一项；旧脚本写的按名字存的
  基线照常可比）；函数项新增 `composable`（带 `@Composable`）；
- `bodies`：所有 `inline` 和 `suspend` 函数的 IR 正文哈希（用于发现正文改动）；
- `topOther` / `classes[].other`：未识别的成员原文，用于保守拦截。

`classify` 比较两份快照。退出码：**0 可热替换**；**3 需要温重启**；
**2 参数/工具错误**（此时不得发送补丁）。输出每行 `WARM <类别> <符号>: <原因>`，类别固定为：

| 类别 | 触发条件 |
|---|---|
| `field-layout` | 类字段新增/删除/类型变化/顺序变化，或构造函数签名变化 |
| `signature` | 函数签名变化（0.2.4 起只限下文「签名变化」之外的情形）；open/abstract/sealed 类、enum、interface、ObjC 子类（父类型是 `platform/*` 或带 `@ExportObjCClass`）新增方法，或任何类新增 override/open 方法；超类型变化；类的 modality（final/open/abstract/sealed）变化；涉及 `private` 的顶层函数可见性变化；typealias 改指向或删除 |
| `inline` | 已有 inline 函数的正文变化（IR 哈希不同），或其签名变化 |
| `suspend` | 已有 suspend 函数的正文变化，或其签名变化 |
| `top-level-property` | 顶层属性新增/删除/类型或 `val`/`var` 变化 |
| `class-removed` | 类被删除（旧实例可能还活着） |
| `class-shape` | 未识别的类/顶层成员发生变化（保守） |

明确放行（不产生 WARM）：普通函数正文、包级函数新增/删除（composable 增删，包括 composable lambda 的增删）、私有方法改动、
新增类和嵌套类、`const val` 的值变化（初始化值不属于布局）；首发后（0.1.2，不升版本）另外放行：final 类（非 enum、非 ObjC 子类）
和 object/companion 新增 **final** 方法、新增 typealias、顶层函数在 `internal` 与 `public` 之间改可见性（两者符号名相同）。
新加的字段只读新快照，旧版脚本写的基线照常可比，升级后不会多一次温重启。用例表：`gradle-plugin/test-projects/classifier-cases/cases.py`。

**签名变化（0.2.4，Issue #28，依据 REPORT §33）**：同名函数的重载集合变了（加/删参数、改参数类型或默认值、改返回值类型、删掉
某个重载）时，下列情形判 **HOT**，因为增量编译会把所有调用方一起重编进补丁，新代码只引用新符号：

- 顶层函数（含 `expect`/`actual` 函数：klib 元数据里它们就是普通函数）；
- final 类（非 enum、非 ObjC 子类、不带 `@ExportObjCClass`）和 object/companion 的 final 成员（元数据里 override 显示为 `open`，不算）；
  在这类类里整个删掉一个 final 成员也判 HOT；
- `@Composable` 函数：只有带 `--force-recompose`（运行中的 app 开着强制重组，`KONAN_HR_FORCE_RECOMPOSE=1`）时 HOT，
  并输出一行 `NOTE remember-reset <符号>: …`（这个 composable 自己的 remember 重置一次）；不带时判 `WARM signature … (@Composable with forceRecompose off)`，
  因为 slot table 里的重启 lambda 还带着旧参数。

仍为 WARM：`inline`（类别 `inline`）、`suspend`（类别 `suspend`）、open/abstract/override/接口成员、enum 和 ObjC 子类的成员
（类别 `signature`），构造函数（`field-layout`）。私有函数（非 composable）的签名变化照旧不报。

**#20 放宽与收紧（0.2.4）**：顶层 `const val` 的新增和删除不再报（常量没有要初始化的存储，调用方内联取值）；final 类/object/companion
新增的 `inline`、`suspend` 成员不再报（补丁之外没有调用方或挂起中的协程）；open/abstract/sealed 类（非 enum、非 ObjC 子类）新增
final 成员不再报（直接调用，不经虚表）。`@ExportObjCClass` 不记录在 klib 元数据里，分类器看不到它的增删（ObjC 子类的成员改动本来就判 WARM）。
书面评估见 `gradle-plugin/test-projects/classifier-cases/RELAXATIONS.md`。

输出新增一种行 `NOTE <类别> <符号>: <说明>`，只是提示，不影响退出码；`classify --json` 里对应 `notes`。`hotreload.sh` 不传
`--force-recompose`，所以在那里改 composable 签名仍然温重启。

### 10.2 在 `hotreload.sh` 里

- `package` 结束时写基线快照 `<BUILD>/compat/{ui,app}.json`。app 快照来自
  `-p library` 编出的完整 klib（带 IR，`<BUILD>/compat/app.klib`）；只有 app 源文件比
  `<BUILD>/compat/app.stamp` 新时才重建（ui 改动不额外编译 app）。
- `reload` 在**发送之前**得到分类结论（v7 是先分类再编译；v8 起分类在后台与 cache-only 编译同时跑，
  分类用 `hr-klib.sock`、编译用 `hr-compile.sock`，互不排队；发送前等分类结束）：
  - 可热替换：发送；成功后把新快照提交为基线；
  - 需要温重启：打印 `WARM ...` 原因，默认自动转到 `warm`（本次编译结果丢弃）；
  - `reload --no-warm`：只打印原因并退出 **3**，不发送（v8 起编译已经做过，但补丁不发）。
- v8：app 的 library klib 由 `hr-klib.sock` 编译（没有守护进程时才直接起 `kotlinc-native`），不再带
  auto-cache 参数；快照缓存在 `<BUILD>/compat/cache/`。
- `warm`：读取 app 自己持久化的状态 → 重新 `package` → 用启动参数 `--hr-state=page,count`
  重启 app → 等到 `RENDER_APP ... page=<page> count=<count>` 和 `EH OK`，打印
  `WARM OK ms=<总耗时> ms_package=<打包> ms_relaunch=<重启到恢复> page=<page> count=<count>`。
- 分类器退出 2 时 `reload` 也退出 2，补丁不发送。
- `package` 结束时打印 `PACKAGE ms=<合计> ui_klib=<ms> compile=<ms> merge=<ms> embed=<ms> bundle=<ms> baseline=<ms>`（诊断输出，不属于契约）；
  `warm` 把 `run.sh` 的输出写到 `$BUILD/warm-run.out`（以前丢弃），排查重启失败时看它和 `WARM_LOG`。
- 每次分类后 `reload` 在 stdout 打印 `compat: timing ms=<合计> ui_snapshot=<ms> app_snapshot=<ms> classify=<ms> ui=<same|hit|dump> app=<...>`
  和 `compat: wait ms=<发送前为等分类而阻塞的时间>`（v8）。这些是诊断输出，**不属于契约**，格式可能变化，插件不要依赖它们。

### 10.3 参考 app 的状态契约（`--hr-state`）

- 启动参数 `--hr-state=PAGE,COUNT`（逗号分隔，可省略；省略时读状态文件）。
- app 在每次页面/计数器变化时写 `<Documents>/hr-state.json`：`{"page":N,"count":M}`。
- 这是“可序列化的那部分状态”；`remember` 里的其它瞬态（tick 等）不恢复。
- 打包方如果自带 app 壳，要实现同样的参数和文件，`warm` 才能恢复状态；不实现也能温重启，
  但只能回到第 0 页。

