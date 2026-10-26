# 构建环境记录（2026-10-26）

本文件是 `gradle/libs.versions.toml` 注释里引用的那份环境快照：写清“这台机器上什么版本能跑通”。
换机器时请先照 `docs/BUILD.md` 复现，再改版本。

## 硬件与系统

| 项 | 值 |
| --- | --- |
| 开发机 | Windows（中文系统，控制台代码页 GBK） |
| 工作区 | `E:\农林快跑`（工程在 `E:\农林快跑\route-workshop`） |
| 构建用映射盘符 | `subst R: "E:\农林快跑"` → `R:\route-workshop` |
| 磁盘 | C: 32.5 GB 可用，D: 19.9 GB，E: 324.3 GB |
| 真机 | **无**（`adb devices -l` 为空） |

## Java / Gradle / SDK

| 组件 | 位置或版本 |
| --- | --- |
| JDK 21（构建用） | `D:\Java\jdk-21`（21.0.3），`JAVA_HOME` 必须指向它 |
| JDK 25（PATH 默认） | `D:\Java\jdk-25.0.2`，**不能**用来跑 Gradle 8.11.1 |
| JDK 11 / 8 | `D:\Java\jdk-11.0.12`、`D:\Java\Java8`（本项目未使用） |
| Gradle | 8.11.1，wrapper 从腾讯镜像下载 |
| Android SDK | `E:\Android\Sdk` |
| 已装 SDK 组件 | `cmdline-tools;latest`、`platform-tools` 37.0.1、`platforms;android-35`、`build-tools;35.0.0` |
| AGP | 8.9.2 |
| 编译参数 | `sourceCompatibility/targetCompatibility 17`，`compileSdk 35`，`minSdk 29`，`targetSdk 35` |

## 网络

- `dl.google.com`、`repo1.maven.org`、`mirrors.cloud.tencent.com` 可直连。
- `services.gradle.org` / `downloads.gradle.org` 不可达；本机代理 `127.0.0.1:7897` 已失效，
  构建脚本里统一清空 `HTTP_PROXY/HTTPS_PROXY`。
- 因此 `gradle-wrapper.properties` 的 `distributionUrl` 用 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`。

## 已知环境陷阱（都踩过）

1. 含中文的路径会让 Gradle 测试 worker `ClassNotFoundException` → 必须走 `subst R:`。
2. 同一工程混用 `E:\` 与 `R:\` 构建 → `different roots` dex 失败 → 先 `clean`，之后只用一个路径。
3. AGP 默认拒绝非 ASCII 路径 → `android.overridePathCheck=true`（纯 Java、无 NDK）。
4. PowerShell 的 `Get-Content` 会把 UTF-8 中文按 GBK 显示成乱码；判断“文件是否被写坏”要用
   `[System.IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)` 或 `ReadAllBytes`。
5. PowerShell 5.1 的 `Set-Content -Encoding utf8` 会写 BOM；需要无 BOM 时用
   `[System.IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`。
6. `adb` 不在 PATH 时用绝对路径 `E:\Android\Sdk\platform-tools\adb.exe`。
