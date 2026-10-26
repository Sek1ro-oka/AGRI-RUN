# evidence — 构建与测试的原始记录

本目录只放「实际跑出来的东西」，用于旁证 `TEST_REPORT_测试结果.md` 里的结论。

| 文件 | 内容 |
| --- | --- |
| `build-and-test-run.log` | 0.1.2 的全量验证输出（2026-10-27 重跑，124 个用例）：完整 Gradle 输出：`--rerun-tasks` 强制重跑三个模块的单元测试，再跑 `:app:lintDebug`、`:location-probe:lintDebug`、`:app:assembleRelease`、`:app:assembleDebug`、`:location-probe:assembleDebug`；开头记录时间与工具链路径，结尾 `GRADLE_EXIT=0`、`BUILD SUCCESSFUL` |
| `lint-summary.txt` | 两个模块 `lintDebug` 的 error/warning 统计与 warning 类别（0.1.2：app 0 error / 16 warning，探针 0 error / 1 warning） |
| `gradle-version.txt` | `gradlew --version`：Gradle 8.11.1、Launcher JVM 21.0.3、Daemon JVM `D:\Java\jdk-21` |
| `java-version.txt` | 构建用 JDK 版本 |
| `adb-devices.txt` | `adb devices -l` 的原始输出：**只有表头，没有设备**——这是本轮「无真机」的直接证据 |

## 没有的东西（如实说明）

- 没有真机截图、录屏、`logcat`、`dumpsys location` 输出：本轮没有任何设备接入。
- 没有 UI 自动化测试报告：项目里没有编写 instrumented test（`androidTest` 只有依赖声明，没有用例）。
- 没有性能/耗电数据。
- `SimulationService` 没有自动化测试，因此日志里也看不到它的执行证据。

## 怎么复核

在本机（Windows）仓库根目录执行：

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-21'
$env:ANDROID_HOME = 'E:\Android\Sdk'
Set-Location 'E:\agri-run\route-workshop'
.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
```

工程位于纯 ASCII 路径，直接构建即可。早期工程在含中文的路径下时，Gradle 测试 worker 会因
`ClassNotFoundException` 整体加载失败（当时用 `subst` 映射盘符绕开），原因与处理见 `docs/BUILD.md`；
如果你把源码放到含中文的目录再构建，仍会遇到同样的问题。
