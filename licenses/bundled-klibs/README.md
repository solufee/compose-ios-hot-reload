# Bundled klibs: origin and licenses

The toolchain bundle's `bundled-klibs` component contains two klibs, unchanged:

| File | Coordinate | sha256 |
|---|---|---|
| `skiko-iosSimulatorArm64Main-0.157.0-alpha01.klib` (45,633,638 bytes) | `org.jetbrains.skiko:skiko-iossimulatorarm64:0.157.0-alpha01` | `c9bfa193a182f7ad…` |
| `skiko-iosSimulatorArm64Cinterop-uikitMain-0.157.0-alpha01.klib` (3,704 bytes) | `org.jetbrains.skiko:skiko-iossimulatorarm64:0.157.0-alpha01:cinterop-uikit` | `66a91a5fe992ae4e…` |

**Origin.** Both are published by JetBrains in the Compose Multiplatform dev repository
`https://packages.jetbrains.team/maven/p/cmp/dev` (also reachable as
`https://redirector.kotlinlang.org/maven/compose-dev`), as
`org/jetbrains/skiko/skiko-iossimulatorarm64/0.157.0-alpha01/skiko-iossimulatorarm64-0.157.0-alpha01.klib` and
`…-0.157.0-alpha01-cinterop-uikit.klib`. The Gradle module metadata there lists the same sha256 and sizes as above.

**Why they are bundled.** All other public klibs the toolchain needs are downloaded by `fetch-toolchain.py` from
Maven Central or Google Maven, pinned by sha256. This pre-release skiko version is in neither of the two, so the
files are redistributed in the bundle instead.

## Licenses

- skiko: Apache License 2.0 — `skiko-LICENSE.txt`, and its NOTICE `skiko-NOTICE.txt`
  (from https://github.com/JetBrains/skiko at tag `v0.157.0-alpha01`).
- skiko statically links Skia `m157-8985a65621` (https://github.com/JetBrains/skia, release `m157-8985a65621`):
  BSD-3-Clause, Copyright (c) 2011 Google Inc. — `skia-LICENSE.txt`. This includes the Skia modules in the klib
  (`libskia`, `libskparagraph`, `libskshaper`, `libskunicode_*`, `libskresources`, `libsvg`, `libskia_ganesh_ext`).
- Third-party libraries that Skia links statically into the same klib (license texts in `third-party/`, taken
  from each project's upstream repository):

| Library in the klib | Project | License | File |
|---|---|---|---|
| `libzlib.a` | zlib | Zlib | `third-party/zlib.txt` |
| `libwebp.a`, `libwebp_sse41.a` | libwebp | BSD-3-Clause | `third-party/libwebp.txt` |
| `libpng.a` | libpng | libpng License (PNG Reference Library License v2) | `third-party/libpng.txt` |
| `libjpeg.a` | libjpeg-turbo | IJG, BSD-3-Clause, Zlib | `third-party/libjpeg-turbo.md` |
| `libpiex.a` | piex | Apache-2.0 | `third-party/piex.txt` |
| `libicu.a` | ICU | Unicode License v3 | `third-party/icu.txt` |
| `libharfbuzz.a` | HarfBuzz | MIT ("Old MIT") | `third-party/harfbuzz.txt` |
| `libexpat.a` | Expat | MIT | `third-party/expat.txt` |
| `libdng_sdk.a` | Adobe DNG SDK | DNG SDK License | `third-party/dng_sdk.txt` |
