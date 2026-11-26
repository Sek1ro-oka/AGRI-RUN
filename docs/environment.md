# 构建环境基线

本文件记录项目使用的工具链与版本基线，方便换机器复现。**具体本机路径不写入仓库**；实际环境以本机的 `JAVA_HOME`、`local.properties` 与 `docs/BUILD.md` 的命令为准。

## 工具链

| 项 | 版本 |
| --- | --- |
| JDK | 17+（AGP 8.9.2 要求；仓库实测 JDK 21.0.3） |
| Gradle | 8.11.1（仓库自带 wrapper） |
| AGP | 8.9.2 |
| Android SDK | platform 35、build-tools 35.0.0、platform-tools |
| 编译参数 | `sourceCompatibility` / `targetCompatibility` 17，`compileSdk 35`，`minSdk 29`，`targetSdk 35` |

依赖版本以 `gradle/libs.versions.toml` 为唯一来源，不在文档里维护第二份清单。

## 通用要求

- JDK 用 `JAVA_HOME` 指定；Android SDK 用工程根目录 `local.properties` 的 `sdk.dir` 指定。两者都不入库。
- 工程与 SDK 路径尽量使用纯 ASCII。非 ASCII 路径在 Windows 上可能导致 Gradle 测试 worker 加载失败，也可能影响模拟器启动；必要时临时使用盘符映射，但不要把映射或本机路径写进仓库。
- 同一工程不要用两个不同路径（例如真实盘与映射盘）混跑构建；出现 `different roots` 一类错误时先 `clean`，之后固定使用一个路径。
- 构建与测试命令见 `docs/BUILD.md`；结果以实际执行输出为准，不以文档记述为准。