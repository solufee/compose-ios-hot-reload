# 脚本说明

所有脚本都可以用环境变量覆盖路径；默认值假设你在**工作目录**（放 `main.kt`、`Info.plist`、
`klibs.txt` 的目录，作者用的是 `demo/`）里执行。

| 脚本 | 作用 | 关键环境变量 |
|---|---|---|
| `build-a.sh` | split host 编译 → 合并 payload → 嵌入 guest，产出 `ComposeHR.kexe` | `KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`IC_DIR`、`AUTO_CACHE` |
| `run-a.sh` | 组装 `.app`、安装并启动模拟器（带 EH 合成开关） | `UDID`、`KALDO_DYLIB` |
| `reload-a.sh` | 每次重新 `kotlinc-native`（旧路径，约 5 s），发最新 cache 对象 | 同上 |
| `reload-b.sh` | `reload-a.sh` + `KONAN_HOT_RELOAD_CACHE_ONLY=1`（跳过 host，约 1.5 s 编译） | 同上 |
| `../daemon/reload-c.sh` | 通过常驻守护进程编译（约 1.3 s）并发送 | 同上 + `HRC_SOCKET` |
| `../daemon/daemon-start.sh` | 启动/检查编译守护进程 | `KOTLIN_DIST`、`HRC_SOCKET_DIR` |
| `../daemon/compile-daemon.sh` | 只编译，不发送（给调试用） | 同上 |
| `merge-payloads.py` | 把 base 二进制里的 cache payload 合成一个 guest 对象；`KALDO_KEEP_COMPACT_UNWIND=1` 时保留 compact unwind | — |
| `embed-merged.py` | 把合并后的对象写进 `__TEXT,__kaldo_boot` 的 `KALD0` manifest | — |
| `verify-klibs.py` | 编译前检查 klib 列表的版本指纹，防止复用旧 Compose klib | — |
| `accept-matrix.py` | 4 类改动 × N 次热重载，检查 `RENDER <tag> page=2 count=3`、PID、p50/p90 | `RELOAD`、`START_TAG` |
| `measure-compile.py` | 只测编译：full / cache-only 各 5 次 | 同上 |
| `profile-compile.sh` | 单次编译 + `-Xprofile-phases` / `-Xdump-perf` 采样 | 同上 |
| `daemon/daemon-memtest.sh` | 连续 N 次编译，记录 RSS 与 GC 后堆 | — |
| `gen-toolchain.sh` | 生成 `toolchain.json`（公开基线 + 补丁 sha256） | `KOTLIN_DIST`、`COMPOSE_PLUGIN_JAR`、`KLIBLIST`、`PATCHES_DIR` |
| `compat-classify.py` | 兼容性分类器：`snapshot` 生成 klib 快照，`classify` 比较两份快照（退出码 0 热替换 / 3 温重启 / 2 错误）；v8 起 `baseline`/`check` 一个进程处理全部模块，`--daemon` 在守护进程里 dump，`--cache-dir` 按 klib 哈希缓存 | `KOTLIN_DIST`/`--klib-tool` |
| `gen-external-klibs.py` | 把公开 klib 固定下来：从 Maven Central / Google Maven 的模块元数据取真实 URL 和发布的 sha256，下载核对，POM 取许可证；仓库里没有的标为随包分发 | 网络 |
| `pack-toolchain.sh` | 打预构建工具链的组件（最多 9 个）tar.zst + 哈希清单（不发布），每个组件附 LICENSE 和 NOTICE | `KOTLIN_DIST`、`COMPOSE_KLIB_CACHE`、`COMPOSE_PLUGIN_JAR`、`KALDO_IOSSIM`、`KALDO_MACOS`、`ORC_RUNTIME`、`UIKIT_UTILS`、`LLVM_TOOLS_FROM`、`EXTERNAL_KLIBS`、`OUT_DIR` |
| `gen-bundle-manifest.py` | 生成发布清单（layout bundle，全部相对路径；`--channel release`、`--external-klibs`） | — |
| `publish-toolchain.py` | 校验后 dry-run 打印发布命令；`--local-repo` 做本地镜像；只有 `--publish --confirm-repo` 才上传（draft release） | `gh` |
| `fetch-toolchain.py` | 消费端：下载、校验 sha256、解包、下载公开 klib、本地化、写 `env.sh`；不读 Gradle 缓存；网络错误自动重试（指数退避） | `zstd`、`curl` |
| `test-fetch-toolchain.py` | `fetch-toolchain.py` 的重试和错误提示测试（不需要网络） | — |

## klibs.txt 格式

每行一个 klib 路径。除了 Kotlin/Native 自带的 stdlib 和平台 klib（`klib/common`、`klib/platform`，由工具链自动加载），
每一行都会作为 `-l <klib>` 传给编译器。示例见 `../demo/klibs-example.txt`，
里面的绝对路径需要按你的机器改。
