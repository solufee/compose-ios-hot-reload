# Compose iOS Hot Reload（Kotlin/Native + Compose Multiplatform）

在 iOS 模拟器上给 Compose Multiplatform 做**进程内热重载**：保存 Kotlin 代码后，正在运行的 app
不重启、不丢状态（页面、`remember` 计数器都在），UI 直接更新。底层是 Kotlin/Native 的
split 编译 + libKaldo（LLVM ORC/JITLink）+ 合成 `__eh_frame`，不是 dlopen，也不是解释器。

> **实验性质，0.2.0。**
> - 只支持 **Apple Silicon Mac + iOS 模拟器**（arm64），不支持真机。
> - 工具链版本固定：打过补丁的 Kotlin/Native、Compose Multiplatform 和 LLVM/libKaldo，插件只认这一套
>   （Xcode 27.2、iOS 27.2 模拟器 runtime）。只用于 debug，不要用于发布构建。
> - 适用工程：已有的 Kotlin Multiplatform 工程（例如 KMP 向导生成的 Compose Multiplatform 工程，插件加在含
>   `MainViewController()` 的模块上，`commonMain`/`iosMain`、`expect`/`actual`、第三方 iOS 依赖都自动识别），
>   或不用 KMP 插件的 app + ui 多模块工程。只编 `iosSimulatorArm64` 目标，Swift/Xcode 那层（`iosApp/`）不参与热重载；
>   工程里的 Compose 不能比工具链的 Compose fork（1.13.0-beta01）新，依赖库要是 Kotlin 2.5 及更早版本编出来的
>   （klib ABI 1.6.0–2.5.0），带原生代码的库（cinterop）0.2.0 不支持。
>   详见 [docs/TRYOUT.md](docs/TRYOUT.md) 第 7 节。
> - 已知限制见 [docs/LIMITATIONS.md](docs/LIMITATIONS.md)。
> - 问题和建议请提到 [Issues](https://github.com/solufee/compose-ios-hot-reload/issues)。

**想直接试用**：看 [docs/TRYOUT.md](docs/TRYOUT.md)。插件从 GitHub Pages 的 Maven 仓库下载，工具链从 Release 下载，
不需要 token，也不用自己构建工具链。下面的“从零构建”只在你想自己编译整套工具链时才需要。

**性能**（测试机上，保存到新画面）：Gradle 双模块示例只改 app 约 3.9 s、只改 ui 约 5.0 s；单文件 demo 2.6 s。
怎么测的、每一项的结果见 [docs/VALIDATION.md](docs/VALIDATION.md)；原理和关键修复见
[docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md)。

许可证：Apache-2.0（见 [LICENSE](LICENSE)）；第三方组件见 [NOTICE](NOTICE)。

## 仓库结构

```
gradle-plugin/   Gradle 插件 com.solufee.cmp.hotreload（接入方式见 docs/TRYOUT.md）
example-gradle/  app + ui 双模块示例，以及参考驱动 hotreload.sh / run.sh
patches/         三个上游仓库的补丁（Kotlin / LLVM+libKaldo / Compose），带公开基线 commit
daemon/          常驻编译进程（HrCompileDaemon）+ 客户端 + 启动脚本
scripts/         工具链打包、发布、下载，兼容性分类器，构建与测量脚本
demo/            单文件两页示例
docs/            TRYOUT（试用）、INTERFACE（底层接口）、LIMITATIONS、PACKAGING、TECHNICAL-NOTES、VALIDATION
```

## 环境要求

> 只想用 Gradle 插件试用的话，看 [docs/TRYOUT.md](docs/TRYOUT.md) 就够了；本节和下面的“从零构建”是给自己编译整套工具链的人看的。

| 项 | 版本 / 说明 |
|---|---|
| 机器 | Apple Silicon Mac（实测 macOS 26） |
| Xcode | 27.2（27B52），含 iOS 27.2 模拟器 runtime |
| JDK | 17 或 21（构建 Gradle 与运行 kotlinc-native；两个都实测过） |
| Kotlin | 2.5.255-SNAPSHOT，从 PR #7234 / #7418 / #7606 的分支构建 |
| Compose | jb-main `ee243c321`（runtime 1.13.0-beta01 一系） |
| Python | 3.9+（脚本只用标准库；macOS 自带的 python3 即可） |
| AXe | 可选，用于真实点击（`brew install cameroncooke/axe/axe`） |

**路径约定**：仓库里所有本机绝对路径都换成了占位符，脚本从环境变量或相对路径推导。
第一次用之前按下表替换（补丁、示例 klib 列表、文档里的命令都一样）：

| 占位符 | 含义 | 例子 |
|---|---|---|
| `<PROJECT_ROOT>` | 本仓库/工作区根目录 | `~/cmp-ios-hot-reload` |
| `<KOTLIN_NATIVE_DIST>` | 自己构建的 Kotlin/Native 工具链 `dist` | `~/konan-hot-reload/kotlin-native/dist` |
| `<COMPOSE_PLUGIN_JAR>` | Compose 编译器插件 jar | `~/.../compiler-2.5.255-SNAPSHOT-shadow.jar` |
| `<YOUR_LLVM_INSTALL>` | 第 1 步构建的 macOS LLVM 安装目录 | `~/llvm/install-macos` |
| `<KALDO_INSTALL>` | 第 1 步 libKaldo 的项目内安装目录（iossim、macOS 两份） | `~/llvm/install-kaldo` |
| `<GRADLE_CACHE>` | Gradle 缓存根目录 | `~/.gradle/caches` |
| `<KONAN_HOME>` | Kotlin/Native 依赖目录（只读：脚本只用其中官方 LLVM 的 `llvm-objcopy`/`llvm-nm`，不往里放任何文件） | `~/.konan` |
| `<HOME>` | 用户主目录 | `~` |

脚本对应的环境变量：`KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`IC_DIR`、
`AUTO_CACHE`、`KALDO_DYLIB`、`UDID`、`HRC_SOCKET(_DIR)`。

## 从零构建

> 第 1–3 步是长构建（LLVM 约 1–2 小时、Kotlin dist 约 15–30 分钟、Compose 十几分钟），
> **没有从零复现验证**；第 4–5 步（app、守护进程、热重载）在作者机器上实测通过。
> 关键问题和修法见 [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md)。

### 1. LLVM + libKaldo

需要两份 LLVM：给编译器用的 macOS 版，和给 iOS 模拟器用的 `libKaldo.iossim.dylib`。

```bash
git clone https://github.com/JetBrains/llvm-project-kotlin.git llvm-project
cd llvm-project
git checkout 2f8844c2b355
git apply /path/to/patches/llvm-kaldo.patch

# macOS 版：Kotlin/Native 编译器链接用
cmake -G Ninja -S . -B build-macos \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLVM_ENABLE_PROJECTS="clang;lld" \
  -DLLVM_TARGETS_TO_BUILD=AArch64 \
  -DLLVM_BUILD_LLVM_DYLIB=OFF \
  -DCMAKE_INSTALL_PREFIX=$PWD/../install-macos
ninja -C build-macos install

# iOS 模拟器版：libKaldo.iossim.dylib
cmake -G Ninja -S . -B build-iossim \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_SYSROOT=iphonesimulator \
  -DLLVM_ENABLE_PROJECTS=llvm \
  -DLLVM_TARGETS_TO_BUILD=AArch64 \
  -DLLVM_BUILD_LLVM_DYLIB=OFF \
  -DCMAKE_INSTALL_PREFIX=$PWD/../install-iossim
ninja -C build-iossim libKaldo.iossim.dylib
```

把两个动态库装到项目内的独立目录 `<KALDO_INSTALL>`。**不要放进 `~/.konan/dependencies`**，
那是本机所有 Kotlin/Native 项目共用的官方依赖目录；也不要放进 LLVM 安装目录。编译器按
`konan.properties` 的 `kaldoLinkerFlags`（`-L<KALDO_INSTALL>/lib -lKaldo*`，见第 2 步）直接从这里链接：

```bash
KALDO_INSTALL=$PWD/../install-kaldo
mkdir -p $KALDO_INSTALL/lib
cp build-macos/lib/libKaldo.dylib         $KALDO_INSTALL/lib/
cp build-iossim/lib/libKaldo.iossim.dylib $KALDO_INSTALL/lib/
```

- 链接开关在 `konan.properties` 的 `kaldoLinkerFlags.*`（`-lKaldo` / `-lKaldo.iossim`）。
- 打进 `.app` 的那份 libKaldo 只取 `KALDO_DYLIB` 或 `toolchain.json` 的 `llvm.kaldoIossim`，没有默认位置；
  清单里有库文件的 sha256，对不上会拒绝打包。所以每次重建 libKaldo 后都要重跑 `scripts/gen-toolchain.sh`。
- `ninja -C build-macos install` 会把 `libKaldo.dylib` 也装进 `install-macos/lib`；装完删掉它
  （`rm ../install-macos/lib/libKaldo*.dylib`），保证链接只能从 `<KALDO_INSTALL>` 找到。

### 2. Kotlin/Native 工具链

```bash
git clone https://github.com/JetBrains/kotlin.git kotlin-hr
cd kotlin-hr
git checkout 8e53ee99fbc08e264828ab082aee3e448194eca8   # 公开 master
# 补丁已包含 PR #7234 / #7418 / #7606 的全部改动，不需要再 checkout PR 分支
git apply /path/to/patches/kotlin-native-hot-reload.patch

# 把 konan.properties 里的三处占位符改成第 1 步的目录：
#   llvmHome.macos_arm64 = /绝对路径/install-macos
#   kaldoLinkerFlags.macos_arm64 = -L/绝对路径/install-kaldo/lib -rpath /绝对路径/install-kaldo/lib -lKaldo
#   kaldoLinkerFlags.ios_simulator_arm64 = -L/绝对路径/install-kaldo/lib -lKaldo.iossim

JAVA_OPTS="-Xmx16g" ./gradlew :kotlin-native:dist --console=plain
export KOTLIN_DIST=$PWD/kotlin-native/dist
```

产物：`kotlin-native/dist/bin/kotlinc-native`、`dist/konan/lib/kotlin-native-compiler-embeddable.jar`。

> **只替换编译器类时**（例如只改了 `KonanDriver.kt` 的 cache-only 分支）可以只跑
> `./gradlew :kotlin-native:prepare:kotlin-native-compiler-embeddable:kotlin-native-compiler-embeddable`，
> 再把 `KonanDriver.class` 等打进 dist 的 jar，约 30–45 秒。
> 注意 `libllvmext` 会因为本机 LLVM 头变化而重编失败，它和编译器 Kotlin 代码无关。

### 3. Compose Multiplatform klib

```bash
git clone https://github.com/JetBrains/compose-multiplatform-core.git compose
cd compose
git checkout ee243c321
git apply /path/to/patches/compose-multiplatform-core-build.patch
# 改 gradle/libs-fork.versions.toml 与 gradle/libs.versions.toml 里的 kotlin 版本为 2.5.255-SNAPSHOT
# 改 kotlin.native.home 指向第 2 步的 dist（补丁里是 <KOTLIN_NATIVE_DIST> 占位符）

./gradlew :compose:ui:ui:compileKotlinIosSimulatorArm64 \
          :compose:foundation:foundation:compileKotlinIosSimulatorArm64 \
          :compose:material3:material3:compileKotlinIosSimulatorArm64 \
          :compose:runtime:runtime:compileKotlinIosSimulatorArm64 \
          # … 共 15 个模块（Compose fork 构建出的全部 iOS 模拟器 klib）
```

把产出的 19 个 klib 收集到一个目录（作者用的是 `~/.gradle/caches/compose-source-jbmain/`），
再把路径写进 klib 列表（见第 4 步）。

### 4. 构建 ComposeHR

```bash
# klib 列表：每行一个 klib 路径；只有包含 /.gradle/caches/ 的行会被当成 -l 传给编译器
export KLIBLIST=/path/to/klibs.txt          # 格式参考 demo/klibs-example.txt

# CMPUIKitUtils 静态库：从 Compose fork 的 iOS 工程构建
#   compose/ui/ui-uikit/src/iosMain/objc/CMPUIKitUtils/CMPUIKitUtils.xcodeproj
#   deployment target 需要 15.0，产物放到工作目录 libCMPUIKitObjcUtils.a

cd demo                    # 把 demo/main.kt、Info.plist 放在这里
KOTLIN_DIST=$KOTLIN_DIST \
COMPOSE_PLUGIN_JAR=$KOTLIN_DIST/../plugins/compose/compiler/build/libs/compiler-2.5.255-SNAPSHOT-shadow.jar \
  ../scripts/build-a.sh
```

`build-a.sh` 会：编译 split host → `merge-payloads.py` 把每个 cache payload 合成一个 guest 对象
→ `embed-merged.py` 写进 `__TEXT,__kaldo_boot` → 生成 `ComposeHR.kexe`。
`demo/klibs-example.txt` 里的路径都是占位符，按你的 `<GRADLE_CACHE>` / `<KOTLIN_NATIVE_DIST>` 生成一份。

app 包：

```bash
mkdir -p ComposeHR.app/Frameworks
cp ComposeHR.kexe ComposeHR.app/ComposeHR
cp Info.plist ComposeHR.app/Info.plist
cp <KALDO_INSTALL>/lib/libKaldo.iossim.dylib ComposeHR.app/Frameworks/
xcrun simctl install booted ComposeHR.app
```

### 5. 启动守护进程 → 启动 app → 热重载

```bash
# 1) 常驻编译进程（socket 在 .hr/ 下，目录 0700、socket 0600）
cd demo
KOTLIN_DIST=$KOTLIN_DIST \
COMPOSE_PLUGIN_JAR=$KOTLIN_DIST/../plugins/compose/compiler/build/libs/compiler-2.5.255-SNAPSHOT-shadow.jar \
  ../daemon/daemon-start.sh

# 2) 启动 app（脚本里带上 EH 合成开关；不设它，启动的 EH probe 会直接终止）
KALDO_DYLIB=<KALDO_INSTALL>/lib/libKaldo.iossim.dylib \
  ../scripts/run-a.sh

# 3) 改一行代码，然后在另一个终端里热重载
sed -i '' 's/BUILD_TAG = "v1"/BUILD_TAG = "v2"/' main.kt
../daemon/reload-c.sh
```

`reload-c.sh` 会调用守护进程编译 → 从 `-Xdump-built-caches-to` 拿到新产出的 `.a`
→ 解包 → 通过 `reload.py` 把对象路径发给 app 的 5567 端口 → app 里 JIT 替换并重组。
控制台出现 `RENDER v2 page=2 ... count=3` 即成功，PID 不变。

**冷启动**：守护进程第一次编译要加载编译器类，约 5–7 s；之后每次 1.3 s 左右。

## Gradle 双模块示例（参考脚本）

> 本节讲的是仓库里的参考驱动 `hotreload.sh` / `run.sh`；用 Gradle 插件时看 [docs/TRYOUT.md](docs/TRYOUT.md)，插件自己做这些事。


`example-gradle/` 是可运行的 app + ui 双模块工程：

```bash
cd example-gradle
./gradlew hotReloadPackage   # 一条命令出 build/ComposeHR.app
./run.sh                     # 安装并启动
./gradlew hotReload          # 改完代码，编译+符号检查+ld -r 合并+发送
```

验收：ui 20/20、app 20/20、同时改两个 3/3（同一 tick），PID 不变。
详见 `example-gradle/README.md`。

改到类字段布局、函数签名、inline、suspend 或新增顶层属性时，`hotReload` 会在发送前被兼容性分类器拦下
（接口 v7；v8 起分类与编译并行），自动重新打包并用 `--hr-state` 回到原页面（页面 + 计数器）。只看拦截结果、不重启：

```bash
bash hotreload.sh reload --no-warm    # 需要温重启时打印 WARM 原因并退出 3，不发送补丁
```

完整回归（改动类型 → 期望结果 + 截图比对）一条命令：

```bash
source <toolchain>/env.sh
python3 example-gradle/accept-b4.py
```


## 工具链清单与接口

```bash
KOTLIN_DIST=... COMPOSE_PLUGIN_JAR=... KLIBLIST=... scripts/gen-toolchain.sh
```

产出 `$KOTLIN_DIST/toolchain.json`（`interfaceVersion=8`，`layout: "local"`：Kotlin/Compose/LLVM 的公开基线 commit
与补丁 sha256、klib 清单、libKaldo 路径与 sha256、Xcode 版本、端口）。底层工具的参数、退出码、环境开关见
[docs/INTERFACE.md](docs/INTERFACE.md)，示例清单见 [docs/toolchain.example.json](docs/toolchain.example.json)。

### 预构建工具链：打包、发布、下载（interfaceVersion 8）

试用者只需要第 4 步（见 docs/TRYOUT.md）。前三步是维护者出包用的：

```bash
# 0) 公开 klib：从 Maven Central / Google Maven 核对并固定（坐标、URL、sha256、许可证）；两个仓库都没有的标为随包分发
scripts/gen-external-klibs.py --klibs <本机 klibs 清单> --out <WORKSPACE>/external-klibs.json

# 1) 打包：9 个 tar.zst 组件（含 bundled-klibs）+ packaging-manifest.json，每个组件附 LICENSE 和 NOTICE
OUT_DIR=<WORKSPACE>/toolchain-dist TOOLCHAIN_VERSION=2.5.255-hotreload.5 \
EXTERNAL_KLIBS=<WORKSPACE>/external-klibs.json \
KOTLIN_DIST=<KOTLIN_NATIVE_DIST> COMPOSE_KLIB_CACHE=<GRADLE_CACHE>/compose-source-jbmain \
COMPOSE_PLUGIN_JAR=<COMPOSE_PLUGIN_JAR> \
KALDO_IOSSIM=<KALDO_INSTALL>/lib/libKaldo.iossim.dylib KALDO_MACOS=<KALDO_INSTALL>/lib/libKaldo.dylib \
ORC_RUNTIME=<path>/liborc_rt_iossim_arm64.a UIKIT_UTILS=<path>/libCMPUIKitObjcUtils.a \
LLVM_TOOLS_FROM=<KONAN_HOME>/dependencies/llvm-21-aarch64-macos-dev-97 \
  scripts/pack-toolchain.sh

# 2) 发布清单：相对路径、每个组件的 sha256、Release 下载地址、公开 klib 清单
scripts/gen-bundle-manifest.py --packaging <WORKSPACE>/toolchain-dist/packaging-manifest.json \
  --identity <KOTLIN_NATIVE_DIST>/toolchain.json --repo solufee/compose-ios-hot-reload --channel release \
  --external-klibs <WORKSPACE>/external-klibs.json --out <WORKSPACE>/toolchain-dist/toolchain.json

# 3) 发布：默认只打印命令（dry-run）；--local-repo 生成本地镜像和 file:// 清单做端到端测试；
#    真正上传必须显式加 --publish --confirm-repo solufee/compose-ios-hot-reload（建 draft release）
scripts/publish-toolchain.py --dist-dir <WORKSPACE>/toolchain-dist --manifest <WORKSPACE>/toolchain-dist/toolchain.json
scripts/publish-toolchain.py --dist-dir <WORKSPACE>/toolchain-dist --manifest <WORKSPACE>/toolchain-dist/toolchain.json \
  --local-repo <WORKSPACE>/toolchain-repo

# 4) 消费端：下载、校验 sha256、解包、下载公开 klib、本地化，然后 source env.sh 就能用 example-gradle
scripts/fetch-toolchain.py https://github.com/solufee/compose-ios-hot-reload/releases/download/toolchain-2.5.255-hotreload.5/toolchain.json \
  --cache <WORKSPACE>/toolchain-cache
. <WORKSPACE>/toolchain-cache/2.5.255-hotreload.5/env.sh
```

- 需要 `zstd`（`brew install zstd`）。`llvmHome` 默认使用 Kotlin/Native 官方依赖 `llvm-21-aarch64-macos-essentials-97`。
- 只有显式加 `--publish --confirm-repo <owner/repo>` 才会真正上传：建一个 draft release 并上传全部组件和清单，公开要另外执行 `gh release edit <tag> --draft=false`。不加时只打印命令（dry-run）。
- 组件、清单字段、退出码见 [docs/INTERFACE.md](docs/INTERFACE.md) §9；体积和渠道见 [docs/PACKAGING.md](docs/PACKAGING.md)。

## 环境开关

| 变量 | 默认 | 作用 |
|---|---|---|
| `KALDO_SYNTH_EH_FRAME` | 关 | 由 compact-unwind 合成 `__eh_frame` 并注册。**热重载构建必须开**，否则异常穿过 JIT 代码时展开失败。`run-a.sh` 已设。 |
| `KALDO_KEEP_COMPACT_UNWIND` | 关 | `merge-payloads.py` 用：保留 `__LD,__compact_unwind`，供上一条合成 FDE。 |
| `KALDO_HOT_SYMBOLS` | 无 | 热符号集文件（第 6 节）。只有集合内的符号会被外部化成可替换 stub。 |
| `KONAN_HOT_RELOAD_CACHE_ONLY` | 关 | 编译器在缓存构建后直接返回，跳过被丢弃的 host 生成与链接。守护进程默认打开。 |
| `KALDO_PERF_MAP` | 关 | 写 JIT 地址到 `/tmp/perf-<pid>.map`，给 lldb 符号化用。 |
| `KALDO_DEBUG_SELREFS` | 关 | `=1` 时 libKaldo 把补登记的 selector 写到 `/tmp/gap-selrefs.log`（调试用；v8 起默认不写）。 |
| `KONAN_ORC_RUNTIME_PATH` / `SIMCTL_CHILD_...` | 无 | ORC runtime 的静态库路径，`run-a.sh` 已设。 |
| `KONAN_HOT_RELOAD_PORT` | 5567 | app 与 `reload.py` 共用的热重载端口；`run.sh` 会加 `SIMCTL_CHILD_` 前缀传给 app。 |

## 验收矩阵

```bash
cd demo
RELOAD=../daemon/reload-c.sh START_TAG=1000 \
  python3 ../scripts/accept-matrix.py <app-console-log> 10
```

4 类改动（文案、颜色、增删 composable、if 分支）× 10 次；每次检查
`RENDER <tag> page=2 ... count=3` 且 PID 不变，输出 p50 / p90。

实测结果（PID 18507，`evidence/accept-fast5.log`）：

| 改动类型 | 成功 | p50 | p90 |
|---|---|---|---|
| 改文案 | 10/10 | 2,472 ms | 2,571 ms |
| 改颜色 | 10/10 | 2,594 ms | 2,617 ms |
| 增删 composable | 10/10 | 2,659 ms | 2,730 ms |
| 改 if 分支 | 10/10 | 2,800 ms | 2,852 ms |
| **合计** | **40/40** | **2,611 ms** | **2,827 ms** |

## 性能

| 阶段 | 保存→首帧 p50 |
|---|---|
| 最初版本（2026-11-18） | 10,443 ms |
| + 跳过 host 生成与链接 | 7,243 ms |
| + 稳定 klib 名 + per-file 缓存 | 6,982 ms |
| + 常驻编译守护进程 | **2,611 ms** |

构成：编译 1.3 s + 解包/发送 0.12 s + 发送到首帧 0.95 s。

上表是单文件 demo（`demo/` + `daemon/reload-c.sh`）。Gradle 双模块示例（EH 合成打开，当前推荐配置）：
只改 app p50 **3,929 ms**、只改 ui 4,945 ms、两个模块同时改 5,096 ms；冷启动到首帧约 5.2 s。
`reload` 默认开兼容性分类器（v7）。v8 起分类与编译并行、dump 在守护进程里做，开分类器的代价基本看不出来：
ui 4,953 ms、app 3,919 ms、两个 5,290 ms，与关分类器交替对照 ui +2 ms、app +15 ms（v7 时是 ui +0.6 s、app +3.5 s）。
完整的测量见 [docs/VALIDATION.md](docs/VALIDATION.md)。

## 限制与已知问题

见 [docs/LIMITATIONS.md](docs/LIMITATIONS.md)。要点：

- 只支持 iOS 模拟器（arm64）、macOS arm64 主机；真机未做。
- 只支持 debug / split 构建；release 不经过这些分支。
- 多模块（app + ui）和同一模块多文件同时改动都支持，一次原子发送、同一 tick 生效。
- 类字段布局、函数签名、inline、suspend、新增顶层属性的改动会被兼容性分类器拦下并自动温重启；
  温重启恢复页面和计数器（`--hr-state`）；插件 0.1.2 起还把 app 自己的 `Documents/hr-state.json` 原样带过重装，其它没写进文件的 `remember` 瞬态不恢复；参考脚本一次温重启约 16 s，
  其中约 10.5 s 是完整重新打包；插件 0.1.1 接入了同样的提速，温重启约 17 s（0.1.0 约 50 s）。见 docs/LIMITATIONS.md。
- plist 里的 `NSNumber` 布尔值桥接：工具链 .5 已修，Compose 的严格 Info.plist 检查可以保持打开（.4 及以前要关闭）。
- 长会话（3.5 小时 / 上万次重组）后发送段会慢约 1 s，原因未定位。
- 启动稳定性靠收窄的 `KALDO_HOT_SYMBOLS`（`run.sh` 默认传）：30/30；不收窄时约 20% 在 `Konan_constructors` 前崩溃。
- 预构建工具链从公开仓库的 Release 下载，不需要 token（见 docs/TRYOUT.md）；Release 的 https 下载在首次公开发布时第一次实测，之前用同样的文件在本地镜像上验证过。

## 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| 启动即 `Uncaught ... eh-probe` | 没设 `KALDO_SYNTH_EH_FRAME=1`，用 `scripts/run-a.sh`。 |
| `daemon not reachable` | 守护进程没起或崩溃；`daemon/daemon-start.sh` 会检查重复启动和 owner。 |
| `no new cache archive` | 源码没变（缓存命中）或编译失败；看 `hr-daemon.log`。 |
| 发送后 app 退出 | 看 app 控制台；多数是 patch 对象里的符号冲突或 EH 注册失败。 |
| `split compilation requires ...` | 少了 `-Xenable-incremental-compilation` / `-Xic-cache-dir`，`split` 强制要求。 |
