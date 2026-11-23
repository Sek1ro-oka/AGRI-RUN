# 本地 Issue 清单

本项目为单人开发；2026-10-27 起代码镜像到 <https://github.com/Sek1ro-oka/AGRI-RUN>（公开仓库，私钥、`local.properties` 与高德 Key 均不入库）。历史 Issue 记录在本文件；2026-11-16 起按用户要求将本轮产品体验问题同步到上游 GitHub Issues。
每条 Issue 记录编号、初始状态、解决方式与验证证据。状态只写“已关闭/未关闭”，不写没有证据的“已验证”。

## 已关闭

| 编号 | 标题 | 处理 | 证据 |
| --- | --- | --- | --- |
| I-001 | AGP 拒绝含中文的工程路径 | 先用 `android.overridePathCheck=true` 兜底；2026-10-26 目录改名为 `E:\agri-run` 后该开关已移除 | `clean` 后 `:app:assembleDebug` 在 ASCII 路径下构建通过 |
| I-002 | Gradle 测试 worker 在中文路径下 `ClassNotFoundException` | 先用 `subst R: "E:\农林快跑"` 兜底；2026-10-26 把目录改名为 `E:\agri-run` 彻底解决并删除 `subst` | ASCII 路径 `clean` 后 95 个用例全绿 |
| I-003 | 混用两个路径（`E:\` 与 `R:\`、改名前后）构建导致 `different roots` dex 失败 | 先 `gradlew clean`，此后只用一个路径构建 | ASCII 路径 `clean` 全量构建 `BUILD SUCCESSFUL` |
| I-004 | Gradle wrapper 无法下载（`services.gradle.org` 不可达） | `--gradle-distribution-url` 指向腾讯镜像并写入 `gradle-wrapper.properties` | `gradlew.bat --version` 输出 Gradle 8.11.1 |
| I-005 | 路线文件半写风险 | 写 `.json.tmp`（随机后缀）+ `FileDescriptor.sync()` + 目录 fsync（平台不支持时忽略）+ 原子 `move`；损坏文件移入 `corrupt/` 且保留原文件名 | `RouteRepositoryTest` 10 个用例 |
| I-006 | `delete()` 二次删除返回 true（不幂等） | 先判 `exists()` 再 `delete()` | `RouteRepositoryTest.deleteRemovesFileAndIsIdempotent` |
| I-007 | `RouteEngine` 有效用时 `01:00:00` 显示成 `1:00:00` | 格式串改 `%02d:%02d:%02d` | `RouteEngineTest.formatDuration_handlesMinutesAndHours` |
| I-008 | 状态机允许 `STARTING` 直接 `resume()`、`COMPLETED` 被 `stop()` 改写成 `STOPPED` | `resume()` 前置校验；`stop()` 在非运行状态为空操作；服务层只在实际打断运行时才发布 STOPPED 快照 | `LoopStateMachineTest` 7 个用例 |
| I-009 | 解码器对空 id / 单点草稿的行为与测试期望不一致 | 空 id 在构造器抛 `IllegalArgumentException`、解码路径抛 `RouteFormatException`；单点草稿允许保存但不可编译 | `RouteJsonCodecTest` |
| I-010 | 跨日界线插值测试容差过严 | 容差改为 1e-3 度并注释说明大圆中点偏极 | `GeoMathTest.interpolate_acrossAntimeridian_staysNearDateLine` |
| I-011 | T11：调度间隔 > 5 秒时没有保护 | 抽出 `TickGapPolicy`（纯逻辑）+ 服务 tick 检测；超限即自动暂停，有效时间只累计到上一次成功 tick，并在通知/界面说明原因 | `TickGapPolicyTest` 10 个用例；`SimulationService.autoPauseForSchedulerGap`（真机行为未实测） |
| I-012 | release 包无签名配置，`keystore` 口令有泄露风险 | `keystore.properties` 条件读取，`keystore/` 与 `keystore.properties` 均在 `.gitignore` | `docs/BUILD.md` 第 4 节 |
| I-013 | 定位探针只有占位界面，无法独立验证坐标 | 实现 `ProbeActivity`：多 provider 监听、模拟位置标记、累计位移、停止汇总 | 构建产物 `location-probe-debug.apk`（真机未实测） |
| I-014 | 大路线（5000 点）塞进 Intent 可能触发体积上限 | 用进程内 `PendingRunRegistry` 传请求，Intent 只带 `runId` | 代码审查 `MainActivity` → `SimulationService` 路径 |
| I-015 | 通知栏缺少暂停/继续/停止 | 常驻通知两个 Action，与页面共用 `handlePause/handleResume/handleStop` | 代码审查（真机未实测） |
| I-016 | 上次异常退出残留的测试 provider 让应用永久无法运行，且被误报成「设备不支持」 | `MockLocationSession.start()` 先尝试清理残留再创建；设置页探测新增 `ALLOWED_AFTER_CLEANUP` 结果并在界面说明 | `MockLocationSessionTest.staleProviderFromPreviousCrash_isClearedBeforeCreating` |
| I-017 | `add` 成功但 `enable` 失败时 provider 未记录，回滚删不掉，自己制造残留 | 先记录再启用；启用失败额外撤掉该 provider | `gpsEnableFailure_rollsBackEverything`、`optionalEnableFailureIsSkippedAndProviderRemoved` |
| I-018 | 可选 provider（network/fused）失败导致整次运行失败，与「GPS 必需、其余尽力」矛盾 | 非必需 provider 失败只记入 `skippedProviders` 并继续；只有 gps 失败或一个都没建成才失败 | `optionalProviderFailureIsSkippedNotFatal` |
| I-019 | `handleStart` 在主线程、其余命令在 Handler 线程，共享字段无同步；`onStartCommand` 无兜底异常 | START 改为投递到服务 Handler；`post()` 包一层 `runGuarded`，异常转 `ERROR`；`stopped` 加 `volatile` | 代码审查（服务层暂无单测） |
| I-020 | 早退路径（无请求/重复停止/未知动作）不写终态，界面可能永远停在「运行中」 | 新增 `stopAndPublishStoppedIfActive()` | 代码审查 |
| I-021 | 通知节流用挂钟、每 tick 重复 `startForeground`、所有动作都先发「准备中」 | 改用 `elapsedRealtime()` 节流；后续更新走 `NotificationManager.notify`；只有 ACTION_START 发「准备中」 | 代码审查 |
| I-022 | 手势监听在 map 就绪前注册，静默失效 → 运行中地图拖不动 | `registerGestureListener()` 记录意图，`onMapReady` 补挂 | 代码审查 |
| I-023 | 权限弹窗期间旋转丢请求，点「允许」后毫无反应 | 待启动参数搬进 `RouteEditorViewModel`；缺失时提示「请再点一次开始」 | 代码审查 |
| I-024 | 主线程读写磁盘（保存、列表、诊断导出） | 保存/列表/加载改到单线程 `Executor`，回调回主线程；`onDestroy` 里 `shutdown()` | 代码审查 |
| I-025 | 原子写没 fsync、tmp 名固定、`list()` 认内容 id | 目录 fsync、tmp 随机后缀、文件名与内容 id 不一致时隔离 | `listQuarantinesFileWhoseNameDoesNotMatchContentId`、`saveLeavesNoTemporaryFilesBehind` |
| I-026 | 完成容差、单圈 1 米边界缺测试 | 补 `completionThreshold_boundaryEitherSide`、`lapLengthNearOneMeter_isAcceptedOrRejected` | 用例数 88 → 95 全绿 |
| I-027 | 完成了独立的只读代码审查（此前 I-106 记录为“未做”） | 另起 subagent 不共享上下文、只读审查，逐条评估后修复 P1/P2 问题 | 本文件 I-016—I-026、`delivery/TEST_REPORT_测试结果.md` 第 7 节 |

## 未关闭

| 编号 | 标题 | 现状 | 计划 |
| --- | --- | --- | --- |
| I-101 | 无真机，T07/T11/T14–T24 未实测 | `adb devices` 为空，全部真机项在 `docs/TEST_PLAN.md` 标 ⚠️ | 接入真我 GT Neo5 后按 TEST_PLAN 第 3 节顺序复核并回填 |
| I-102 | B 操场坐标未知，无内置示例 | 任务书禁止猜坐标；`samples/` 只有 `(0,0)` 附近合成数据 | 用户在真机点选或提供经纬度后另存为路线 |
| I-103 | OSM 公共瓦片的使用政策风险 | 已写署名、未自定义 UA、无离线预取 | 正式长期使用前换自建/商业瓦片源 |
| I-104 | 第三方 App 是否采信模拟位置未知 | 未做任何检索与承诺 | 属被测方行为，不在本工具范围内 |
| I-105 | 无导出 GPX/GeoJSON 能力 | 任务书 P0 未要求 | 若需要再进 P1 |
| I-107 | `SimulationService` 没有任何自动化测试 | 700+ 行服务只靠静态走查；JVM 单测覆盖不到 Handler/通知/WakeLock | 后续引入 Robolectric 或真机 instrumentation，至少覆盖 tick 推进与 stop/complete 分支 |
| I-108 | 崩溃残留的测试 provider 在真机上是否跨进程存活 | 代码已加「先清理再创建」，但未在真机验证 system_server 里的残留行为 | 真机：强杀进程后重启应用，看是否仍能正常开始（观察诊断页 provider 矩阵） |
| I-109 | `fused`（GMS）provider 能否注册因 ROM 而异 | 代码按「尽力尝试、失败跳过」处理，逐项记录在 `publishedProviders/skippedProviders` | 真机看诊断页矩阵并记录实际结果 |
| I-110 | Android 14 上从通知 Action 冷启动前台服务可能受限 | 代码先 `ensureForeground` 再投递命令，未在真机验证 | 真机：杀掉界面后用通知按钮暂停/停止，确认状态与通知一致 |

## 0.1.1 批次（真机反馈后）

| 编号 | 问题 | 处理 | 状态 |
| --- | --- | --- | --- |
| I-028 | 真机打开后地图一片黑 | 三种瓦片源可切换 + 10 秒看门狗 + 底部原因条 + 浅灰底图；手动输入与单点发送不依赖地图 | 已修（代码），真机待验（T26） |
| I-029 | 点 3 个点就自动连回起点，无法自选是否闭合 | `RouteDefinition.closed` + 菜单「闭合路线」；不闭合按原路返回计圈 | 已修，数学部分有单测（T27 待真机） |
| I-030 | 开发者选项的模拟位置应用列表里没有本应用 | 清单声明 `ACCESS_MOCK_LOCATION`（AOSP `AppPicker` 按 `requestedPermissions` 过滤）；aapt2 已确认 APK 内含 | 已修（T25 待真机确认列表出现） |
| I-031 | 希望直接传送单点位置 | 菜单「发送单点位置」+ `RunRequest` 单点模式 + 服务固定坐标输出 | 已实现（T28 待真机） |
| I-032 | 地图依赖公共瓦片，无网/被墙时不可用 | 三个备选源均为公开栅格服务，仍需联网；离线地图不在 P0 范围 | 未关闭（设计限制） |
| I-033 | 不闭合路线的「圈数」语义可能与用户直觉不同 | 界面文案明确写「不闭合（原路返回）· 单圈 = 2 × 单程」，并以实际圈长显示 | 观察项，待真机反馈 |

## 0.1.2 批次（2026-10-27）

| ID | 类型 | 标题 | 状态 | 处理 |
| --- | --- | --- | --- | --- |
| I-034 | feature | 传送：把模拟位置搬到地图选点 | 已关闭 | 菜单「传送到地图选点」+ ViewModel 模式 + 复用单点链路；运行中拒绝传送 |
| I-035 | feature | 高德底图（GCJ-02）与坐标换算 | 已关闭 | `ChinaShift` + `RouteMapController` 坐标模式；OSM 仍是默认；高德接口非官方，可能随时失效 |
| I-036 | limitation | 百度地图未接入 | 未关闭（设计取舍） | 百度瓦片是 BD-09 私有矢量接口，需要官方 SDK key；本应用不接第三方 SDK |
| I-037 | feature | 位置自检：系统到底收没收到 | 已关闭 | `LocationVerifier` + `SystemLocationReader` + 诊断页黄色区块；真机表现未实测 |

## 0.1.3 批次（2026-10-27）

| ID | 类型 | 标题 | 状态 | 处理 |
| --- | --- | --- | --- | --- |
| I-038 | bug | 打开后地图空白：OSM 域名在部分网络下不可达 | 已关闭 | 默认源改为高德；补瓦片探测（HEAD 4 秒）与地图区提示；OSM 仍可选 |
| I-039 | bug | 加完节点看不到点在哪 | 已关闭 | 首点放大 16 级；后续点不在视野内时 `fitRoute` 重新取景 |
| I-040 | feature | GPX 导入/导出 | 已关闭 | `GpxCodec`（子代理实现）+ 菜单两项；单段 `trkseg`，WGS-84 |
| I-041 | infra | 模拟器上没法装调试包 | 已关闭 | debug 追加 `x86_64` ABI；release 不变 |
| I-042 | test | 「系统真的收到了吗」只有单元测试 | 已关闭（模拟器） | API 35 模拟器实测：探针收到 11 帧且均标模拟；真机仍待复验 |
| I-043 | limitation | 高德瓦片接口非官方、可能失效 | 未关闭（外部依赖） | 菜单可切回 OSM/备用源；文档与界面都注明来源与风险 |
| I-044 | limitation | 模拟器结论不能代替真机 | 未关闭（范围声明） | `delivery/TEST_REPORT_测试结果.md` 分开列「已实测（模拟器）」与「未实测（真机）」 |

## 0.3.2 关闭项（2026-10-28）

| 编号 | 标题 | 处理 | 证据 |
| --- | --- | --- | --- |
| I-101 | 悬浮条只在任务活动时显示，冷态无法跨应用传送（N-01） | `OverlayPanel.sync()` 改为 `enabled && allowed`；未运行时禁用暂停/停止并显示 `overlay_idle_status`；点 A/B/C 走 `teleportTo`（冷态也启动任务） | `:app:compileDebugJavaWithJavac` 通过；全量 216 用例通过 |
| I-102 | 路线运行中点 A/B/C 只写日志、界面无反馈（N-02） | `SimulationService.handleTeleport()` 路线分支改调 `switchRouteTaskToSinglePoint()`，就地切单点并发布 RUNNING 快照；`OverlayPanel` 先 toast `overlay_teleport_sent` | 同上；代码路径 `SimulationService.java` 新增方法 |
| I-103 | 悬浮条拖动可出屏、位置不持久化（N-03） | `clampToScreen()` + `overlay_x`/`overlay_y` 持久化；去掉 `FLAG_LAYOUT_NO_LIMITS` | 同上 |
| I-104 | 保存期间继续编辑会丢 dirty（N-04） | `RouteEditorViewModel.editRevision()` + `markSavedIfUnchanged(long)`；`MainActivity.doSave()` 按代次决定是否清除 dirty | 同上 |
| I-105 | 「地图瓦片源」仍提示“本版本仍使用栅格瓦片…SDK 待接入”（N-11） | `MapProviders.note()` 改为如实描述 SDK/回退；`MapProvidersTest` 断言同步更新 | `MapProvidersTest.noteExplainsSdkPathAndRasterFallback` 通过 |
| I-106 | 诊断页看不出实际发送间隔与偏差 | 新增 `PublishCadence`（中位间隔/最长间隔/相对标称偏差）与每 20 帧一条日志 | `PublishCadenceTest` 6 例通过 |
| I-107 | 探针日志两个来源混在一行（N-12） | `ProbeActivity.append()` 改 `synchronized` 并折掉行内换行 | 同上 |
| I-108 | 探针无法验证高德 SDK 接收结果（docs/13 第二步） | 新增 `AmapReader`（`amap-sdk` / `amap-sdk-dev` 双通道，getter 实读选项，输出 errorCode/errorInfo/locationType/gpsAccuracyStatus/精度/年龄/provider/mock/脱敏坐标） | `:location-probe:assembleDebug` BUILD SUCCESSFUL，APK 12.1 MB |

## 0.3.3 关闭项（2026-10-28，真机验收意见 R32-01…R32-07）

真机验收结论：目标 App「农林快跑」已能记录模拟运动距离，核心需求达成。本批修的是可用性缺陷。

| 编号 | 标题 | 处理 | 证据 |
| --- | --- | --- | --- |
| I-109 | 路线运行中传送把自己打断：`providerReady()` 无条件推进状态机，RUNNING→RUNNING 非法边抛异常（R32-01） | 新增 `TaskModeSwitch`：RUNNING 保持不推进、PAUSED 不自动恢复且不重置有效用时、只有 STARTING 才 `providerReady()`；不给状态机加自转移 | `TaskModeSwitchTest` 5 例通过 |
| I-110 | 探针高德对照通道被 `setLocationPurpose(Sport)` 覆盖，mockEnable 实测 false，对照无效（R32-02） | `AmapReader` 拆 `baseOption/buildPrimaryOption/buildDevOption`，对照最后设 `setMockEnable(true)`；新增 `AmapOptionCheck` 断言 false/true 并把 ✓/✗ 写进报告 | `AmapOptionCheckTest` 5 例通过 |
| I-111 | 传送/暂停/开始各路径重复 `postDelayed`，多个发布循环并行（R32-03） | 新增 `TickScheduler`：同一 Runnable 只保留一个待执行 tick，`cleanupEverything()` 先 cancel | `TickSchedulerTest` 6 例通过（含连续排程 20 次只投递 1 次） |
| I-112 | 悬浮条订阅挂在 Activity 生命周期上，切后台后状态冻住（R32-04） | 新增 `OverlayHost` / `OverlaySession` / `SnapshotSource` / `OverlayViewState`；订阅随悬浮开关成对 attach/detach，`onDestroy` 不再隐藏窗口 | `OverlaySessionTest` 6 例、`OverlayViewStateTest` 7 例通过 |
| I-113 | 探针把 SDK 错误回调算进位移与均速；无速度样本时显示 0.00 m/s（R32-05） | `ChannelStats` 分开回调/有效/错误，新增 `hasSpeedSamples`、`ageSinceLastFrameMillis`；报告加统计会话与最后回调年龄；布局加「传送测试」复选框 | `ChannelStatsTest` 新增 3 例通过 |
| I-114 | 保存 A 期间切到 B，B 被误标已保存（R32-06） | 新增 `DraftSaveToken`（routeId + draftGeneration + editRevision）；`load/newDraft/markDeleted` 递增代次；`markSavedIfCurrent` 三者全等才清 dirty | `DraftSaveTokenTest` 8 例通过 |
| I-115 | `docs/12` 自相矛盾地声称已实现 `GOGOGO_REFERENCE`；首帧日志给出结论式建议（R32-07） | 修正 `docs/12` 改动表与 §7/§8；`logFirstFixProfile()` 改为只报观测值并说明「一次只改一个变量」 | 文档核对 + 代码 review |
| I-116 | 交付文档版本漂移（README/手册/ISSUES/TEST_REPORT/VERSION/SHA256SUMS 停在 0.3.2） | 全部升到 0.3.3 / 0.1.4，新增 `docs/15`；0.3.2 成功包移入 `delivery/baseline-0.3.2/` 作对照 | 见 `delivery/SHA256SUMS.txt`（逐项复核 0 不匹配） |


## 2026-11-16 产品体验改造

本轮按用户要求在上游创建 GitHub Issues，以下链接与本地记录对应。

| 上游 | 审查项 | 本轮处理 |
| --- | --- | --- |
| [#25](https://github.com/Sek1ro-oka/AGRI-RUN/issues/25) | 5 自然变速覆盖浮窗改速 | 共用运行目标速度，增加真实浮窗切档后的连续周期检查 |
| [#26](https://github.com/Sek1ro-oka/AGRI-RUN/issues/26) | 6 传送确认 | 待后续实现 |
| [#27](https://github.com/Sek1ro-oka/AGRI-RUN/issues/27) | 7 扰动范围文案 | 待后续实现 |
| [#28](https://github.com/Sek1ro-oka/AGRI-RUN/issues/28) | 8 课表独立使用 | 待后续实现 |
| [#29](https://github.com/Sek1ro-oka/AGRI-RUN/issues/29) | 10 路线节点编辑与闭合选项 | 待后续实现 |
| [#30](https://github.com/Sek1ro-oka/AGRI-RUN/issues/30) | 11 精确输入 | 已实现，配速/速度、圈数、运动类型预设与浮窗共用 |
| [#31](https://github.com/Sek1ro-oka/AGRI-RUN/issues/31) | 12 浮窗标签与传送 | 待后续实现 |
| [#32](https://github.com/Sek1ro-oka/AGRI-RUN/issues/32) | 14 地图主页与信息层级 | 已实现，更新入口迁移、首页当前任务入口 |

A 预设路线预览与选择后地图展示、B 精确输入、C 地图主页的实现和验证见 [本轮记录](16_地图主页与精确输入.md)。

## 2026-11-22 课前提醒（新功能，已实现待验证）

用户需求：课表设置页新增卡片，开关「按课表上课前提醒」，开启后可自定义提前分钟数（1–120，默认 10）。

| 模块 | 内容 |
| --- | --- |
| `study/ClassReminderPlanner` | 纯 JVM 计划器：按学期起始日推算教学周，结合课表周次（含单/双周）与作息节次起点算提醒时刻；提前量跨天时回看前一天；未设开学日期或周次无效时无提醒 |
| `study/ClassReminderScheduler` | 单只滚动闹钟（AlarmManager，7 天窗口）；有精确闹钟权限用 `setExactAndAllowWhileIdle`，否则降级 `setAndAllowWhileIdle`；无未来提醒时取消 |
| `study/ClassReminderReceiver` | 闹钟回调按 [触发前 2 分钟, 触发后 10 分钟) 窗口补发全部到期通知（渠道 `class_reminder`，IMPORTANCE_HIGH），并滚动续排下一只；开机/改时/换时区/覆盖安装后重排 |
| `StudySettingsActivity` + 布局 | 「课前提醒」卡片：开关、提前分钟数选择对话框、下一班提醒提示；未设开学日期/课表为空/7 天内无提醒时给出行内警告 |
| `StudyTimetableStore` | 新增开关与提前分钟数持久化（默认关闭） |
| `StudyActivity.onResume` | 回到课表页时重排闹钟，覆盖设置页/导入页对课表与作息的修改 |
| Manifest | 声明 `RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`；注册 `.study.ClassReminderReceiver`（exported=false） |

验证：`ClassReminderPlannerTest` 11 例通过（同日命中、跨天提前量、单双周、越界节次、到期窗口边界等）；全量 JVM 测试 248 例 0 失败；lint 0 error（281 warning 与基线一致）；`assembleDebug` 成功。通知实际弹出、省电策略下的准点性、模拟器/真机闹钟行为均未实测。

## 2026-11-23 自建作业与截止提醒（新功能，已实现待验证）

用户需求：查询作业页底部「新增作业」，可自定义作业名称、截止时间与截止前多少分钟提醒；到点弹通知告知「作业（名称）即将截止」；「作业截止提醒」开关放在查询作业的设置页。

| 模块 | 内容 |
| --- | --- |
| `study/CustomHomework` | 纯 Java 模型：id + 名称 + 截止毫秒 + 每条自己的提前分钟数（1–10080，默认 60）；`remindEpochMillis()` 推算提醒点，无截止时间时无提醒点 |
| `study/CustomHomeworkCodec` | 自建作业 JSON 编解码，坏条目跳过、整体损坏退回空列表；与学习通作业缓存分开存放，刷新不会冲掉手录数据 |
| `study/CustomHomeworkStore` | SharedPreferences 存储（`custom_homework`）：列表增删改查 + 「作业截止提醒」总开关（默认关闭） |
| `study/HomeworkReminderPlanner` | 纯 JVM 计划器：30 天窗口内按提醒点升序；`dueBetween` 给闹钟回调补发用，区间左闭右开 |
| `study/HomeworkReminderScheduler` | 单只滚动闹钟（requestCode `0x4857`，与课前提醒互不干扰）；有精确闹钟权限用 `setExactAndAllowWhileIdle`，否则降级 `setAndAllowWhileIdle`；关闭开关或窗口内无提醒点时取消 |
| `study/HomeworkReminderReceiver` | 按 [触发前 2 分钟, 触发后 10 分钟) 补发通知（渠道 `homework_reminder`，IMPORTANCE_HIGH），点通知进查询作业页；开机/改时/换时区/覆盖安装后重排 |
| `StudyDialogs.showHomeworkEditor` | 自建作业编辑器：名称 + 截止日期/时间两级选择 + 提前分钟数，实时预览「将在 … 提醒你」；提醒点已过会给出行内提示且不允许保存过期截止；编辑态提供删除二次确认 |
| `StudyActivity` | 作业页底部固定「新增作业」按钮；自建条目排在列表尾部（状态标签「自建」/「已截止」，右侧显示提前量），点击进入编辑；空态判定改为「学习通与自建都为空」（`hasHomeworkContent`），避免手录作业被空态遮住 |
| `StudySettingsActivity` + 布局 | 「作业截止提醒」卡片：开关 + 下一个提醒提示；无自建作业、30 天内无提醒点时给出行内提示 |
| Manifest | 注册 `.study.HomeworkReminderReceiver`（exported=false，复用已声明的 `SCHEDULE_EXACT_ALARM`/`RECEIVE_BOOT_COMPLETED`/通知权限） |

验证：新增 `HomeworkReminderPlannerTest` 10 例、`CustomHomeworkCodecTest` 7 例，全量 JVM 测试 266 例 0 失败；lint 0 error（281 warning 与基线一致）；`assembleRelease` 出包并用 apksigner 核对签名证书 `CN=UNLOOP Release`。通知实际弹出、省电策略下的准点性、真机闹钟行为均未实测。

## 2026-11-23 App 内检查更新找不到附件（缺陷修复）

现象：装了 0.9.5/0.9.6 的设备点「检查更新」能发现新版本，但弹窗提示「未找到 apk，可前往发布页」，无法在应用内下载。

定位：`UpdateChecker` 只认名字以 `route-workshop-` 开头的附件（用来排除定位探针包），而 0.9.5–0.9.7 发布时把附件命名为 `app-release.apk`，于是这三版在应用内更新看来等于「没有安装包」。0.9.4 及更早用的 `route-workshop-<版本>-release.apk` 一直正常。

处置：

| 项 | 内容 |
| --- | --- |
| 线上数据 | 直接把 v0.9.7 附件改名为 `route-workshop-0.9.7-release.apk`（GitHub 只改元数据，字节与 sha256 不变，实测首字节 `PK`、`releases/latest` 已返回新名）；旧链接 `app-release.apk` 随之 404 |
| 约定 | 今后 Release 附件一律命名 `route-workshop-<版本>-release.apk` |
| `UpdateChecker` | 附件匹配放宽为「`route-workshop-` 前缀或 `app-release` 前缀，且不含 probe」，避免再次出现整版不可更新；新增 2 个 JVM 用例 |
| `UpdateManager` | 新增 `describeDownload`：读系统下载器的状态、原因码、进度与线路，纯函数 `describe` 可 JVM 测试；新增 `looksLikeApk` 判断 ZIP 魔数 |
| `UpdatesActivity` | 下载完成后先校验再提示安装（此前要点「立即安装」才校验）；失败提示带上面那条摘要并写入诊断日志；校验未通过时区分「线路返回的是网页拦截页」；更新弹窗由 3 枚按钮改 2 枚，窄屏不再竖排 |
| 布局/字符串 | 检查更新页下方新增「打开发布页」「诊断日志」两个入口（`DiagnosticsActivity` 此前只在 Manifest 注册、界面上无入口）；新增失败摘要与诊断入口文案 |

背景证据：用户手工从加速线路下载的 74 MB 文件首 4 字节是 `<!DO`，即拿到的是网页而非 APK；本机实测 5 条线路（GitHub 直连 + gh-proxy.com/.org + ghproxy.imciel.com/net）均返回 `PK` 且完整下载与 GitHub digest、本机构造三者 sha256 一致。

验证：273 个 JVM 单元测试全过（37 个测试类，较上一版 +7）；`lintRelease` 0 errors、281 warnings（均为既有文案项）；`assembleRelease` 产物 74,194,505 字节、sha256 `8908a31431fdac7503533d5572f2cbdf3e77b99baa3c813869813add15b43c3c`、签名 `CN=UNLOOP Release`，已作为 v0.9.8（versionCode 38，tag 指向 `a04769f`）的附件 `route-workshop-0.9.8-release.apk` 发布，GitHub 回读的 digest 与本机构建一致。真机侧「应用内更新到 0.9.8」与拦截页提示尚未实测。

## 2026-11-23 旧签名无法追认，0.9.4 及更早只能卸载重装（已定案）

现象：手机上装过 0.9.4 及更早版本的设备安装 0.9.5+ 时被系统拒绝，提示签名不一致（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。用户提供了 `unloop-keystore-2026Q3_cert-14f0286f.der`，要求把 0.9.5 之后的包全部换成这个签名。

定位：这是 2026-11-22 重建签名 key 的必然结果，记录在 v0.9.5 的发布说明里。逐项核实：

| 证据 | 结果 |
| --- | --- |
| 用户给的 `.der` | 913 字节 X.509 DER **公钥证书**；`openssl pkey/pkcs8` 读为「Could not find private key」，按 PKCS#12 解析报 wrong tag → 不含私钥，无法用于签名 |
| 证书身份 | `CN=Route Workshop Local, O=Local Dev`，2026-10-26 生效，SHA-256 `14f0286f…02a9c` |
| 旧包确实是它签的 | 取回 v0.9.4 附件（74,137,033 B，直连截断后经 gh-proxy.com 取全），`apksigner verify --print-certs` 得到同一 DN 与指纹 |
| 当前包 | `CN=UNLOOP Release`，SHA-256 `01547534…82f4`，两把 key 不同 → 系统必须拒绝覆盖安装 |
| 旧私钥下落 | 全盘 `*.jks/*.keystore/*.p12/*.pem`（D: 与 C:\Users\GOST）、两个 `$RECYCLE.BIN`、`git log --all -- keystore/` 均无结果；按 `docs/BUILD.md` 原路径应为 `keystore/route-workshop-release.jks`（别名 `routeworkshop`），从未入库 |

处置：用户确认旧私钥找不到了，本条按「不可追认」定案。签名主线只保留 `unloop-release.jks`；`docs/BUILD.md` 第 4.1 节写清两条线的指纹、覆盖范围，以及「手上只有证书时不要试图换回旧签名」的原因，防止后续重复尝试。旧设备的唯一升级路径是先卸载再安装，代价是丢本地数据（`files/routes/` 的路线、课表登录态、自建作业、跑速与提醒设置；课表与作业缓存重装后可重新同步）。

后续待办（未开工，需用户点头）：加一份本地数据导出/导入，让「换签名必须卸载」不再等于丢数据；同时把 `keystore/` + `keystore.properties` 做仓库外备份——旧 key 已经丢过一次，不能再丢第二次。
