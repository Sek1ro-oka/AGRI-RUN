# 测试结果报告 — 路线工坊 0.2.0

- 测试日期：2026-10-27（Asia/Hong_Kong）
- 被测版本：`org.localroute.app` versionName 0.2.0 / versionCode 6（release，本地自签名）
- 测试主机：Windows 11，CPU/内存见 `docs/environment.md`
- 构建工具链：JDK 21.0.3（`D:\Java\jdk-21`）、Gradle 8.11.1、AGP 8.9.2、Android SDK 35、build-tools 35.0.0
- **测试机**：真机（真我 GT Neo5）仍未接入自动化；本轮按用户要求装了 **API 35 x86_64 模拟器**，
  把「运行 → 另一应用读到 mock 帧 → 传送 → GPX 往返 → 底图坐标对齐」跑通并留下证据（见第 8 节与 `evidence/emulator/`）。
  **模拟器不等于真机**：厂商 ROM 的模拟位置白名单、后台冻结策略无法用 AOSP 模拟器复现，相关项仍标「未实测」。

> 本报告只写实际执行过的检查。凡是需要真机才能得出的结论，一律标注「未实测」，**不代替用户做真机结论**。

## 1. 自动化单元测试

执行命令（工程现已位于纯 ASCII 路径 `E:\agri-run\route-workshop`）：

```bat
gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest ^
            :app:lintDebug :location-probe:lintDebug ^
            :app:assembleRelease :app:assembleDebug :location-probe:assembleDebug --console=plain
```

结果：`BUILD SUCCESSFUL`，退出码 0（完整输出见 `evidence/build-and-test-run.log`）。

| 模块 | 测试套件 | 用例数 | 失败 | 覆盖内容 |
| --- | --- | --- | --- | --- |
| route-core | `GeoMathTest` | 9 | 0 | 1 度纬度≈111 194.9 m、方位角 0/90/180/270、球面插值中点、跨 ±180° 插值、经度归一化、坐标范围校验（纬度 ±90、经度 ±180、NaN/Infinity 拒绝） |
| route-core | `RouteCompilerTest` | 17 | 0 | 0 点/1 点/全重复点抛 `ROUTE_INVALID`、正方形单圈=周长、两点闭合单圈=2×直线距离、相邻重复点去重、重复闭合终点去重不多算零长边、单圈<1 m 拒绝、5000 点通过 / 5001 点拒绝、prefix 距离单调；**不闭合**：3 点往返单圈=2×单程且折返点正确、正方形不闭合比闭合长、两点往返、往返不足 1 米与单点均拒绝 |
| route-core | `RouteEngineTest` | 13 | 0 | 1 圈恰好完成且位置精确回起点、5 圈逐圈 +1 不越界、4.5 圈不多算、超时不多走、完成容差两侧（差 2e-6 m 不完成、进入 1e-6 m 视为完成）、单圈恰好 1 米附近（略大通过 / 0.8 米拒绝）、暂停 30 秒不计时、250/500/1000 ms 采样结果一致、调度卡顿 6 秒后距离连续、跨 ±180° 取点、`RunConfig` 边界（0.49/60.1/0/1001/99 ms 拒绝）、时长格式化 |
| route-core | `LoopStateMachineTest` | 7 | 0 | 正常迁移链 IDLE→READY→STARTING→RUNNING→PAUSED→RUNNING→COMPLETED→READY、非法迁移抛异常、`stop()` 幂等、`fail()` 记录错误码且不覆盖、COMPLETED 下 stop 为空操作 |
| route-core | `TickGapPolicyTest` | 10 | 0 | 默认上限 5 秒、没有上次 tick 不暂停、恰好 5 秒不暂停、5 秒 + 1 ns 暂停、时钟回退不暂停、自定义上限、原因文案含秒数与「自动暂停/继续」、负间隔不出负数、整秒与一位小数格式化、非正上限拒绝 |
| simulation-core | `MockLocationSessionTest` | 16 | 0 | gps→network→fused 创建顺序、不支持项跳过并记录、可选 provider 创建失败只跳过不影响 gps、可选 provider 启用失败被撤掉并跳过、gps 启用失败整体回滚、上次崩溃残留的 provider 先清理再创建、`SecurityException` 映射为 `MOCK_APP_NOT_SELECTED`、重复 start 不重建、publish 写入全部 provider 且字段完整、publish 失败映射 `PUBLISH_FAILED`、未 start 时 publish 抛异常、stop 只移除本次创建且幂等、remove 抛异常不外泄、候选必须含 gps、stop 先停用再移除（部分 ROM 只认停用） |
| app | `RouteJsonCodecTest` | 9 | 0 | 编解码往返、字段与顺序、单点草稿可保存但编译报 `ROUTE_INVALID`、`closed:false` 往返保留并编译成往返路线、未知 `schemaVersion` 拒绝、空 id 拒绝、坐标越界拒绝、超 5000 点拒绝 |
| app | `RouteRepositoryTest` | 10 | 0 | 保存/加载往返、列表按更新时间倒序、id 生成格式、非法 id 拒绝、删除幂等、损坏文件移入 `corrupt/` 保留原文件名且不被覆盖、文件名为 A 而内容 id 为 B 时被隔离不进列表、原子写不残留 `.tmp` |
| app | `RouteEditorViewModelTest` | 19 | 0 | 加点/撤销/清空/闭合、点数上限、单圈长度随点变化、草稿（<2 点）不可运行、手动经纬度输入解析（先纬后经、非法值拒绝）、速度与圈数校验、保存后重新加载名字与点；**闭合开关**：切换后 dirty、圈长变长、文案含「原路返回」、`toDefinition().closed()` 同步、载入不闭合路线保持不闭合；单点请求暂存与清理；传送模式默认关、可开关、开关不影响草稿点 |
| route-core | `GpxCodecTest`（0.2.0 新增） | 15 | 0 | 编码含声明/`trk`/`trkseg`/`trkpt` 与 6 位小数、名称转义、空点集拒绝、解码往返保序、忽略 `wpt`/`rte`、缺 `lat`/`lon` 抛异常、非数字与越界拒绝、XML 声明缺失仍可解析、空白文件拒绝、Doctype 被禁用（XXE） |
| app | `RunRequestTest`（新增） | 4 | 0 | 路线请求携带路线与配置、单点请求无路线且速度默认、单点拒绝 null、每个请求有独立 id |
| route-core | `ChinaShiftTest`（0.1.2 新增） | 7 | 0 | 境外判定（伦敦为真、北京/上海为假）、境外原样返回、北京偏移 100–1500 米且经纬均增大、WGS→GCJ→WGS 往返误差 < 0.5 m、境外逆向不变、极端点换算后仍合法 |
| simulation-core | `LocationVerifierTest`（0.1.2 新增） | 8 | 0 | 一致判 `ACCEPTED`、超容差判 `MISMATCH`、未收到判 `NOT_RECEIVED`、无模拟标记判 `NOT_MARKED_MOCK`、无期望值判 `NO_EXPECTED`、距离计算与缺侧返回 -1、文案包含「目标 App」 |
| **合计** | 13 个套件 | **144** | **0** | 失败 0、错误 0、跳过 0（route-core 78 / simulation-core 24 / app 42） |

## 2. 静态检查（lint）

`lintDebug` 对两个模块均通过（`abortOnError true`）：**0 error / 16 warning**（app），探针模块 0 error / 1 warning。

保留的 warning（均不阻塞，已在 `KNOWN_ISSUES` 记录）：

- `LogNotTimber`：项目未引入 Timber，属模板规则提示。
- `ObsoleteSdkInt`：若干 `minSdk 29` 下恒真的分支，为保证可读性未删除。
- `DataExtractionRules`、`DisableBaselineAlignment`、`SetTextI18n`（`SettingsCheckActivity` 用 `setText` 拼状态文本）。

已修复的 error 级问题：`WrongConstant`（旧 `addTestProvider` 重载按 API 31 校验，使用处加 `@SuppressLint` 并注明原因）、`CoarseFineLocation`（探针补 `ACCESS_COARSE_LOCATION`）、
`MockLocation`（0.1.1 确实需要声明 `ACCESS_MOCK_LOCATION`，已加 `tools:ignore` 并写明理由）。未使用资源（`map_status_failed*`、4 个预留颜色）已删除，`UnusedResources` 警告清零。

## 3. 构建产物与签名

| 产物 | 大小 | 说明 |
| --- | --- | --- |
| `路线工坊-0.2.0-release.apk` | 32.93 MB | release，本地自签名；只含 `arm64-v8a`/`armeabi-v7a` 原生库 |
| `定位探针-0.1.0-debug.apk` | 10.59 MB | 独立包名 `org.localroute.probe`，debug 签名，用于验证系统位置（本版未改动） |
| `source.zip` | 约 0.2 MB | `git archive HEAD` 生成；源码快照对应的提交见 `git log -1`（归档内容即该提交的树） |

- 调试包 `app-debug.apk` 因为额外打了 `x86_64`（模拟器用），体积涨到 48.12 MB；release 不受影响。
- `apksigner verify --print-certs`：签名有效，证书 `CN=Route Workshop Local`，SHA-256 `14f0286f4624de37fc1a013f9a15514ac04981bb1f222b77b3ce7dbb38f02a9c`（与 0.1.0—0.1.2 同一把 key，可覆盖安装）。
- `aapt2 dump badging`：`package org.localroute.app`、`versionCode 6`、`versionName 0.2.0`、`minSdk 29`、`targetSdk 35`、应用名「路线工坊」、入口 `org.localroute.app.ui.MainActivity`；
  权限清单里包含 `android.permission.ACCESS_MOCK_LOCATION`（这就是修好“模拟位置应用列表里没有本应用”的关键）。
- 探针包名 `org.localroute.probe`，权限只有 FINE/COARSE 定位。

## 4. 任务书 T01—T24 与 0.1.1/0.1.2 新增 T25—T31 逐条对照

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
| T11 | 调度间隔 > 5 秒自动暂停 | 策略抽到 `TickGapPolicy`（10 个单测覆盖 5 秒边界、时钟回退、文案），服务在 tick 里命中即暂停且有效时间只累计到上一次成功 tick；**服务接线本身未真机验证** | 部分（策略已单测） |
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
| T25 | 开发者选项的模拟位置应用列表里出现本应用 | 0.1.1 清单声明 `ACCESS_MOCK_LOCATION`；`aapt2 dump badging` 已确认 APK 里含该权限（AOSP `AppPicker` 按 `requestedPermissions` 过滤） | 部分（打包已核实，列表未真机确认） |
| T26 | 地图可见与失败可诊断 | 三种瓦片源 + 10 秒看门狗 + 底部状态条 + 浅灰底图；无网络时手动输入/单点发送仍可用 | 未实测 |
| T27 | 不闭合路线的圈长与折返 | `RouteCompilerTest`（5 个新用例）+ `RouteEditorViewModelTest`（2 个）+ `RouteJsonCodecTest`（1 个）覆盖数学与持久化；界面与真机行为未验证 | 部分（逻辑层通过） |
| T28 | 单点位置发送 | `RunRequestTest`（4 个用例）覆盖单点请求构造；服务端固定坐标输出与通知行为未验证 | 部分（逻辑层通过） |
| T29 | 传送到地图选点 | `RouteEditorViewModelTest` 覆盖传送模式开关；模拟器上实测「进入传送模式 → 点地图 → 位置搬到该点、速度 0、`dumpsys location` 显示新坐标 + `mock`」；真机未验证 | **已实测（模拟器）** |
| T30 | 高德底图与 GCJ-02 换算 | `ChinaShiftTest`（7 个用例）；模拟器上默认高德、瓦片可见，输入 WGS-84 `39.906297,116.395157` 落在「天安门东」站图标上；真机移动网络未验证 | **已实测（模拟器）** |
| T31 | 位置自检结论 | `LocationVerifierTest`（8 个用例）；模拟器上诊断页能读到 `gps` 的 mock 帧并判「系统已接受」 | **已实测（模拟器）** |
| T32 | 首点自动放大与视野外取景 | 模拟器上加点即放大到 16 级、第二点不在视野内时自动重新取景 | **已实测（模拟器）** |
| T33 | GPX 导出/导入 | `GpxCodecTest`（15 个用例）；模拟器导出到 `Download/路线-*.gpx` 后再导入，节点与单圈长度一致 | **已实测（模拟器）** |
| T34 | OSM 不可达时的提示 | 模拟器切到 OSM 官方后地图区出现原因提示，路线与运行不受影响 | **已实测（模拟器）** |
| T35 | 独立应用读到系统 mock 位置（原 T15） | 探针收到 11 帧、累计 12.6 米、逐帧「模拟位置」；`dumpsys location` 同时显示 `mock` | **已实测（模拟器）** |

汇总（口径：自动化 = 纯 JVM 覆盖；模拟器 = 已在 API 35 模拟器上看到预期现象；未实测 = 必须真机）：
**纯自动化 10 条**（T01—T06、T10、T12、T13）、**模拟器已实测 7 条**（T29—T35）、
**部分 8 条**（T07、T08、T09、T11、T14、T19、T23、T25）、**未实测 8 条**（T16、T17、T18、T20、T21、T22、T24、T26）。
其中 T07/T16/T17/T18/T22/T24 只能在真机上判断（后台冻结、锁屏、通知动作、旋转、覆盖安装），模拟器结论不作数。

## 5. 怎么把剩下的「未实测」变成「已实测」

模拟器上已经做完的（T29—T35）不必重复；下面这些必须在真机上按顺序做：

1. 手机打开「开发者选项 → 选择模拟位置信息应用」，选中「路线工坊」（0.1.1 起才会出现在列表里）。
2. 安装 `路线工坊-0.2.0-release.apk`，授予定位权限与通知权限，确认系统定位开关开启。
3. 按 `README_安装与使用.md` 画一条路线（或手动输入 `samples/` 里的合成坐标），速度 9 km/h、1 圈，点「开始」。
4. 同时安装 `定位探针-0.1.0-debug.apk`，在探针里点「开始监听」，应看到每秒约 2 条 `模拟位置` 标记的坐标，且沿路线移动。
5. 依次执行 T16（锁屏 10 分钟）、T17（通知按钮）、T18（旋转）、T20（杀进程后重开）、T22（连停 20 次）、
   T24（覆盖安装）、T25（列表出现本应用）、T26（真机网络下的底图排查）、T07（暂停 30 秒）、
   T11（调度间隔 > 5 秒自动暂停）、T14（未授权/未选应用时的错误提示）并记录实际现象。

## 6. 本轮没做的事（如实说明）

- 没有真机：用户在真我 GT Neo5 上的四项反馈已按 0.1.1/0.1.2 修改，但**真机复验仍由用户做**。
- 有模拟器：API 35 x86_64 AVD 上跑通了运行、独立应用读帧、传送、GPX 往返、底图与坐标换算（见第 8 节），
  但 AOSP 行为与厂商 ROM 不同，T07/T16/T17/T18/T20/T22/T24 这些「系统给不给后台」的项不能拿模拟器代替。
- 独立审查只覆盖 JVM 层与服务/界面代码的静态走查；`SimulationService` **仍然没有任何自动化测试**
  （需要 Robolectric 或 instrumentation），这是本轮最大的测试缺口。
- 没有做性能与耗电测量（T23 只有编译期覆盖）。
- 没有在真机移动网络下验证高德/OSM 的可用性。

## 7. 独立审查（第二轮）与据此做的修改

审查方式：另起一个只读 subagent（不共享本会话上下文），要求它逐行读源码、自己跑单元测试、按 P0/P1/P2 分组报告；它**没有改任何文件**，只产生 `build/` 输出。

审查结论：核心数学（Haversine、方位角、跨 ±180° 插值、按距离二分取点、圈数与完成判定）**未发现缺陷，没有 P0**；问题集中在「模拟位置 provider 生命周期」与「服务线程模型」。

据此修改并补测（全部有单元测试或静态可查，真机未验证）：

| 审查发现 | 处理 | 证据 |
| --- | --- | --- |
| 上次异常退出残留的测试 provider 会让应用永久无法运行，还误报「设备不支持」 | `MockLocationSession.start()` 与设置页探测都先尝试清理残留；探测新增 `ALLOWED_AFTER_CLEANUP` 结果并在界面说明 | `MockLocationSessionTest.staleProviderFromPreviousCrash_isClearedBeforeCreating` |
| `add` 成功但 `enable` 失败时 provider 未被记录，回滚删不掉，自己制造残留 | `createdProviders.add()` 提前到 `addTestProvider` 之后、`enable` 之前；启用失败还会单独撤掉该 provider | `gpsEnableFailure_rollsBackEverything`、`optionalEnableFailureIsSkippedAndProviderRemoved` |
| 可选 provider（network/fused）失败会让整次运行失败，与「GPS 必需、其余尽力」矛盾 | 改为非必需 provider 失败只记入 `skippedProviders` 并继续；只有 gps 失败或一个都没建成才整体失败 | `optionalProviderFailureIsSkippedNotFatal` |
| `handleStart` 跑主线程、其余命令在 Handler 线程，共享字段无同步；`onStartCommand` 无兜底 | START 也改为投递到服务 Handler；`post()` 统一包一层 `runGuarded`，异常转 `ERROR` 而不是崩进程；`stopped` 加 `volatile` | 代码走查（服务层暂无单测） |
| 早退路径（无请求/重复停止/未知动作）不写终态，界面可能永远停在「运行中」 | 新增 `stopAndPublishStoppedIfActive()`，界面处于运行态时先补一条 STOPPED 快照再退出 | 代码走查 |
| 通知节流用挂钟时间、每 tick 重复 `startForeground`、所有 action 都先发「准备中」 | 改用 `SystemClock.elapsedRealtime()` 节流；后续更新用 `NotificationManager.notify`；只有 ACTION_START 发「准备中」 | 代码走查 |
| 手势监听在 map 就绪前注册，静默失效 → 运行中地图无法拖动 | `registerGestureListener()` 记录意图，`onMapReady` 里补挂 | 代码走查 |
| 权限弹窗期间旋转丢请求，点「允许」后毫无反应 | 待启动参数搬进 `RouteEditorViewModel`；拿不到时明确提示「请再点一次开始」 | 代码走查 |
| 主线程读写磁盘（保存、列表、诊断） | 保存/列表/加载改到单线程 `Executor`，回调回主线程；`onDestroy` 里 `shutdown()` | 代码走查 |
| 原子写没 fsync 目录、tmp 名固定、`list()` 用内容 id | 增加目录 fsync（平台不支持时忽略）、tmp 名加随机后缀、文件名与内容 id 不一致时隔离 | `listQuarantinesFileWhoseNameDoesNotMatchContentId`、`saveLeavesNoTemporaryFilesBehind` |
| 完成容差、单圈 1 米边界无测试 | 补 `completionThreshold_boundaryEitherSide`、`lapLengthNearOneMeter_isAcceptedOrRejected` | 用例数 88 → 95 |

仍未处理（已写入 `KNOWN_ISSUES_已知问题.md`）：`SimulationService` 无自动化测试、残留 provider 在真机上是否跨进程存活待验证、`fused` provider 能否注册因 ROM 而异、通知 action 冷启动前台服务在 Android 14 上的限制、第三方应用（尤其走 GMS Fused）是否接受这套模拟位置。
## 8. 模拟器实测（0.2.0，2026-10-27）

为什么做：用户指出「还没办法真正实现虚拟定位」和「地图太老」。这两条光靠读代码无法回答，于是本机装了一个
API 35 / x86_64 的 AVD，把整条链路真的跑了一遍。**模拟器不是真机**，结论只覆盖 AOSP 行为。

| 检查 | 怎么做的 | 看到了什么 | 结论 |
| --- | --- | --- | --- |
| 底图可用 | 清数据后冷启动 | 默认高德、中文路网与地铁站名可见，无黑屏 | 通过 |
| 选点即取景 | 点一下地图 | 单点自动放大到 16 级并居中到「台基厂头条」一带 | 通过 |
| 坐标换算 | 手动输入 WGS-84 39.906297,116.395157 | 点落在高德「天安门东」站图标上（不做换约会偏约 500 米） | 通过 |
| 真运行 | 两点路线，9 km/h，5 圈 | 状态「运行中」、速度 9.0 km/h、单圈 1.02 km、累计 39.7 m、有效用时 00:15、0/5 圈 | 通过 |
| 系统真的收到 | db shell dumpsys location | Location[gps 39.906032,116.394823 ... vel=2.5 bear=224.0 mock]，另有 fused 记录 | 通过 |
| 别的应用能不能读 | 装 org.localroute.probe 独立探针 | 收到 11 帧、累计位移 12.6 米、逐帧标「模拟位置」、spd=2.50m/s | 通过 |
| 传送 | 菜单「传送选点」→ 点地图 | 「当前定位」立刻变成点击处，dumpsys 显示 el=0.0 mock | 通过 |
| GPX | 菜单导出 → 下载目录 → 再导入 | 路线-20261026-181123.gpx（306 B，WGS-84 6 位小数），导入后 2 节点、单圈 1.02 km | 通过 |
| 停止幂等 | 连点「停止」 | mock provider override 的添加/移除成对出现，无残留 | 通过（模拟器） |
| OSM 兜底 | 切到 OSM 官方源 | 地图区提示不可达原因，路线与运行不受影响 | 通过 |

本轮由实测发现并修掉的问题（都已进代码）：

1. **OSM 在国内不可达**（	ile.openstreetmap.org 解析到黑洞地址、TLS 握手直接失败）→ 默认源换成高德，并加瓦片探测与提示。
2. **加第一个点看不到**——LatLngBounds 对单点不缩放，等于没反应 → 单点时改用 16 级居中。
3. **高德 style=8 返回纯白瓦片** → 改用 style=7（带路网与注记）。
4. **模拟器装不上调试包**（debug 只打 arm ABI）→ debug 追加 x86_64，release 不变。

没能在模拟器上证明的事：厂商 ROM 的模拟位置白名单、锁屏与后台冻结、通知按钮在冷启动下的行为、
真机移动网络下高德/OSM 的实际可用性、耗电与温升。这些仍以真机复验为准。

## 9. 0.2.0 实测与回归（2026-10-27）

### 9.1 本轮做了什么

按 `docs/05_对标审查与改进方案.md` 的 A—G 逐项落地：默认开口草稿、显式闭合复选框、到终点停止/原路往返/绕圈三种终点策略、配速按钮与运行中改速、切地图源后重放草稿、多段 GPX 只取首段、schema v2 兼容 v1 文件。测试从 144 增至 **173**（route-core 102 / simulation-core 24 / app 47，13 个套件；lint 0 error、app 29 warning）。

### 9.2 自动化用例

| 套件 | 用例数 |
| --- | --- |
| GeoMathTest | 9 |
| RouteCompilerTest | 21 |
| RouteEngineTest | 17 |
| EndBehaviorTest | 7 |
| DistancePhaseTest | 8 |
| LoopStateMachineTest | 7 |
| TickGapPolicyTest | 10 |
| ChinaShiftTest | 7 |
| GpxCodecTest | 16 |
| MockLocationSessionTest | 16 |
| LocationVerifierTest | 8 |
| RouteJsonCodecTest | 12 |
| RouteRepositoryTest | 10 |
| RouteEditorViewModelTest | 20 |
| RunRequestTest | 4 |

### 9.3 模拟器实测（API 35 x86_64）

| 检查 | 结果 |
| --- | --- |
| 新草稿默认开口 | 3 点后提示「3 个节点 · 单程 66.4 米（到终点停止）」 |
| 勾选闭合 | 提示变「3 个节点 · 闭合 · 单圈 181.4 米」 |
| 开口 + 原路往返 | 提示「3 个节点 · 原路往返 · 单程 66.4 米 · 往返 132.7 米」 |
| 到终点停止实跑 | 66.4 m / 00:26 走完，状态「已完成」，圈数 1/1，位置停在末点 |
| 运行中改速 | 9 → 12 km/h 后速度栏变 12.0，距离继续累加、位置连续 |
| 系统侧读数 | `dumpsys location` 出现 `Location[gps 53.388254,-35.890951 ... vel=2.5 bear=54.8 mock]` |

本轮由实测暴露并修复了三个真缺陷：闭合态下程序性恢复单选导致闪退、往返模式「单程」被算成 1.5 倍、多段 GPX 段间被连成直线。

### 9.4 仍未实测

真机（真我 GT Neo5）复验、锁屏/切应用/强杀重开、通知控制、悬浮窗授权流程、官方高德/百度 SDK。这些项目和之前一样保留在 `KNOWN_ISSUES_已知问题.md` 与本表第 5 节，没有因为「模拟器跑通了」而被标成已验证。

## 10. 补充：可编辑配速与地图 Key 适配层（0.2.0 build 6）

- 配速改为三种具名预设（行走 5.0 / 跑步 9.0 / 骑行 15.0 km/h，单击套用、长按改值、可恢复默认），保留手动输入；模拟器首屏已验证按钮文案。
- 新增 `MapProviders` 适配层：Key 从 `local.properties` 的 `amapKey=` 注入 `BuildConfig.AMAP_KEY`，不进仓库；无 Key 时明确提示「当前使用非官方栅格瓦片」，有 Key 也只标「待接入」——`MapProvidersTest` 断言不会谎称官方 SDK 已生效。
- 用例总数 173 → **185**（新增 `SpeedPresetsTest` 8、`MapProvidersTest` 4）；lint 仍 0 error、app 29 warning。
- 版本：`versionName 0.2.0`、`versionCode 6`，签名证书 SHA-256 仍为 `14f0286f…02a9c`（同一把本地 key，可覆盖安装 build 5）。
