# 预构建工具链：体积与分发方案（B5 方案，B6 实现）

> 2026-11-19。当前接口是 **interfaceVersion 8**（B4 的分类器和温重启、B10 的分类器提速都只加工具，不改分发字段；分发格式仍是 B6；`fetch-toolchain.py` 接受 6–8 的 bundle 清单）：打包、发布清单、发布脚手架、下载脚本都已经实现，
> 并用本地 `file://` 仓库跑通了“打包 → 发布 → 下载 → 用下载来的工具链打包 app → 模拟器上热重载”的全流程。
> **还没有真正发布**：发布脚本只有 dry-run 和本地仓库两种模式。字段和命令见 [INTERFACE.md](INTERFACE.md) §9。

## 1. 组件与体积（`2.5.255-hotreload.4`）
打包命令见第 6 节。压缩格式为 `tar` + `zstd -19 -T0`，9 个组件打包（含二进制扫描）共用 1 分 29 秒。全部是公开仓库 Release 的附件。
| 组件 | 内容 | 原始 | 压缩后 | sha256（前 16） |
|---|---|---:|---:|---|
| `kotlin-native` | Kotlin/Native dist 最小子集（macOS arm64 主机），`konan.properties` 为占位符 | 374.4 MiB | **67.1 MiB** | `4a82f1e7bf7bfeac` |
| `compose-klibs` | Compose fork 的 19 个模块 klib（已解包目录；源文件路径为相对路径） | 48.0 MiB | **10.5 MiB** | `30c72c0afd1593a0` |
| `compose-compiler-plugin` | Compose 编译器插件 shadow jar | 2.2 MiB | **0.5 MiB** | `41965e21850158d4` |
| `kaldo-iossim` | `kaldo/lib/libKaldo.iossim.dylib` | 38.9 MiB | **10.1 MiB** | `83d4d057d88d7059` |
| `kaldo-macos` | `kaldo/lib/libKaldo.dylib`（主机侧） | 37.5 MiB | **10.0 MiB** | `4928f339c5ed952e` |
| `orc-runtime` | `liborc_rt_iossim_arm64.a`（启动时 ORC 需要） | 3.0 MiB | **0.5 MiB** | `8a0d27f8741ecf12` |
| `cmp-uikit-utils` | `libCMPUIKitObjcUtils.a`（打包时 `-force_load`；去掉了调试信息） | 0.4 MiB | **0.1 MiB** | `95a16f10d4d9f492` |
| `llvm-tools` | 官方 dev-97 的 `llvm-objcopy`、`llvm-nm`（只链接系统库） | 42.2 MiB | **7.0 MiB** | `c7f7bba27d9eb907` |
| `bundled-klibs` | skiko 的 2 个 klib（见下）及其许可证 | 43.6 MiB | **43.3 MiB** | `19840745492b5c27` |
| **合计** | | **590.2 MiB** | **149.0 MiB** | |

上表是 .6 及以前的组件。**工具链 .7**（Compose 基线 `v1.13.0-rc01`）只有前 8 个组件，没有 `bundled-klibs`，压缩后共 **105.7 MiB**：`kotlin-native` 67.1 MiB、`compose-klibs` 10.5 MiB、`compose-compiler-plugin` 0.5 MiB、`kaldo-iossim` 10.1 MiB、`kaldo-macos` 10.0 MiB、`orc-runtime` 0.5 MiB、`cmp-uikit-utils` 0.06 MiB（用 rc01 的源码重编）、`llvm-tools` 7.0 MiB；清单见 `docs/toolchain-bundle.example.json`。

另外 `fetch-toolchain.py` 从 Maven Central / Google Maven 下载公开 klib（.6：21 个坐标，共 5.1 MiB；.7：27 个 klib 文件，含 skiko 0.156.1 的 2 个）。每个组件都带
`licenses-compose-ios-hot-reload/`（LICENSE、NOTICE）。完整哈希和字节数见打包产物里的 `packaging-manifest.json`（schema 3）
和发布清单（示例：`docs/toolchain-bundle.example.json`）。

**`.3` 已撤回、不发布**：它的 `compose-klibs` 里记录了构建机上的源文件绝对路径，`libCMPUIKitObjcUtils.a` 的调试信息里也有，
会暴露构建机的用户名和目录结构。`.4` 的修法：Compose klib 用 `-Xklib-relative-path-base=<compose 源码根目录>` 重建
（源文件路径变成 `compose/ui/ui/src/...` 这样的相对路径；`klib dump-abi` 与重建前完全一致），`libCMPUIKitObjcUtils.a`
用 `strip -S` 去掉调试信息（导出符号不变）。

### 打包时的二进制扫描（固定门槛）

`pack-toolchain.sh` 在每个组件打包前，用 `scripts/scan-binary-leaks.py` 扫描暂存目录里的**所有文件**，二进制也按字节扫，
zip（jar、zip 格式的 klib）会解开逐个成员扫：macOS / Linux 的用户目录前缀、本机家目录、`agent-` 加 8 位十六进制，以及本机 deny 文件里
`# binary-scan: begin/end` 之间的人名、主机名、内部名称（deny 文件只在本机，不进仓库）。命中任何一项就停止打包，不写清单。
唯一的豁免是 `bundled-klibs` 里原样转发的 skiko klib（只有 .4–.6 有；.7 没有随包的 klib，豁免用不上，名单也没加条目）：它是 JetBrains 发布的原文件，IR 里带着 JetBrains CI 的构建路径；
只有当**每一处**命中都是这个路径时才放行，并在输出里逐条列出。测试：`scripts/test-scan-binary-leaks.sh`。

### llvmHome：官方 LLVM 够用（B6 结论）

v5 起 libKaldo 由 `kaldoLinkerFlags` 的 `-L<KALDO_INSTALL>/lib` 直接链接，不再依赖 LLVM 安装目录里有没有它。
B6 用下载来的工具链、`llvmHome = $llvm.macos_arm64.user`（Kotlin/Native 官方依赖 `llvm-21-aarch64-macos-essentials-97`，
里面只有 clang/lld/llvm-ar）跑通了 `hotReloadPackage`。base kexe 的 rpath 证实链接用的就是官方目录。之后首次启动 `EH OK`，
热重载 9/9 在同一个 PID 上，冷启动 3/3。所以**分发包不需要带自建的 1.9 GB LLVM**；`merge-payloads.py`/`embed-merged.py`
需要的 `llvm-objcopy`/`llvm-nm` 由 `llvm-tools` 组件提供（7.0 MiB）。

### Kotlin 最小子集的边界

保留：

```
bin/                      kotlinc-native、konanc、run_konan
tools/                    env_blacklist（run_konan 必需）
konan/lib/                kotlin-native-compiler-embeddable.jar（226 MiB）
konan/nativelib/          host 侧 LLVM/Clang stub dylib（80 MiB）
konan/targets/            ios_simulator_arm64 + macos_arm64（共 5.4 MiB，全部保留）
konan/platformDef/        （6.3 MiB，全部保留）
konan/konan.properties
klib/common/              14 MiB
klib/platform/ios_simulator_arm64/   43 MiB
```

去掉：

- `klib/platform/macos_arm64`（54 MiB）：只做 iOS 模拟器，不需要；
- `klib/cache/*`（100 MiB，其中 ios 40 MiB）：缓存，缺失时按需生成；
- `konan/swift_export` 等未使用目录。

验证：最小子集（不含 `klib/cache`）完成过一次完整的 `hotreload.sh package`（split 编译 →
合并 payload → 嵌入 guest → 组装 `.app`），链接成功；从 `tar.zst` 解包后的目录也成功编译了
`ui.klib`。`klib/cache` 缺失只影响首次编译速度，不影响正确性。

### 公开依赖 klib（0.1.0 起）

app 用到的 23 个公开 klib 文件（21 个坐标；skiko 和 atomicfu 各多一个 cinterop klib）不靠本机 Gradle 缓存：

- 21 个由 `fetch-toolchain.py` 按清单 `externalKlibs` 直接从 Maven Central（`repo1.maven.org`）或 Google Maven（`dl.google.com`）下载，
  逐个校验 sha256。清单由 `gen-external-klibs.py` 生成：它读每个坐标的 Gradle 模块元数据，用同名文件的真实 `url` 下载，
  要求本机已验证的缓存文件、下载文件、元数据里发布的 sha256 三者一致；许可证取自 POM，全部是 Apache-2.0。
- 工具链 .7 用 rc01 搭配的 skiko `0.156.1`，它在 Maven Central 上，和其他公开 klib 一样下载，不再随包分发，`bundledKlibs` 为空。
- .4–.6：skiko `0.157.0-alpha01` 的 2 个 klib 在这两个仓库里都没有（只在 JetBrains 的 Compose dev 仓库
  `https://packages.jetbrains.team/maven/p/cmp/dev`，sha256 相同），所以原样放进第 9 个组件 `bundled-klibs`（约 43 MiB）。
  它静态包含 Skia（BSD-3-Clause）和 Skia 的第三方库，许可证全文在 `licenses/bundled-klibs/`，也随组件分发。

## 2. 分发渠道建议

| 组件 | 渠道 | 标识 |
|---|---|---|
| `kotlin-native`、`compose-klibs`、`kaldo-iossim`、`kaldo-macos`、`orc-runtime`、`cmp-uikit-utils`、`llvm-tools` | **私有 GitHub Release 附件** | tag `toolchain-<toolchainVersion>`，资产名即文件名 |
| `compose-compiler-plugin` | GitHub Packages（Maven） | `com.solufee.cmp.toolchain:compose-compiler-plugin:<toolchainVersion>@tar.zst` |
| 发布清单 `toolchain.json`（layout bundle） | 同一个 Release 的附件 | 与组件同 tag；本地仓库模式另存 `toolchain-<version>.json`（`file://` URL） |

理由：

- 大二进制放 Release：GitHub 文档明确单个 Release 附件 **< 2 GiB**，本方案最大 67 MiB，余量充足；
  Release 对附件总大小和流量没有限制。
- 小且需要版本解析的放 Maven：插件 jar 和清单适合 `maven-publish`。
- GitHub Packages 的 **存储额度** 按账号计（Free 500 MB，Pro/Team 2 GB，与 Actions 产物共用）。
  98.1 MiB 的全量即使全部放 Packages 也放得下；但把大 tarball 放 Release 可以避免
  Maven 客户端为 67 MiB 单文件做多次重试，也不占 Packages 额度。
- GitHub 的 100 MiB **仓库文件**上限不适用于 Packages 和 Release 附件；Maven registry 文档
  没有给出单文件上限，因此“全部走 Maven”在技术上可行，作为备选保留。
- 两种渠道都需要凭据：私有仓库的 Release 下载需要 `repo` 权限的 token；Packages 需要
  `read:packages`。凭据只放 `~/.gradle/gradle.properties` 或环境变量，不进工程。

## 3. 版本与命名

- 工具链版本：`2.5.255-hotreload.1`（Kotlin 版本 + 热重载修订号）。
- Release tag：`toolchain-2.5.255-hotreload.1`。
- 文件名固定为 `<组件>-<版本>[-<平台>].tar.zst`，便于插件按清单拼接 URL。
- 重新打包会改变哈希；任何重新打包都必须重新生成 manifest 并换修订号，不能覆盖同名资产。

## 4. 发布清单（v6，已实现）

字段定义见 [INTERFACE.md](INTERFACE.md) §9.2。要点：所有路径都相对于解包目录；每个组件带 `bytes`、`sha256`、`extractDir`
和 `download`（`release`：repo/tag/asset；`maven`：repository/coordinate/path）；`llvm.llvmHome` 指向官方依赖。
插件行为：下载到 `$GRADLE_USER_HOME/caches/cmp-hotreload/<version>/`，校验 sha256 后解包；哈希不符就报错并删除，
不回退到未校验的文件。参考实现是 `scripts/fetch-toolchain.py`（INTERFACE §9.4）。

## 5. 体积与发布风险

- GitHub Packages 存储额度按账号共享（Free 500 MB）；本方案只有 0.5 MiB 走 Packages，其余 105 MiB 是 Release 附件，
  Release 附件不占这份额度。每发一个新版本，Release 都会再存一份；旧版本至少保留一个“上一个可用版本”。
- 私有 Release 附件对未登录用户不可见；插件必须带 token（`repo` 权限），文档要写清凭据配置。
  fetch 脚本里通过 API 下载私有附件的那条路径**还没实测**，只有 `file://` 跑通过。
- 解压依赖 `zstd`：插件侧可用 `zstd-jni`，或者调用系统的 `zstd`；参考脚本要求 PATH 里有 `zstd`。
  目前只保证 macOS arm64 主机。
- 消费方第一次编译时要现建 stdlib 缓存（包里不带 `klib/cache`），所以首次打包比本机慢约 1 分钟（B6：3m59s，本机 2m58s）。
- 还没在第二台机器上从零跑过；Xcode/模拟器 runtime 与清单不一致时的报错路径也还没验证。
- 0.1.0 起：工具链的全部组件作为公开仓库 GitHub Release 的附件发布（`gen-bundle-manifest.py --channel release`），插件发布到公开仓库 GitHub Pages 上的静态 Maven 仓库；两者下载都不需要 token。上传只有 `publish-toolchain.py --publish` 一条路径。

## 6. 复现：打包 → 发布 → 下载

见 README「预构建工具链」一节的四步命令。校验已打出的包：

```bash
scripts/publish-toolchain.py --dist-dir <WORKSPACE>/toolchain-dist --manifest <WORKSPACE>/toolchain-dist/toolchain.json
```

它会逐个核对字节数、sha256、2 GiB 限制和清单里有没有本机路径，然后只打印发布命令。

`COMPOSE_KLIB_CACHE` 是 `compose-source-jbmain/` 这一层，每个子目录是一个已解包的 klib
（`default/manifest`、`default/ir`、`default/linkdata`），打包时保留原目录名。

## 7. 可复现打包（Issue #96，自 `2.5.255-hotreload.11.2` 起）

### 7.1 `.11` → `.11.1` 的差异来源

逐个组件解包后比较了文件内容、tar 头和 zstd 参数。结论：zstd 不是差异来源。`zstd -19 -T0`（1.5.7）压缩同一条管道输入，
结果是确定的，与线程数无关：把两版的 8 个 tar 各自经管道重新压缩，得到的字节和已发布归档完全一致。所以归档 sha256 只由 tar 流决定。

| 组件 | 文件内容 | tar 头 | 处理 |
|---|---|---|---|
| kaldo-iossim、kaldo-macos | 不变 | mtime 不同 | 内容改了才重打，tar 头固定 |
| orc-runtime、llvm-tools、cmp-uikit-utils | 不变 | mtime 不同 | 沿用上一版归档 |
| compose-compiler-plugin（3 个文件）、compose-klibs（2025 个文件） | 不变 | mtime 不同 | 沿用上一版归档 |
| kotlin-native | 3952 个文件不变；3 个文件真实变化（`compiler.fingerprint`、编译器 jar、`hot_reload.bc`，来自 #77/#78） | mtime 不同 | 重打，tar 头固定 |

每个 tar 头还带着打包机器的本地用户名、组名和 uid。这些不影响功能，但属于本机信息，所以一并去掉。
打包内容本身已经是确定的：编译器 jar 的条目时间已统一为 1980-02-01，静态库成员的属主是 0/0、日期是 1970
（只有 cmp-uikit-utils 的 `__.SYMDEF` 例外，它是沿用的，不重新生成）。

### 7.2 做法

- `scripts/repro-pack.py tar`：条目按路径字节序排列（目录在其内容之前，和 `tar -C DIR -cf - .` 一样带 `./` 前缀），
  mtime 全部取 `SOURCE_DATE_EPOCH`，uid/gid 为 0，用户名和组名为空，目录及可执行文件的权限为 0755、其余为 0644，
  保留符号链接，不写扩展属性，只在 ustar 放不下时才写 PAX 头。然后经管道交给 `zstd -q -19 -T0`。
- `SOURCE_DATE_EPOCH` 同时用作两份清单的 `generatedAt`，因此同一输入得到的 `packaging-manifest.json` 和 `toolchain.json` 也逐字节相同。
  默认值是打包仓库 HEAD 的提交时间；发布时设为补丁所在提交的时间（`.11.2` 用 main `0016ad4`）。
- 沿用（`REUSE_FROM=<上一版 dist 目录>`）：比较组件的内容摘要（路径、类型、可执行位、文件字节、链接目标），
  如果和上一版归档解出来的一样，就逐字节复制上一版归档（只改文件名里的版本号）。
  清单为该组件记录 `reusedFrom` 和 `reuseReason`；每个组件都记录 `contentDigest`。
- 自检：每个新打出的归档都要解出来，核对内容摘要和暂存目录一致，否则打包失败。
- 发布门禁 `scripts/repro-gate.sh`：用同一份输入，在两个独立的工作目录里并行打包两次
  （各自的 cwd、`TMPDIR`、输出目录都分开），所有文件的 sha256 必须一致，否则失败，`OUT_DIR` 里什么都不写。
  通过后，在打包清单里写入 `reproducibility` 记录，`gen-bundle-manifest.py` 把它带进 `toolchain.json`。
  `--channel release` 遇到没有通过记录的清单会拒绝生成（`--no-repro-gate` 只用于本地测试）。

已知遗留：沿用的归档是 `.11.1` 的原字节，它们的 tar 头里仍有旧的本机用户名和 mtime（`.11.1` 已经公开发布过），
要等这些组件下次真正变化、重新打包时才会消失。
