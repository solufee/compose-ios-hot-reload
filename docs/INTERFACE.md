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
    "version": "1.13.0-beta01",
    "pluginJar": "<COMPOSE_PLUGIN_JAR>",
    "klibList": "<KLIBLIST>",
    "baseCommit": "ee243c321ddff6b18fcf18916be1b96ef70e2363",
    "patch": "compose-multiplatform-core-build.patch",
    "patchSha256": "c1de7a107e3f15820ae7a9278e683a672ea5470aa3164861e6e17cd0a9ab2da6"
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
    "mergeChangedObjectsWith": "ld -r"
  }
}
```

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
- 退出码：0 成功；2 参数个数不对；其它非 0 表示链接/复制失败。

- 工作目录 `$TMPDIR/kaldo-merge-*`（约 600 MB）在任何退出路径上都会删除（v8 起 `try/finally`；v7 及以前只在成功时删，失败或被杀会留下）。调试时设 `KALDO_MERGE_KEEP=1` 保留，并在 stderr 打印路径。

### 3.2 `embed-merged.py`

```
embed-merged.py <base.kexe> <merged.o> <out.kexe>
```

- 把合并对象写成单条 `KALD0` manifest，替换 `__TEXT,__kaldo_boot`，输出新的可执行文件。
- 环境：`KONAN_LLVM_HOME`（llvm-objcopy）。
- 退出码：0 成功；2 参数个数不对；非 0 表示 objcopy 失败。

### 3.3 打包方义务（任何打包实现都必须做，v3 起）

不论用 `hotreload.sh`、Gradle 插件还是别的驱动出 `.app`，下面几条都是契约的一部分；漏掉任何一条，
app 会拒绝启动、启动崩溃或异常无法展开。

| # | 要求 | 不做的后果 |
|---|---|---|
| 1 | 调 `merge-payloads.py` 时导出 `KALDO_KEEP_COMPACT_UNWIND=1` | 没有 `__kaldo_cu`，无法合成 eh_frame，异常穿过 JIT 代码时进程终止 |
| 2 | 启动 app 时传 `SIMCTL_CHILD_KALDO_SYNTH_EH_FRAME=1` | 同上（EH probe 或翻页时终止） |
| 3 | 启动 app 时传 `SIMCTL_CHILD_KALDO_HOT_SYMBOLS=<merge-payloads 产出的 hot-symbols.txt>` | 全部符号外部化，冷启动约 20% 在 ORC `simplifyDepGroups` SIGSEGV |
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
| `ui-klib` | 把 ui 源目录编成 `build/ui/ui.klib` | `KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`KLIB_AUTO_CACHE_FROM`、`UIKIT_UTILS`、`BUILD_DIR`、`UI_SRC_DIR`、`HRC_KLIB_SOCKET` |
| `package` | split 编译 app + 合并 payload + 嵌入 guest + 组装 `.app` | 上面的 + `IC_DIR`、`AUTO_CACHE`、`APP_SRC`、`OUT`、`KALDO_HOT_PACKAGES`；libKaldo 取 `KALDO_DYLIB`，未设时取 `$KOTLIN_DIST/toolchain.json` 的 `llvm.kaldoIossim`，都没有就退出 1；与清单 `kaldoIossimSha256` 不一致也退出 1 |
| `reload` | 分类（v7；v8 起与编译**并行**）+ cache-only 编译 → 过滤本次重建的 archive → `nm` 分类 → `ld -r` → 符号检查 → **等分类结论** → 发送；需要温重启时默认自动 `warm`，`--no-warm` 时只拦截并退出 3 | 上面的 + `HRC_SOCKET`、`HRC_SOCKET_DIR`、`COMPAT_DIR`、`COMPAT_CLASSIFIER`、`KLIB_TOOL` |
| `warm` | 读取 app 状态 → `package` → 用 `--hr-state` 重启 → 等 `EH OK` 与目标页/计数器，打印 `WARM OK ms=...` | `UDID`、`BUNDLE`、`WARM_LOG` |

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

## 7. 环境开关

| 变量 | 默认 | 作用 |
|---|---|---|
| `KALDO_SYNTH_EH_FRAME` | 关（runtime 本身） | 启动时由 compact unwind 合成并注册 `__eh_frame`；**打包方必须开**（§3.3 第 2 条）。v5 起按值判断：未设、空串、`0` 为关，其他非空值为开（v4 及以前只看是否存在，`0` 也算开） |
| `KALDO_KEEP_COMPACT_UNWIND` | 关（`merge-payloads.py` 本身）；`hotreload.env` 开 | 构建时保留 `__LD,__compact_unwind`，供上一条合成。**打包方必须开**（§3.3 第 1 条）；不开时异常穿过 JIT 代码无法展开。B7 起整段注册，12.6 万条 FDE 只多约 0.7 s 启动 |
| `KONAN_HOT_RELOAD_CACHE_ONLY` | 关 | 编译时在缓存构建后返回，跳过 host 生成与链接；守护进程默认开 |
| `KALDO_HOT_SYMBOLS` | 未设 | 启动时的热符号清单；未设 = 全部 Kotlin 符号外部化（启动约 20% 概率 SIGSEGV）。**打包方必须传收窄清单**（§3.3 第 3 条） |
| `KALDO_PERF_MAP` | 关 | 写 `/tmp/perf-<pid>.map`，给 lldb 符号化 |
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
| 8 | 2026-11-19 | B10：分类器提速。守护进程新增 `@klib` 工具请求（§2.1）；`compat-classify.py` 新增 `baseline`/`check` 子命令和 `--daemon`、`--cache-dir`，快照增加 `klibSha256`（§10.1）；`reload` 的分类改为与 cache-only 编译并行、发送前等结论，app library klib 走 `hr-klib.sock`（§10.2）。**行为变化**：`hr-compile.sock` 有默认路径 `$HRC_SOCKET_DIR/hr-compile.sock`（`HRC_SOCKET_DIR` 默认 `<工程>/.hr`），存在就用，不再要求显式导出 `HRC_SOCKET`（§2.3）；`reload --no-warm` 在退出 3 前会先完成编译（仍不发送）。`merge-payloads.py` 在任何退出路径上删除工作目录，`KALDO_MERGE_KEEP=1` 保留（§3.1）。`fetch-toolchain.py` 接受 interfaceVersion 6–8 的 bundle 清单（v7 时它仍只认 6，下载 v7 清单会被拒，这是 v7 的遗漏）。清单字段不变。B11：libKaldo 只在 `KALDO_DEBUG_SELREFS=1` 时写 `/tmp/gap-selrefs.log`（§7；以前每次启动都写，首发前必修），`llvm.patchSha256` 变为 `3bb8a987…`、`llvm.kaldoIossimSha256` 变为 `8ed50386…`（macOS 版 libKaldo 同步重建），字段格式不变。公开 klib（v8，0.1.0）：bundle 清单新增 `externalKlibs`、`bundledKlibs`（§9.2），新增组件 `bundled-klibs`，新脚本 `gen-external-klibs.py`；`fetch-toolchain.py` 下载并校验公开 klib，不再读 Gradle 缓存，`--auto-cache-from` 默认为空；`compose-compiler-plugin` 改为 Release 附件；每个组件附 LICENSE 和 NOTICE；fetch 生成的 local 清单带 `tools`（绝对路径） |

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
| `bundled-klibs` | `bundled-klibs` | release | v8：公开 Maven 仓库里找不到的公开 klib（0.1.0 是 skiko `0.157.0-alpha01` 的两个 klib），原样放入 |

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
- 公开仓库的 Release 附件和公开 klib 都不需要 token。设了 `$GITHUB_TOKEN` 时，Release 附件会带上它（私有仓库通过 GitHub API 按 tag 查 asset id）。
  Release 走 https 这条路在首次公开发布时第一次实测；公开 klib 的 https 下载已经实测（Maven Central、Google Maven）。
- `KLIB_AUTO_CACHE_FROM` 只包含工具链自己的 klib 目录（`compose-klibs`、`external-klibs`、`bundled-klibs`），`--auto-cache-from` 默认为空（v7 及以前默认是 `~/.gradle/caches`）。
- 消费方义务：使用前必须按清单校验 sha256；校验失败不得使用；不得回落到其他位置的同名文件。

## 10. 兼容性分类器与温重启（v7；v8 提速）

### 10.1 `compat-classify.py`

```
compat-classify.py snapshot --klib <klib> --out <json> [--klib-tool <klib-tool>] [--daemon <sock>] [--cache-dir <dir>]
compat-classify.py classify --old <json> --new <json> [--json]
compat-classify.py baseline --baseline-dir <dir> --module <name>=<klib> ... [--klib-tool ..] [--daemon ..] [--cache-dir ..]   # v8
compat-classify.py check    --baseline-dir <dir> --module <name>=<klib|-> ... [--klib-tool ..] [--daemon ..] [--cache-dir ..] [--prior-ms <name>=<ms>]   # v8
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
- `topProperties` / `topFunctions`：包级声明；
- `bodies`：所有 `inline` 和 `suspend` 函数的 IR 正文哈希（用于发现正文改动）；
- `topOther` / `classes[].other`：未识别的成员原文，用于保守拦截。

`classify` 比较两份快照。退出码：**0 可热替换**；**3 需要温重启**；
**2 参数/工具错误**（此时不得发送补丁）。输出每行 `WARM <类别> <符号>: <原因>`，类别固定为：

| 类别 | 触发条件 |
|---|---|
| `field-layout` | 类字段新增/删除/类型变化/顺序变化，或构造函数签名变化 |
| `signature` | 函数签名变化、已有类新增方法、超类型变化 |
| `inline` | 已有 inline 函数的正文变化（IR 哈希不同） |
| `suspend` | 已有 suspend 函数的正文变化，或其签名变化 |
| `top-level-property` | 顶层属性新增/删除/类型或 `val`/`var` 变化 |
| `class-removed` | 类被删除（旧实例可能还活着） |
| `class-shape` | 未识别的类/顶层成员发生变化（保守） |

明确放行（不产生 WARM）：普通函数正文、包级函数新增/删除（composable 增删）、私有方法改动、
新增类、`const val` 的值变化（初始化值不属于布局）。

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
- 每次分类后 `reload` 在 stdout 打印 `compat: timing ms=<合计> ui_snapshot=<ms> app_snapshot=<ms> classify=<ms> ui=<same|hit|dump> app=<...>`
  和 `compat: wait ms=<发送前为等分类而阻塞的时间>`（v8）。这些是诊断输出，**不属于契约**，格式可能变化，插件不要依赖它们。

### 10.3 参考 app 的状态契约（`--hr-state`）

- 启动参数 `--hr-state=PAGE,COUNT`（逗号分隔，可省略；省略时读状态文件）。
- app 在每次页面/计数器变化时写 `<Documents>/hr-state.json`：`{"page":N,"count":M}`。
- 这是“可序列化的那部分状态”；`remember` 里的其它瞬态（tick 等）不恢复。
- 打包方如果自带 app 壳，要实现同样的参数和文件，`warm` 才能恢复状态；不实现也能温重启，
  但只能回到第 0 页。

