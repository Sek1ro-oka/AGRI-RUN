# evidence — 构建与测试的原始记录

本目录只放「实际跑出来的东西」，用于旁证 `TEST_REPORT_测试结果.md` 里的结论。

| 文件 | 内容 |
| --- | --- |
| `build-and-test-run.log` | 最后一次全量执行的完整 Gradle 输出：`:route-core:test`、`:simulation-core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:location-probe:lintDebug`、`:app:assembleRelease`、`:app:assembleDebug`、`:location-probe:assembleDebug`，结尾为 `BUILD SUCCESSFUL` |
| `gradle-version.txt` | `gradlew --version`：Gradle 8.11.1、Launcher JVM 21.0.3、Daemon JVM `D:\Java\jdk-21` |
| `java-version.txt` | 构建用 JDK 版本 |
| `adb-devices.txt` | `adb devices -l` 的原始输出：**只有表头，没有设备**——这是本轮「无真机」的直接证据 |

## 没有的东西（如实说明）

- 没有真机截图、录屏、`logcat`、`dumpsys location` 输出：本轮没有任何设备接入。
- 没有 UI 自动化测试报告：项目里没有编写 instrumented test（`androidTest` 只有依赖声明，没有用例）。
- 没有性能/耗电数据。

## 怎么复核

在本机（Windows）仓库根目录执行：

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-21'
$env:ANDROID_HOME = 'E:\Android\Sdk'
Set-Location 'R:\route-workshop'   # subst R: "E:\农林快跑"
.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
```

注意：必须从 `R:\`（纯 ASCII 路径）构建，直接在含中文的路径下跑单元测试会因 Gradle 测试 worker 的 `ClassNotFoundException` 失败，原因与绕法见 `docs/BUILD.md`。
