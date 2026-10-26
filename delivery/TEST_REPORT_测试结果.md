# 测试结果报告 — 路线工坊 0.1.0

- 测试日期：2026-10-26（Asia/Hong_Kong）
- 被测版本：`org.localroute.app` versionName 0.1.0 / versionCode 1（release，本地自签名）
- 测试主机：Windows 11，CPU/内存见 `docs/environment.md`
- 构建工具链：JDK 21.0.3（`D:\Java\jdk-21`）、Gradle 8.11.1、AGP 8.9.2、Android SDK 35、build-tools 35.0.0
- **测试机：无。本轮没有任何真机或模拟器接入**（`adb devices -l` 输出只有 `List of devices attached`，见 `evidence/adb-devices.txt`）。

> 本报告只写实际执行过的检查。凡是需要真机才能得出的结论，一律标注「未实测」，**不代替用户做真机结论**。

## 1. 自动化单元测试

执行命令（在 `R:\route-workshop`，即 `E:\农林快跑\route-workshop` 的 subst 盘符）：

```bat
gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest ^
            :app:lintDebug :location-probe:lintDebug ^
            :app:assembleRelease :app:assembleDebug :location-probe:assembleDebug --console=plain
```

结果：`BUILD SUCCESSFUL`，退出码 0（完整输出见 `evidence/build-and-test-run.log`）。

| 模块 | 测试套件 | 用例数 | 失败 | 覆盖内容 |
| --- | --- | --- | --- | --- |
| route-core | `GeoMathTest` | 9 | 0 | 1 度纬度≈111 194.9 m、方位角 0/90/180/270、球面插值中点、跨 ±180° 插值、经度归一化、坐标范围校验（纬度 ±90、经度 ±180、NaN/Infinity 拒绝） |
| route-core | `RouteCompilerTest` | 12 | 0 | 0 点/1 点/全重复点抛 `ROUTE_INVALID`、正方形单圈=周长、两点闭合单圈=2×直线距离、相邻重复点去重、重复闭合终点去重不多算零长边、单圈<1 m 拒绝、5000 点通过 / 5001 点拒绝、prefix 距离单调 |
| route-core | `RouteEngineTest` | 11 | 0 | 1 圈恰好完成且位置精确回起点、5 圈逐圈 +1 不越界、4.5 圈不多算、超时不多走、完成容差、暂停 30 秒不计时、250/500/1000 ms 采样结果一致、调度卡顿 6 秒后距离连续、跨 ±180° 取点、`RunConfig` 边界（0.49/60.1/0/1001/99 ms 拒绝）、时长格式化 |
| route-core | `LoopStateMachineTest` | 7 | 0 | 正常迁移链 IDLE→READY→STARTING→RUNNING→PAUSED→RUNNING→COMPLETED→READY、非法迁移抛异常、`stop()` 幂等、`fail()` 记录错误码且不覆盖、COMPLETED 下 stop 为空操作 |
| simulation-core | `MockLocationSessionTest` | 12 | 0 | gps→network→fused 创建顺序、不支持项跳过并记录、可选 provider 失败回滚已创建项、`SecurityException` 映射为 `MOCK_APP_NOT_SELECTED`、重复 start 不重建、publish 写入全部 provider 且字段完整、publish 失败映射 `PUBLISH_FAILED`、未 start 时 publish 抛异常、stop 只移除本次创建且幂等、remove 抛异常不外泄、候选必须含 gps |
| app | `RouteJsonCodecTest` | 9 | 0 | 编解码往返、字段与顺序、单点草稿可保存但编译报 `ROUTE_INVALID`、`closed:false` 拒绝、未知 `schemaVersion` 拒绝、空 id 拒绝、坐标越界拒绝、超 5000 点拒绝 |
| app | `RouteRepositoryTest` | 8 | 0 | 保存/加载往返、列表按更新时间倒序、id 生成格式、非法 id 拒绝、删除幂等、损坏文件移入 `corrupt/` 保留原文件名且不被覆盖、原子写不残留 `.tmp` |
| app | `RouteEditorViewModelTest` | 9 | 0 | 加点/撤销/清空/闭合、点数上限、单圈长度随点变化、草稿（<2 点）不可运行、手动经纬度输入解析（先纬后经、非法值拒绝）、速度与圈数校验、保存后重新加载名字与点 |
| **合计** | 8 个套件 | **77** | **0** | 失败 0、错误 0、跳过 0 |

## 2. 静态检查（lint）

`lintDebug` 对两个模块均通过（`abortOnError true`）：**0 error**。

保留的 warning（均不阻塞，已在 `KNOWN_ISSUES` 记录）：

- `LogNotTimber`：项目未引入 Timber，属模板规则提示。
- `ObsoleteSdkInt`：若干 `minSdk 29` 下恒真的分支，为保证可读性未删除。
- `DataExtractionRules`、`DisableBaselineAlignment`、`SetTextI18n`（`SettingsCheckActivity.java:103` 用 `setText` 拼状态文本）。
- `UnusedResources`：`colors.xml` 中预留给地图线的 4 个颜色。

`WrongConstant` 与 `CoarseFineLocation` 两个 error 级问题已在交付前修复：旧版 `addTestProvider` 重载按 API 31 签名校验，使用处已加 `@SuppressLint("WrongConstant")` 并注明原因；探针补 `ACCESS_COARSE_LOCATION`。

## 3. 构建产物与签名

| 产物 | 大小 | 说明 |
| --- | --- | --- |
| `路线工坊-0.1.0-release.apk` | 32.88 MB | release，本地自签名；只含 `arm64-v8a`/`armeabi-v7a` 原生库 |
| `定位探针-0.1.0-debug.apk` | 11.11 MB | 独立包名 `org.localroute.probe`，debug 签名，用于验证系统位置 |
| `source.zip` | 0.18 MB | `git archive HEAD` 生成，对应提交 `4b25157`（其父提交 `dc683ae` 为功能实现） |

- `apksigner verify --print-certs`：签名有效，证书 `CN=Route Workshop Local`，SHA-256 `14f0286f4624de37fc1a013f9a15514ac04981bb1f222b77b3ce7dbb38f02a9c`。
- `aapt2 dump badging`：`package org.localroute.app`、`versionCode 1`、`versionName 0.1.0`、`minSdk 29`、`targetSdk 35`、应用名「路线工坊」、入口 `org.localroute.app.ui.MainActivity`。
- 探针包名 `org.localroute.probe`，权限只有 FINE/COARSE 定位。

## 4. 任务书 T01—T24 逐条对照

图例：**自动化** = 已被 JVM 单元测试覆盖并通过；**部分** = 逻辑层有覆盖、系统集成层未验证；**未实测** = 需要真机，本轮没有设备。

| 编号 | 场景 | 覆盖方式 | 状态 |
| --- | --- | --- | --- |
| T01 | 0 点 / 1 点 / 全重复点无法开始 | `RouteCompilerTest`（抛 `ROUTE_INVALID`）+ `RouteEditorViewModelTest`（草稿不可运行） | 自动化通过 |
| T02 | 重复终点与相邻重复点 | `RouteCompilerTest` 去重后单圈长度与无重复时一致 | 自动化通过 |
| T03 | 两点闭合 | `RouteCompilerTest`：单圈 = 2 × 直线距离，闭合边计入 | 自动化通过 |
| T04 | 1 圈 | `RouteEngineTest`：恰好完成 1 次并停止（`finished()`），位置回起点 | 自动化通过 |
| T05 | 5 圈 | `RouteEngineTest`：逐圈 +1、恰好 5、无第 6 次、不越界 | 自动化通过 |
| T06 | 整圈边界前后与最后一个 tick | `RouteEngineTest`：完成容差 1e-6 m、距离夹到目标、不越界 | 自动化通过 |
| T07 | 暂停 30 秒 | `RouteEngineTest` + `ActiveElapsedClock`：有效时间不累计、距离不增长；服务层「0 速度同位置」未在真机验证 | 部分（逻辑层通过） |
| T08 | 暂停后继续 | `RouteEngineTest`（按有效时间连续推进）+ `LoopStateMachineTest`（PAUSED→RUNNING）；真机无跳跃未验证 | 部分 |
| T09 | 连续点开始/暂停/停止 | `LoopStateMachineTest`（`stop()` 幂等）、服务层单实例由 `SimulationService` 的单一 `HandlerThread` + `PendingRunRegistry` 保证；重复点击未在真机验证 | 部分 |
| T10 | 250 / 500 / 1000 ms 采样一致性 | `RouteEngineTest`：三种 tick 同一时刻位置与距离一致 | 自动化通过 |
| T11 | 调度间隔 > 5 秒自动暂停 | 已实现（`SimulationService.MAX_TICK_GAP_NANOS`，暂停时有效时间只累计到上一次成功 tick）；**无单元测试、未真机验证** | 未实测 |
| T12 | 跨 ±180° | `GeoMathTest`（插值）+ `RouteEngineTest`（取点、方位） | 自动化通过 |
| T13 | NaN / 无限 / 越界坐标 | `GeoPoint` 构造校验 + `RouteJsonCodecTest` 越界拒绝 | 自动化通过 |
| T14 | 未选模拟应用 / 权限拒绝 / 定位关闭 | `MockLocationSessionTest` 覆盖 setup 失败与错误码映射；系统弹窗、权限拒绝的真实路径未验证 | 部分 |
| T15 | 独立读取系统位置收到移动位置 | 需要真机 + 探针 APK | 未实测 |
| T16 | Home / 切应用 / 锁屏 10 分钟 | 前台服务 + `FOREGROUND_SERVICE_LOCATION` + 有期限 WakeLock 已实现，行为未验证 | 未实测 |
| T17 | 通知暂停/继续/停止 | 通知动作与 `ACTION_PAUSE/RESUME/STOP` 已实现，真机未验证 | 未实测 |
| T18 | 旋转 / 重建 | 状态存于 `SimulationService` + `SimulationStateRepo`，界面重订阅；旋转未验证 | 未实测 |
| T19 | 完成/停止/错误释放资源 | `cleanup()` 幂等、`MockLocationSession.stop()` 只移除本次创建；真实 provider 残留未验证 | 部分 |
| T20 | 保存后杀进程重开 | `RouteRepositoryTest` 覆盖持久化；进程重启后不自动开始由服务设计保证，未验证 | 部分 |
| T21 | 地图网络失败 | 瓦片失败不影响本地引擎（架构上路线与地图解耦），未验证 | 未实测 |
| T22 | 连续运行—停止 20 次 | 未验证 | 未实测 |
| T23 | 5000 点无 ANR | `RouteCompilerTest` 覆盖 5000 点编译；界面渲染与 `pointAt` 二分查找未在真机计时 | 部分 |
| T24 | 覆盖安装保留数据 | 未验证 | 未实测 |

汇总：**自动化通过 10 条**（T01—T06、T10、T12、T13 及 T04 相关边界）、**部分通过 8 条**（T07、T08、T09、T14、T19、T20、T23 等）、**未实测 6 条**（T11、T15—T18、T21、T22、T24 中需真机项）。

## 5. 怎么把「未实测」变成「已实测」

1. 手机打开「开发者选项 → 选择模拟位置信息应用」，选中「路线工坊」。
2. 安装 `路线工坊-0.1.0-release.apk`，授予定位权限与通知权限，确认系统定位开关开启。
3. 按 `README_安装与使用.md` 画一条路线（或手动输入 `samples/` 里的合成坐标），速度 9 km/h、1 圈，点「开始」。
4. 同时安装 `定位探针-0.1.0-debug.apk`，在探针里点「开始监听」，应看到每秒约 2 条 `模拟位置` 标记的坐标，且沿路线移动 → 即 T15 通过。
5. 依次执行 T16（锁屏 10 分钟）、T17（通知按钮）、T18（旋转）、T22（连停 20 次）、T24（覆盖安装）并记录实际现象。

## 6. 本轮未做的事（如实说明）

- 没有真机、没有模拟器，因此没有任何 UI 交互、系统模拟位置、后台存活、通知动作的实测记录。
- 没有做代码审查（单人开发，未安排独立审查者）。
- 没有做性能与耗电测量（T23 只有编译期覆盖）。
- 没有联网验证 OSM 瓦片在本机网络下的加载表现。
