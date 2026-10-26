# 本地 Issue 清单

本项目为单人本地开发、**无远端仓库**，因此没有 GitHub Issue。按 agent 规则用本文件代替：
每条 Issue 记录编号、初始状态、解决方式与验证证据。状态只写“已关闭/未关闭”，不写没有证据的“已验证”。

## 已关闭

| 编号 | 标题 | 处理 | 证据 |
| --- | --- | --- | --- |
| I-001 | AGP 拒绝含中文的工程路径 | `gradle.properties` 设 `android.overridePathCheck=true`，并在 `docs/BUILD.md` 写清前提与退路 | `:app:assembleDebug` 构建通过 |
| I-002 | Gradle 测试 worker 在中文路径下 `ClassNotFoundException` | 用 `subst R: "E:\农林快跑"` 映射 ASCII 盘符，统一在 `R:\route-workshop` 构建 | 最小 ASCII 工程对照实验通过；77 个用例全绿 |
| I-003 | 混用 `E:\` 与 `R:\` 两条路径构建导致 `different roots` dex 失败 | 先 `gradlew clean`，此后只在 `R:\` 构建 | `build-clean.log` / 重建日志 `BUILD SUCCESSFUL` |
| I-004 | Gradle wrapper 无法下载（`services.gradle.org` 不可达） | `--gradle-distribution-url` 指向腾讯镜像并写入 `gradle-wrapper.properties` | `gradlew.bat --version` 输出 Gradle 8.11.1 |
| I-005 | 路线文件半写风险 | 写 `.json.tmp` + `FileDescriptor.sync()` + 原子 `move`；损坏文件移入 `corrupt/` 且保留原文件名 | `RouteRepositoryTest` 8 个用例 |
| I-006 | `delete()` 二次删除返回 true（不幂等） | 先判 `exists()` 再 `delete()` | `RouteRepositoryTest.deleteRemovesFileAndIsIdempotent` |
| I-007 | `RouteEngine` 有效用时 `01:00:00` 显示成 `1:00:00` | 格式串改 `%02d:%02d:%02d` | `RouteEngineTest.formatDuration_handlesMinutesAndHours` |
| I-008 | 状态机允许 `STARTING` 直接 `resume()`、`COMPLETED` 被 `stop()` 改写成 `STOPPED` | `resume()` 前置校验；`stop()` 在非运行状态为空操作 | `LoopStateMachineTest` 9 个用例 |
| I-009 | 解码器对空 id / 单点草稿的行为与测试期望不一致 | 空 id 在构造器抛 `IllegalArgumentException`、解码路径抛 `RouteFormatException`；单点草稿允许保存但不可编译 | `RouteJsonCodecTest` |
| I-010 | 跨日界线插值测试容差过严 | 容差改为 1e-3 度并注释说明大圆中点偏极 | `GeoMathTest.interpolate_acrossAntimeridian_staysNearDateLine` |
| I-011 | T11：调度间隔 > 5 秒时没有保护 | tick 中检测实际间隔，超过 5 秒即自动暂停，有效时间只累计到上一次成功 tick，并在通知/界面说明原因 | `SimulationService.autoPauseForSchedulerGap`（真机行为未实测） |
| I-012 | release 包无签名配置，`keystore` 口令有泄露风险 | `keystore.properties` 条件读取，`keystore/` 与 `keystore.properties` 均在 `.gitignore` | `docs/BUILD.md` 第 4 节 |
| I-013 | 定位探针只有占位界面，无法独立验证坐标 | 实现 `ProbeActivity`：多 provider 监听、模拟位置标记、累计位移、停止汇总 | 构建产物 `location-probe-debug.apk`（真机未实测） |
| I-014 | 大路线（5000 点）塞进 Intent 可能触发体积上限 | 用进程内 `PendingRunRegistry` 传请求，Intent 只带 `runId` | 代码审查 `MainActivity` → `SimulationService` 路径 |
| I-015 | 通知栏缺少暂停/继续/停止 | 常驻通知两个 Action，与页面共用 `handlePause/handleResume/handleStop` | 代码审查（真机未实测） |

## 未关闭

| 编号 | 标题 | 现状 | 计划 |
| --- | --- | --- | --- |
| I-101 | 无真机，T07/T11/T14–T24 未实测 | `adb devices` 为空，全部真机项在 `docs/TEST_PLAN.md` 标 ⚠️ | 接入真我 GT Neo5 后按 TEST_PLAN 第 3 节顺序复核并回填 |
| I-102 | B 操场坐标未知，无内置示例 | 任务书禁止猜坐标；`samples/` 只有 `(0,0)` 附近合成数据 | 用户在真机点选或提供经纬度后另存为路线 |
| I-103 | OSM 公共瓦片的使用政策风险 | 已写署名、未自定义 UA、无离线预取 | 正式长期使用前换自建/商业瓦片源 |
| I-104 | 第三方 App 是否采信模拟位置未知 | 未做任何检索与承诺 | 属被测方行为，不在本工具范围内 |
| I-105 | 无导出 GPX/GeoJSON 能力 | 任务书 P0 未要求 | 若需要再进 P1 |
| I-106 | 未做独立的代码审查（单人开发，无第二人） | 本轮我同时承担指挥与实现 | 交付报告里如实标注“未安排独立审查” |
