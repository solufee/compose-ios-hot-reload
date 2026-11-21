# 补丁说明

三份补丁分别对应三个仓库，基线都是**公开可 checkout 的提交**。每份补丁都做过
`git apply --check`（见本节末尾的验证记录）。

| 补丁 | 仓库 | 基线 commit | 内容 |
|---|---|---|---|
| `kotlin-native-hot-reload.patch` | https://github.com/JetBrains/kotlin | `8e53ee99fbc08e264828ab082aee3e448194eca8` | 三个未合并 PR 的完整改动 + 本项目的改动 |
| `llvm-kaldo.patch` | https://github.com/gabryon99/llvm-project-kotlin | `2f8844c2b35557baced9106c50b9b8758fa6a0de` | libKaldo 插件（selector 修复、compact-unwind→eh_frame、EH 注册、模块初始化器、符号外部化） |
| `compose-multiplatform-core-build.patch` | https://github.com/JetBrains/compose-multiplatform-core | 工具链 .7：官方标签 `v1.13.0-rc01` = `f39822e6a77eea5d194e4f9bb56cb6961321b849`（.6 及以前：`ee243c321ddff6b18fcf18916be1b96ef70e2363`，补丁内容相同，两处都能打上） | 构建接线，让 Compose 用 2.5.255-SNAPSHOT 出 klib；工具链 .6 起另含 `ui` 模块 iOS 源码的一处改动：热重载成功后强制完整重组（新文件 `compose/ui/ui/src/iosMain/.../scene/HotReloadRecompose.ios.kt`，`ComposeSceneMediator.ios.kt` 包一层，TECHNICAL-NOTES §1.6）。不改 Compose runtime（runtime 用 Google 发布的 klib） |

## Kotlin 补丁包含的 PR

`kotlin-native-hot-reload.patch` 不是只含本项目改动，而是**从公开 master 基线到本项目工作树**的完整 diff。
它包含下列三个 PR 的提交（本地按顺序 rebase 过，SHA 与 PR head 不同），再加上本项目自己的改动：

| PR | 标题 | PR head（GitHub 上的原始 SHA） |
|---|---|---|
| [#7234](https://github.com/JetBrains/kotlin/pull/7234) | 热重载 launcher | `7bf7bf81412672f7ccfd729b9396ed849109d0eb` |
| [#7418](https://github.com/JetBrains/kotlin/pull/7418) | split 编译（`-Xcompilation-scheme`） | `7f041585d92915a5867e0bac35ef2d11a112edcb` |
| [#7606](https://github.com/JetBrains/kotlin/pull/7606) | 热重载 runtime / Kaldo 接入 | `eb20031f74052276e9e436c1d922cf71bbdf9a0b` |

本项目在它们之上的改动（原理见 `docs/TECHNICAL-NOTES.md`）：eh_frame 合成、
`-Xhot-reload` 的 cache-only 分支、稳定的中间 klib 名、用户模块 per-file 缓存、
ObjC selector 修复的 runtime 侧配合等。补丁共 100 个文件、约 3.1k 行新增。

## 应用

```bash
# Kotlin / Native 工具链
git clone https://github.com/JetBrains/kotlin.git kotlin-hr
cd kotlin-hr
git checkout 8e53ee99fbc08e264828ab082aee3e448194eca8
git apply /path/to/patches/kotlin-native-hot-reload.patch
```

打完补丁后，`kotlin-native/konan/konan.properties` 里有三处占位符：

```
llvmHome.macos_arm64 = <YOUR_LLVM_INSTALL>
kaldoLinkerFlags.macos_arm64 = -L<KALDO_INSTALL>/lib -rpath <KALDO_INSTALL>/lib -lKaldo
kaldoLinkerFlags.ios_simulator_arm64 = -L<KALDO_INSTALL>/lib -lKaldo.iossim
```

分别改成你自己的 LLVM 安装目录和 libKaldo 安装目录（见 README「从零构建」第 1 步）。
libKaldo 只放在 `<KALDO_INSTALL>`，LLVM 安装目录里不要再放它（interfaceVersion 5 起）。

```bash
# LLVM + libKaldo（公开仓库，基线可 checkout）
git clone https://github.com/gabryon99/llvm-project-kotlin.git llvm-project
cd llvm-project
git checkout 2f8844c2b35557baced9106c50b9b8758fa6a0de
git apply /path/to/patches/llvm-kaldo.patch
```

```bash
# Compose Multiplatform core
git clone https://github.com/JetBrains/compose-multiplatform-core.git compose
cd compose
git checkout v1.13.0-rc01      # f39822e6a77e，工具链 .7；.6 用 ee243c321ddff6b18fcf18916be1b96ef70e2363
git apply /path/to/patches/compose-multiplatform-core-build.patch
```

## 验证记录

在临时目录里分别 clone/checkout 到上面的基线，再对补丁执行 `git apply --check`：

```
$ git -C /tmp/verify-kotlin rev-parse HEAD
8e53ee99fbc08e264828ab082aee3e448194eca8
$ git -C /tmp/verify-kotlin apply --check patches/kotlin-native-hot-reload.patch
（无输出，退出码 0）

$ git -C /tmp/verify-llvm rev-parse HEAD
2f8844c2b35557baced9106c50b9b8758fa6a0de
$ git -C /tmp/verify-llvm apply --check patches/llvm-kaldo.patch
（无输出，退出码 0）

$ git -C /tmp/verify-compose rev-parse HEAD
ee243c321ddff6b18fcf18916be1b96ef70e2363
$ git -C /tmp/verify-compose apply --check patches/compose-multiplatform-core-build.patch
（无输出，退出码 0）
```

Issue #32（2026-11-21，`kotlin-native-hot-reload.patch` sha256 `a34772eb…`：新增 binary option `splitPayloadList`（`native/binary-options/.../BinaryOptions.kt`），`SplitCompilationSupport.kt` 给出时写 payload 清单、`BootstrapMetadata.kt` 嵌入空清单加零填充预留）：在 JetBrains/kotlin 的基线 `8e53ee99fbc0` 上用临时 index 检查，`git apply --cached --check` 通过，打出来的 3 个文件和工作树逐字节相同（`patches/apply-check.log`）。

Issue #29（2026-11-21，`kotlin-native-hot-reload.patch` sha256 `c8bd0687…`：新增 `plugins/compose/compiler-hosted` 里 4 个文件——`ComposePlugin.kt` 的 feature flag `HotReloadStableKeys`、`DurableFunctionKeyTransformer.kt`（名字 + remember 形状作 key，重复时加序号）、`ComposableFunctionBodyTransformer.kt`（内层偏移从函数体开头算）、`AbstractComposeLowering.kt`）：在 JetBrains/kotlin 的基线 `8e53ee99fbc0` 上用临时 index 检查，`git apply --cached --check` 通过，打出来的 4 个文件和工作树逐字节相同（`patches/apply-check.log`）。

Issue #27（2026-11-20，`kotlin-native-hot-reload.patch` sha256 `4f560d86…`：runtime 的 `HotReload.cpp`/`HotReloadInternal.hpp` 把 reload 放到主队列上执行；`compose-multiplatform-core-build.patch` sha256 `42e1eba6…`：`ui` 的 `FrameChoreographer.ios.kt` 帧入口和 `HotReloadRecompose.ios.kt` 回调里当场失效）：分别在 JetBrains/kotlin 的基线 `8e53ee99fbc0` 和 compose-multiplatform-core 的官方标签 `v1.13.0-rc01`（`f39822e6a77e`）上用临时 index 检查，`git apply --cached --check` 都通过；按补丁打出来的这几个文件和工作树逐字节相同（`patches/apply-check.log`）。

Issue #5（2026-11-20，`llvm-kaldo.patch` sha256 `3e0dafa3…`，`Kaldo.cpp`/`Kaldo.hpp`：reload 时摘掉上一层 JITDylib 的宿主符号生成器，加 `KALDO_RELOAD_TIMING`）：在 gabryon99/llvm-project-kotlin 的基线 `2f8844c2b355` 上用临时 index 检查，`git apply --cached --check` 通过。

工具链 .6（2026-11-20，`compose-multiplatform-core-build.patch` sha256 `335d7a32…`，新增 `ui` 的强制重组）：在 JetBrains/compose-multiplatform-core 的基线 `ee243c321ddf` 上用临时 index 检查，`git apply --cached --check` 通过（`patches/apply-check.log`）。

工具链 .7（2026-11-20，补丁不变，仍是 `335d7a32…`）：在官方标签 `v1.13.0-rc01`（`f39822e6a77e`，提交号经 GitHub API 核对）上用临时 index 检查，`git apply --cached --check` 退出码 0；在 `v1.13.0-beta02`（`7649a819edb3`）上 `ComposeSceneMediator.ios.kt` 那一段打不上（退出码 1，beta02 还没有 #3597 的延迟 `setContent`），所以基线选 rc01（`patches/apply-check.log`）。

B11（2026-11-19，`llvm-kaldo.patch` sha256 `3bb8a987…`）：在 gabryon99/llvm-project-kotlin 的基线提交上，用临时 index
（`GIT_INDEX_FILE=<tmp> git read-tree 2f8844c2b355…`，不动工作区）检查：

```
$ git apply --cached --check patches/llvm-kaldo.patch
（无输出，退出码 0）
```

## 注意

- 三个 PR 目前都还是 open 状态，没有合并到官方 master；补丁里已经包含它们的全部提交，
  所以**不需要**再去 checkout PR 分支。
- 补丁里没有二进制、没有 klib、没有缓存。`libKaldo.dylib` / `libKaldo.iossim.dylib`
  要从 LLVM 源码自己编。
- 补丁里的所有本机路径都是占位符（`<PROJECT_ROOT>`、`<KOTLIN_NATIVE_DIST>`、
  `<GRADLE_CACHE>`、`<KONAN_HOME>`、`<HOME>`、`<YOUR_LLVM_INSTALL>`、`<KALDO_INSTALL>`），按 README
  的占位符表替换。
