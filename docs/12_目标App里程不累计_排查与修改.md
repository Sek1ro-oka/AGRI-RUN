# 目标 App 不累计运动里程：证据、改动与复测流程

日期：2026-10-28（Asia/Hong_Kong）。对应问题：探针四通道都收到模拟帧，但「智慧浙农林 2.51」的跑步模块不记运动距离。

> **本文已被 `docs/13_智慧浙农林2.51_里程问题分析与分步改进方案.md` 修订。**
> 13 号文在目标 APK 里找到了更具体的代码分支（速度/精度/点间距条件 + `userIsRunning` 陀螺仪门控），
> 本文原先那套「字段不全/步长小于精度/样本太短」的确定性解释属于**推断，已被下修**。
> 两份文档一起看：**13 号文是分析依据，本文是工程侧改动记录与复测流程。**

## 0. 目标 App 身份（本次固定）

| 项 | 值 |
| --- | --- |
| 应用名 | 智慧浙农林 |
| 包名 | `edu.zafu.uniteapp` |
| 版本 | versionName `2.51` / versionCode `251` |
| APK | `base.apk(1).1`，142,912,796 字节，SHA-256 `E747E7D587174BF9D205ADD31F57F32817A4F4E13DA5BFE8DC786622080BCA2F` |
| 跑步模块 | `edu.zafu.uniteapp.sport.SportActivity` + `edu.zafu.uniteapp.gd.LzBgLocHelper` |
| 接收端 | 高德 `AMapLocationClient`（`AMapLocationPurpose.Sport`，`setMockEnable(false)`） |

## 1. 已有证据能证明什么

用户提供的一次探针报告（约 4 秒、15.6 米）：

| 通道 | 帧数 | 首帧延迟 | 间隔 中位/P95/最长 | 位移 | 均速 | 模拟标记 | 位移速度比 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gps | 8 | 225 ms | 531/533/580 ms | 15.6 m | 4.17 m/s | 8/8 | 1.00 |
| gms-flp | 6 | 249 ms | 537/544/573 ms | 11.2 m | 4.17 m/s | 6/6 | 1.00 |
| network | 8 | 258 ms | 530/537/570 ms | 15.6 m | 4.17 m/s | 8/8 | 1.00 |
| fused(framework) | 8 | 275 ms | 530/538/560 ms | 15.6 m | 4.17 m/s | 8/8 | 1.00 |

按 `docs/09` 的四层模型：

- **L1 发布成功**：通过。
- **L2 独立客户端（系统接口）收到连续帧**：通过，framework 与 GMS FLP 两条通道同时成立。
- **L2′ 目标接收端（高德 SDK）收到什么**：**未知**。探针当时没有高德通道，所以「四个系统通道都收到」不能等同于「高德 SDK 交给了运动模块有效帧」。
- **L3 目标 App 地图/轨迹变化**：未记录。
- **L4 目标记录采用（里程累加）**：失败。

L2 通过只能说明「我们发得对」，不能说明「对方肯收」，更不能说明对方的运动判定已经满足。

## 2. 目标代码里真正的里程条件（依据见 `docs/13` §3）

`LzBgLocHelper.handleLocation()` 的运行态分支，依次是：

1. `errorCode == 0` 且坐标非 `(0,0)`；
2. 精度合格（`getAccuracy()` 与 `LgBasicData.getSportGpsAccuracy()` 比较，构造默认 50 米）；
3. SDK 速度处于运行配置窗口（`getSportSpeedMin()` 0.1 m/s ～ `getSportSpeedMax()` 10 m/s，构造默认值）；
4. 计算与**最后一个已接纳点**的地理距离 `deltaMeters`；
5. `deltaMeters` 与 **`getSportSpeedMax()` 的数值直接比较**（这一局部条件**没有先除以两点时间差**）；
6. **`userIsRunning == true`** 才 `totalMeter += deltaMeters`；
7. 不满足里程条件时，该点**仍可能进入轨迹列表** → 于是出现「有线没公里」。

`userIsRunning` 由 `registerSensor()` → `SensorManager.getDefaultSensor(4)`（**TYPE_GYROSCOPE，陀螺仪**）→ `handleSensorAcc()` 周期性更新；不满足时还会提示「请跑起来」。

因此 13 号文给出的判断是：**发送端改 GPS 字段不能改变另一个进程里的 `userIsRunning`**。这是本次最重要的一条边界。

## 3. 0.3.1 做了什么（工程改动记录）

| 改动 | 位置 | 作用 |
| --- | --- | --- |
| 精度画像 `FixQualityProfile` | `simulation-core/src/main/java/org/localroute/simulation/FixQualityProfile.java` | 按速度统一给出水平/垂直/速度/航向精度与卫星数、载噪比 |
| 统一填充器 `LocationFiller` | `app/src/main/java/org/localroute/app/simulation/LocationFiller.java` | framework 与 GMS FLP 共用一份填充；补垂直/速度/航向精度、`elapsedRealtimeUncertainty`、`extras{satellites,meanCn0,maxCn0}`；network 来源不写卫星字段 |
| 首帧打印发布字段 | `SimulationService.logFirstFixProfile` | 诊断页出现「发布字段（每帧一致）：水平精度 3.0 米…」 |
| 单帧位移提示 | 同上 | 0.3.2 起改成「单帧位移 = 速度 × 发送间隔」并把目标 App 的固定米数门槛（默认 10 米）点出来，不再声称「小于精度必被丢弃」 |
| 菜单「兼容性：发送间隔」 | `MainActivity.showTickIntervalDialog()`、`menu_main.xml`、`strings.xml` | 0.3.2 起为 100 / 500 / 1000 / 2000 毫秒四档，存 `ui` 偏好 `tick_interval_ms` |
| 参考发布模式 | `SimulationService` + `PublishCadence` | **仍未做** `GOGOGO_REFERENCE`（见 §7 第 3 步）。0.3.2 只加了 `PublishCadence` 统计实际调度间隔，它是观测手段，不是参考发布模式 |
| 探针显示卫星数/速度精度 | `ProbeActivity.describe()` | 每帧末尾 `卫星=12 spdAcc=0.21` |
| 探针切后台不断订阅 | `ProbeActivity.onStop/onDestroy` | 修掉 N-10，可在目标 App 前台时后台取证 |
| 探针高德 SDK 通道 | `location-probe/.../AmapReader.java` | 0.3.2 新增：按目标的接收策略（`Sport` + `setMockEnable(false)`）与开发对照（`setMockEnable(true)`）两条通道读高德回调 |

## 4. 复测流程（按 `docs/13` §5 的顺序）

**第 0 步：先做真实定位基线。** 不开任何模拟，用真 GPS 在目标 App 里跑 200 米，确认它本身能进记录状态（蓝点、轨迹、实时距离都动）。这一步不过，后面全是误判。

**第 1 步：同机同条件三方对照。** 同一路线、同一速度、同一时段，分别跑：

| 组 | 配置 |
| --- | --- |
| A | GoGoGo（记录实际 APK 版本，不只看源码版本） |
| B | 本项目普通模式（gps + network + fused，500 ms） |
| C | 本项目 `GOGOGO_REFERENCE`（gps + network，100 ms） |

每轮之间停止会话并清理，一轮只启用一个模拟应用。

**第 2 步：在目标页面记录四件事**：蓝点、轨迹线、实时距离、提示文字（有没有「请跑起来」），外加发生时间。

**第 3 步：探针同时记录**高德通道（主通道 + 对照通道）与 framework 通道的原始回调，字段脱敏后保存。

**第 4 步：按判定表行动**（与 `docs/13` §5 第四步一致）：

| 观测 | 行动 |
| --- | --- |
| 高德主通道无位置、或 `errorCode != 0` | 按 SDK 错误修发布/接收链路；不要继续堆卫星字段 |
| 高德主通道正常、点间距异常 | 查回调实际间隔（发送间隔 ≠ 回调间隔）、后台调度、路线插值 |
| 轨迹增加但距离停住，且目标提示「请跑起来」 | 命中陀螺仪门控；这不是发送端能改的，按 13 号文如实报告 |
| 位置可用、字段一致，但目标仍不计距 | 标记为「未知内部状态」，不要编造原因；需要目标方测试环境 |
| GoGoGo 同条件有效、本项目无效 | 保留差异数据，逐项缩小配置范围（provider、帧率、精度数值、字段集） |

## 5. 明确撤回的三条旧结论

1. ~~「每帧位移小于声明精度就会被目标丢弃」~~ —— 目标代码没有这条；它的距离条件比的是配置上限数值与点间距，不是 `delta > accuracy`。
2. ~~「地图动但公里不动，多半是字段或样本太短」~~ —— 已找到独立的 `userIsRunning` 陀螺仪门控，样本长度只是可能因素之一。
3. ~~「2000 ms + 30–40 km/h 可以作为默认修复」~~ —— 35 km/h × 2 s ≈ 19.4 米，正好可能超过目标的 10 米点间距条件，**反而更差**。

## 6. 仍然做不到的事

- 无法清除 `isMock` 标记：framework 对测试 provider 的标记，改 `Location` 对象不改变交给其他进程的标记。
- 无法改写另一个进程的 `userIsRunning`、`totalMeter` 或服务器判定；本项目只做标准位置注入。
- 无法得知线上 `basicData` 实际值：第 2 节列的是 `LgBasicData.<init>` 的构造默认值，登录后被服务器覆盖，不能当兼容参数硬编码。
- 模拟 `extras` 里的卫星数/载噪比**不等于**真实 GNSS 状态，也不影响目标 App 的传感器读数。

## 7. 0.3.2 的进度（2026-10-28）

按 `docs/13` §5 的六步，当前状态：

| 步骤 | 状态 | 说明 |
| --- | --- | --- |
| 1. 固定版本与测试状态 | 已完成（文档） | 目标 App 身份、APK 哈希、设备无连接状态都在本文件 §0 记录 |
| 2. 探针加高德通道 | 已完成（代码） | `location-probe/.../AmapReader.java`：`amap-sdk`（mockEnable=false，复现目标）+ `amap-sdk-dev`（mockEnable=true，对照）；选项用 getter 实读；每帧输出 errorCode/errorInfo/locationType/gpsAccuracyStatus/精度/年龄/provider/mock/脱敏坐标。**尚未在真机跑过**，且探针包需要单独在高德控制台登记 Key |
| 3. `GOGOGO_REFERENCE` 发布模式 | 未做 | 需要 100 ms 档与「按单调时间+累计距离取位置」，会改动发布链路，本轮未动 |
| 4. 同机三方对照 | 未做 | 依赖真机与上述第 3 步 |
| 5. `DistanceDecision` 纯逻辑模块 | 未做 | 本轮只补了 `PublishCadence`（间隔统计）与 `PublishCadenceTest` |
| 6. 证据目录 `delivery/compatibility/zafu-2.51/<runId>/` | 未做 | 待真机对照跑完再落盘 |

另：`LzBgLocHelper.handleLocation` 的里程分支受 `userIsRunning`（陀螺仪 `TYPE_GYROSCOPE`）门控，
注入 GPS 无法改变该布尔值——这条在 §2 已写明，是「即使所有门槛都满足也可能不累计」的上限条件。

## 8. 0.3.3 的进度（2026-10-28）

真机验收结论：**目标「农林快跑」已能记录模拟运动距离**（用户真机反馈），本文件 §4 的复测流程里
「第 0 步基线 → 第 1 步三方对照」中**主路径已通过**；`docs/14` 因此把核心需求改判为「已达成」。

本版针对真机暴露的四个 P1 缺陷做了修复（详细见 `docs/15_0.3.3_R32修复与交付记录.md`）：

| 缺陷 | 修法 | 可自动化验证的部分 |
| --- | --- | --- |
| R32-01 路线中传送把自己打断 | `TaskModeSwitch` 决定是否调 `providerReady()`，只有 STARTING 才推进 | `TaskModeSwitchTest`（5 例） |
| R32-02 高德对照通道被场景覆盖 | `buildDevOption()` 最后设 `setMockEnable(true)`；`AmapOptionCheck` 断言 false/true | `AmapOptionCheckTest`（5 例） |
| R32-03 传送后多个 tick 并行 | `TickScheduler` 同一 Runnable 只保留一个待执行 | `TickSchedulerTest`（6 例） |
| R32-04 Activity 后台悬浮条不刷新 | 订阅挪到 `OverlayHost`（成对 attach/detach） | `OverlaySessionTest`（6 例）、`OverlayViewStateTest`（7 例） |

仍未做（与 §7 一致）：`GOGOGO_REFERENCE` 参考发布模式、同机三方对照的正式记录、
`DistanceDecision` 模块、`delivery/compatibility/` 证据目录。`PublishCadence` 的单元测试
**不能**替代 `SimulationService` 的调度/状态转换测试——调度本身仍只靠真机与日志验证。
