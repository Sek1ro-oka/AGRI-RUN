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

## 2026-11-23 悬浮窗默认开启、跑圈页去掉传送/定位、界面风格与主页卡片（已实现待验证）

需求（用户原话摘要）：① 悬浮窗默认打开；② 跑圈卡片的「传送」「定位」按钮删除，「已经有一个专门的通道来传送和定位」；③ 主页两张入口卡片图标缩小、放到文字左边；④ 设置页加一张「界面风格」卡片，可选跟随系统/深色/浅色，默认跟随系统。

改动：

| 位置 | 内容 |
| --- | --- |
| `OverlayHost` | 新增 `DEFAULT_ENABLED = true`，读取 `overlay_enabled` 时用该默认值。只影响从没手动切过开关的设备；用户关掉后写回的 `false` 会一直保留。没有「显示在其他应用上层」权限时依然不画任何东西，仍在开始跑圈前提示一次去授权 |
| `CampusRunActivity` + `activity_campus_run.xml` | 删掉 `buttonTeleport`（「传送/定位」按钮）、`textTeleportHint` 待命提示，以及 `showMapActionsMenu`/`onTeleportPressed`/`performTeleport`/`sendTeleport`/`beginSinglePoint`/`locateCurrentPosition`/`locateByDevice`/`showDeviceLocated`/`locateFallback` 九个方法与 `teleportArmed`、`teleportTarget`、`deviceLocation`、`pendingTeleport`、`pendingLocate`、`awaitingTeleportAck`（含超时回调与 `handleTeleportAck`）状态；权限回调只剩「开始跑圈」一条分支；地图点击只在绘制模式加节点。整文件净减 370 行（+10/−380），本次九文件合计 −491/+160 |
| `strings.xml` | 删除随之上架失效的 21 条文案（`campus_map_actions_*`、`campus_menu_*`、`campus_teleport_*`、`campus_locate_done/fallback/missing`）。`campus_locate_device/searching/permission/off` 四条仍被「虚拟定位」页使用，保留 |
| `AppearancePreferences`（新） | `ui`/`night_mode` 存三种模式，默认 `MODE_FOLLOW_SYSTEM`；`nightModeFor` 映射到 `AppCompatDelegate` 常量，纯函数便于 JVM 测试 |
| `UnloopApplication`（新）+ `AndroidManifest` | 应用启动时 `AppearancePreferences.apply(this)`，冷启动即恢复用户选的风格；此前从不调用 `setDefaultNightMode`，深浅色一直靠系统隐式跟随，本次只是把选择权交给用户，默认值不变 |
| `AppSettingsActivity` + `activity_app_settings.xml` + `ic_fm_display.xml` | 「通用」卡片第一行新增「界面风格」（图标 44dp + 当前值 + 箭头），点击弹单选对话框，选中后立即 `setDefaultNightMode` 并刷新行内文案。放在设置页而非主页，是因为主页本身没有设置类卡片 |
| `activity_main.xml` | 「校园快跑」「虚拟定位」两张卡片去掉 92dp 色块头部，保持左右并排，改为卡内 32dp 圆角图标块（`bg_campus_entry_icon`，内缩 7dp）放在标题左侧，标题 18sp→15sp，说明与入口文案移到下方、11sp |
| `CampusWorkspaceTest` | 两处 `buttonTeleport` 可见性断言改为断言 `mapContainer` 完整显示 |

已知取舍：跑圈中把地图拖走之后，页内没有「回到当前位置」的手动按钮了；恢复镜头只剩重新进入跑圈页或开始跑圈（这两处仍会自动对准模拟位置）。跨应用传送/定位仍走主页「虚拟定位」与悬浮控制台，功能没有减少，只是入口收敛。

验证：277 个 JVM 用例全过（38 个测试类，较上一版 +4，即 `AppearancePreferencesTest`）；`lintRelease` 0 errors、279 warnings（比基线少 2 条，来自删除的文案）；`assembleRelease` 产物 74,177,165 字节、签名 `CN=UNLOOP Release`；`compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此悬浮窗默认态、深浅色切换、两张卡片新排版、去掉按钮后的跑圈页布局都只做了静态验证，真机效果待复验。

## 2026-11-24 0.9.9 发布记录

上一节的改动（含主页卡片图标定为 32dp）以 PR #67 `1eeeb3b`、PR #68 `afd7065` 并入 main，版本号由 PR #69 `7a9bdaa` 升到 0.9.9 / versionCode 39，轻量标签 `v0.9.9` 指向该合并提交，Release 地址 <https://github.com/Sek1ro-oka/AGRI-RUN/releases/tag/v0.9.9>。

| 项 | 结果 |
| --- | --- |
| 附件命名 | `route-workshop-0.9.9-release.apk`，符合 `UpdateChecker` 的识别约定 |
| 本机构建 | 74,177,165 字节，sha256 `31adaaa315775991c5c4d4751306b5e4ecfdb85d246f22a227655a9f6a65e392`，`aapt2 dump badging` 读到 `versionCode='39' versionName='0.9.9'` |
| 签名 | `apksigner verify --print-certs` → `CN=UNLOOP Release, O=Local Dev, C=CN`，SHA-256 `01547534…82f4`，与 0.9.5–0.9.8 同一主线，老设备可覆盖安装；0.9.4 及更早仍需先卸载（见 2026-11-23 签名定案） |
| 线上复核 | `releases/latest` 返回 `v0.9.9`；回下载附件同为 74,177,165 字节、首 2 字节 `PK`、sha256 与本机一致（GitHub API 的 `digest` 字段发布时仍为 null，摘要以下面这条实测为准） |
| 发布前测试 | `:app:testDebugUnitTest` 277 例 0 失败；构建产物即 tag 指向的 `7a9bdaa` 状态 |

仍待真机复验：悬浮窗默认开启后的首次表现、深浅色切换（尤其高德 SDK 底图与悬浮控制台配色）、主页两张 32dp 图标卡片在窄屏的换行、跑圈页去掉传送/定位后的布局与「拖走地图难以回中」的实际体验。

## 2026-11-24 本机数据备份与恢复（新功能，已实现待验证）

需求来源：对标审查里列为 B 档第 1 项的「换机/重装丢数据」这个洞。2026-11-23 签名定案时确认过，0.9.4 及更早的包装不上新版本、只能卸载重装，用户手绘的路线、课表、自建作业会跟着存储一起没；`android:allowBackup="false"` 之后系统也不会代劳。所以补一个用户自己按一下就生成的 JSON 备份。

改动：

| 位置 | 内容 |
| --- | --- |
| `backup/BackupSection`、`BackupBundle`、`BackupCodec`（新） | 文件格式：`format="unloop.backup"`、`formatVersion=1`、`createdAt`（ISO-8601）、`appVersion`/`appVersionCode`、`sections:[{name,schema,count,data}]`，缩进输出。解码是「整体能读就尽量读」：不是 JSON、`format` 对不上、`formatVersion` 比本机新才判失败，单个坏段落只跳过并记一条告警；超 32 MB 直接判「拿错文件」。时间字段读不出来按 0 处理，不把整份备份判死 |
| `backup/BackupMerge`（新） | 纯函数的合并判定，`Policy{REPLACE, MERGE_NEWER, KEEP_MINE}` × `Action{ADD,REPLACE,KEEP,DROP}`，条目顺序固定「先本机后新增」，`entries()` 与 `actions()` 一一对应（KEEP 用本机那份、REPLACE 用备份那份），备份里重复的 id 只补一次 |
| `backup/BackupValues`（新） | SharedPreferences 值带类型标记序列化（`b/i/l/f/s`）。JSON 里 3 与 3.0 是同一种数字，标记丢了写回时就会 `ClassCastException`，因此未知标记与缺值条目一律跳过，不猜类型 |
| `backup/BackupCatalog`（新） | 7 个段落与键白名单：自建路线、路线文件、课表、自建作业、教务学校信息、运行与界面设置、作业缓存（最后一项默认不勾选）。**白名单而不是黑名单**：新增的键默认不进备份。`study_auth`（学习通 cookie/uid）与 `app_update`（下载状态）整文件排除，`active_run_id`/`active_lap_meters`/`period_minutes` 三个运行期键排除——恢复后不能假装「上次还在跑」 |
| `backup/BackupStore`（新） | 收集与写回：路线段跳过预设（预设靠激活码补回，存进备份只会在换机后变成重复预设），路线文件段读 `files/routes/*.json`；恢复时逐条经 `CampusRouteCodec` 校验再合并，文件段先要求 `RouteRepository.isValidId(id)` 且 id 与内容一致，仍走 `save()` 的原子写与隔离目录。空段落不写入文件 |
| `backup/BackupTransfer`（新） | 唯一碰 `ContentResolver` 的一层：单线程 `ExecutorService` + 主线程 `Handler` 回调，失败只回异常原文，文案交给界面。本机不认识的段落 id 一律不写，只报数量，内容仍完整留在文件里，所以新版导出、旧版恢复再新版恢复不会丢东西 |
| `AppSettingsActivity` + `activity_app_settings.xml` + `ic_fm_backup/restore.xml`（新） | 设置页新增「数据」分组两行：导出备份、恢复备份。导出＝多选段落（默认勾选按清单来）→ 系统保存面板（`ACTION_CREATE_DOCUMENT`，`application/json`，默认名 `unloop-backup-<版本>-yyyyMMdd-HHmmss.json`）。恢复＝`ACTION_OPEN_DOCUMENT` → 只列出本机认识且有内容的段落 → **二次确认**（点名哪些段落会整段替换、自建路线只补缺、是否有本机不认识的段落）→ 写回后按「已恢复 N 项，跳过 M 项」提示 |
| `strings.xml` | 新增 29 条文案：7 条段落名 + 21 条对话框与提示 + 1 条「数据」分组标题；整段替换与只补缺分开说，不用一句「会覆盖数据」糊过去 |
| `BackupCodecTest`、`BackupMergeTest`、`BackupValuesTest`、`BackupCatalogTest`（新） | 26 个用例。除格式与合并边界外，`BackupCatalogTest` 守三条不变量：同一个键不能出现在两个段落；键类型必须在声明的标记集合里；清单里不得出现 `cookie/token/password/uid/secret` 或排除文件/排除键——防止以后有人图省事把凭据加进白名单 |

顺带修的一处隐患：`List.of()`/`Map.of()` 这类 Java 9 工厂方法在 Android 上是 API 30 才有的，本机 minSdk 29，调用会在 Android 10 真机抛 `NoSuchMethodError`；lint 的 `NewApi` 对 `java.*` 覆盖不全，0 errors 也照不出。新代码里的 13 处已全部换成 `Arrays.asList` / `Collections.singletonList` / `Collections.emptyList`（仓库其它主源码本来就没有 `List.of` 用法）。

已知取舍：① 备份不含学习通登录态，恢复完要重新登录一次，这是有意的——cookie 出去的风险比省一次登录的收益大；② 段落级选择，键级不能挑（想只恢复课表周数而不覆盖课程，做不到）；③ 课表、设置这类「一份当前状态」的数据整段替换，靠确认对话框承担告知责任；④ 预设路线不进备份。

验证：303 个 JVM 用例全过（42 个测试类，较 0.9.9 的 277 例 / 38 类 +26，即上述四个新测试类）；`lintRelease` 0 errors、279 warnings（与基线持平，新增文案与资源没引入新告警）；`assembleDebug` 通过，`ActivityAppSettingsBinding` 能读到 `rowBackupExport`/`rowBackupImport`。本机无 adb 也无模拟器，因此 SAF 保存面板的实际落盘、各家文件管理器对 `application/json` 这个 MIME 的接受度、恢复后课表/路线/悬浮窗位置是否真的生效，都还只是静态验证，真机效果待复验。


## 2026-11-24 常用点位收藏（新功能，已实现待验证）

需求来源：对标审查 B 档第 2 项。虚拟定位页每次都要重新搜索或拖地图才能回到「宿舍楼下」「东门」这类点，`docs/11_使用手册.md` 11.4 写的悬浮条 A/B/C 快捷传送点是 0.2.x 的菜单形态，代码里早已不存在（Explore 全仓确认无残留存储），所以这次把它做成真正的一等功能：可命名、可增删的点位列表，而不是三个固定槽位。

改动：

| 位置 | 内容 |
| --- | --- |
| `map/MapPoint`（新） | 不可变模型：`id`/`name`/`GeoPoint`/`createdAtMillis`。名称在这里收敛一次——去空白、超 40 字截断、空值退成「未命名点位」，界面和反序列化共用同一条路，列表里不会出现空白行。改名是换新对象而不是就地改 |
| `map/MapPointCodec`（新） | JSON 格式 `{version:1, points:[{id,name,latitude,longitude,createdAt}]}`，与 `CampusRouteCodec` 同一套约定：整体读不动返回空列表，单条坐标越界／非有限数／缺 `id` 只跳过该条，重复 `id` 只留第一条，超过 100 条截断。上限是为备份文件可读，不是存储放不进 |
| `map/MapPointStore`（新） | 存进 `map` 文件的 `favorites` 键（与底图图层同文件、不同键：图层是界面状态，收藏是用户数据）。`add()` 返回 `AddResult{SAVED,DUPLICATE,LIMIT}` 而不是 `null`，界面才能说清「没存进去」的原因是重复还是满额。5 米内算重复收藏同一个点 |
| `activity_virtual_location.xml` | 新增第二枚 FAB「常用点位」，坐在「定位当前位置」正上方（`marginBottom=84dp`），默认 FAB 尺寸 56dp 满足触摸目标；不占纵向面板空间，跑圈页与悬浮台都不受影响 |
| `dialog_map_points.xml`（新）+ `ic_campus_bookmark.xml`（新） | 与路线列表同款底部弹层：标题＋滑动提示＋列表＋空态文案＋两个按钮。列表内部滚动、按钮始终外露的高度和 insets 算法照抄路线弹层 |
| `VirtualLocationActivity` | `showMapPointsSheet()`／`addMapPointRow()`／`promptMapPointName()`／`saveMapCenterAsPoint()`／`deleteMapPoint()`。点一行＝走既有的 `applyPoint()`，因此「单点保持中直接移动、跑圈中拒绝、空闲只预览」这套判定不重复实现；重命名与删除放在 `SwipeActionRow` 的横向按钮里，轻触碰不到销毁性动作；删除后 toast + 重新弹层，与路线列表行为一致 |
| `BackupCatalog` + `BackupStore` | 新增第 8 个段落 `map_points`（`keyed=true` → 导入策略 `KEEP_MINE`，只补缺不顶掉本机改过的名字）。收集复用 `MapPointCodec.encode` 的输出，恢复时逐条经 `MapPointCodec.decode` 校验、按 id 合并、到 100 条就停。上一节刚做的备份因此顺带覆盖收藏，不用再改一次格式 |
| `strings.xml` | 新增 16 条文案（含「重命名」「已重命名为」两条滑动动作与提示） |
| `MapPointCodecTest`（新） | 10 个用例：完整往返、空/坏 JSON/结构不符、坏一条只丢一条、重复 id、超上限截断、名称兜底与截断、`create()` 随机 id 与负时间归零、缺 id 直接拒 |

已知取舍：① 收藏页只在虚拟定位页开放，跑圈页没有入口——跑圈靠路线，点位在那里会和「选起点」打架；② 名称硬编码兜底串 `未命名点位` 留在 Java 里，是为了让 `MapPointCodecTest` 保持纯 JVM 用例（不碰 `R`），代价是这条串不随语言切换；③ 删除不二次确认，误删后重新收藏同一个点成本很低，路线删除本来也没有确认；④ 不做「按收藏时间排序」以外的分组、备注、图标——先让「记住一个点」这件事成立；⑤ `docs/11_使用手册.md` 没有同步——它首页写明「适用版本 0.3.3」，整篇按当前界面重写属于另一件事，这次的说明先落在本节。

验证：313 个 JVM 用例全过（43 个测试类，较备份那版的 303 例 / 42 类 +10，即 `MapPointCodecTest`）；`lintRelease` 0 errors、279 warnings（与基线持平）；`assembleDebug` 通过，`DialogMapPointsBinding` 与 `binding.buttonMapPoints` 能读到。本机无 adb 也无模拟器，因此底部弹层在高机型上的 insets 内缩、`SwipeActionRow` 与地图手势抢不抢触摸、`mapHost.cameraCenter()` 在首帧未就绪时是否真的返回 null（提示「地图还没有准备好」）、以及从旧版本升级后 `map` 文件新增键是否与图层键互不干扰，都还只是静态验证，真机效果待复验。

## 2026-11-24 坐标粘贴识别（新功能，已实现待验证）

需求来源：对标审查 B 档第 3 项。之前只有 Photon 地名搜索，别处复制来的「30.2741, 120.1551」或一条地图分享链接粘进去只会搜出一堆不相干的地名；0.1.1 时代那个「输入经纬度」的菜单项在当前界面里也没有了。

改动：

| 位置 | 内容 |
| --- | --- |
| `search/CoordinateInput`（新） | 纯 Java 的文本→坐标识别，四种写法：裸「纬度,经度」（半角/全角逗号、分号、斜杠、空白分隔，允许尾随缩放级 `15`/`15z`）、`lat=`/`lng=` 键值（顺序无关、可出现在串首）、Google 的 `@纬度,经度,15z` 与 `?q=`/`?query=`（含 `%2C` 转义逗号）、带 N/S/E/W 半球字母的度分秒。命中即返回 `Hit{point,converted}`，认不出返回 null |
| 同上：坐标系处理 | 站点名单（`amap.com`/`gaode.com`/`map.qq.com`/`ditu.qq.com`）判定原文是 GCJ-02，取数后走 `ChinaShift.gcj02ToWgs84` 换算并把 `converted=true` 带回界面说明；高德的 `position=`/`center=`/`coords=` 是**经度在前**，单独一条支路按相反顺序取值。境外点不偏移，所以也不会谎称换算过 |
| 同上：宁严勿宽 | 含中文且不是度分秒、又不是链接的串直接当搜索词；裸数字要求整段只有这两个数（「30.1, 120.2 附近」不认）；范围在构造 `GeoPoint` 之前判完（它的构造对越界是抛异常，不是返回 null） |
| 复用而不是改：`search/PhotonSearch.Place` | 它的构造本来就是包内可见，`CoordinateInput` 同包直接调用即可——识别到的坐标扮成一条搜索结果，既有的结果列表、距离显示、点选即传送全都白拿，不新写一套 UI，`PhotonSearch` 一行未动 |
| `VirtualLocationActivity` | `scheduleSearch()` 里先试识别，命中就同步渲染、**不联网也不等 350ms 防抖**；`coordinateHit` 用来把「数据来自 Photon/OpenStreetMap」那行换成「这一条来自你粘贴的坐标，没有联网查询」——来源说不清比多说一句更糟 |
| `strings.xml` | 新增 4 条：结果标题、两种来源说明、替换后的来源行 |
| `CoordinateInputTest`（新） | 14 个用例：分隔符与缩放级、正负号、`GeoPoint.format()` 自己输出的串能贴回来、越界不算坐标、普通地名与 `maps.app.goo.gl` 短链不认、度分秒缺半球字母不认、Google 三种链接原样取数、高德 `lat/lng` 换算后偏移量级在 20–2000 米、高德 `position` 的经纬顺序认错会落到几千公里外、境外点不偏移且标记回到未换算、百度 `location=` 明确不认 |

已知取舍：① 百度分享链接（BD-09）不认——本应用没有 BD-09 的换算实现，认错坐标系会把点丢到几百米外，不如让人去高德/Google 复制；② `maps.app.goo.gl`、`surl.amap.com` 这类短链需要联网解析跳转，识别阶段只做纯文本，不碰网络；③ 「30.1, 120.2 附近」这类混着中文的串会被当搜索词，判断依据是键名而不是「看起来像不像数字」，为的是不错手改坐标；④ 粘贴识别只负责选点，要长期保留仍要走「常用点位」的收藏。

验证：327 个 JVM 用例全过（44 个测试类，较收藏那版的 313 例 / 43 类 +14）；`lintRelease` 0 errors、279 warnings（与基线持平）；`assembleDebug` 通过。本机无 adb 也无模拟器，因此各家地图 App 分享文本的真实格式（尤其高德 App 内「复制链接」到底给的是 `uri.amap.com` 还是短链、腾讯位置分享的参数名）没有逐条实测，识别是按公开文档与常见形态写的，真机粘贴效果待复验。

## 2026-11-24 选点与生效分离（单点保持中先待确认，已实现待验证）

需求来源：对标审查 A 档第 4 项。原来单点保持中再点地图就直接 `SimulationService.teleportTo()`，一次误触就把别的应用正在读的位置挪走了；地图点击、搜索结果、常用点位三个入口都走这条 instant 路径，没有任何反悔机会。

改动：

| 位置 | 内容 |
| --- | --- |
| `VirtualLocationActivity.applyPoint` | 拆成 `applyPoint()`（保持中记成 `pendingTarget`，镜头跟过去但不发命令）与 `applyPointNow()`（立刻生效，供「定位到实际位置」这类明确命令与确认动作复用）。跑圈中拒绝、空闲只预览这两条判定不变，仍然只有这一个入口 |
| 同上：`confirmPendingTarget`／`dropPendingTarget` | 确认走 `applyPointNow()` 并提示「已传送到新点位」；放弃只清状态。待确认期间位置不动，`textCoordinates` 显示的是**生效中**那一组数，不是新选的 |
| `activity_virtual_location.xml` | 主按钮上方新增 `pendingBar`（48dp 两个文字按钮：放弃新点／确认传送），只在有候选点时可见；搜索态收起地图时一起隐藏 |
| `render()` | 待确认时目标行改成「生效中 X → 待确认 Y」，提示行换成「已选新点，确认前正在保持的位置不会动」；待生效的点用 `renderRoute(单点, showVertices=true)` 画成起点样式标记——借用地图接口已有的能力，不给两个引擎各加一个 marker API |
| `onSnapshot()` | 保持结束时 `pendingTarget` 直接变成预览点（此时已经不存在「别挪走正在生效的位置」这回事），用户接着按「开始定位」用的就是它，不会白选 |
| 返回键 | 先放弃候选点，再退出页面，不会带着一个没生效的选择离开 |
| 文案 | 4 条新增（确认/放弃/待确认目标行/提示），并改掉 3 条已经不诚实的说法：`virtual_status_holding`、`virtual_hint_move`、`virtual_target_hint` 原本都写「可直接移动位置」；顺手删掉不再有用处的 `virtual_teleport_sent` |

已知取舍：① 「定位到实际位置」不经过确认——它是用户主动要求回到真实位置，再加一步只会变成摸不着头脑；② 不做「多少米以内免确认」这类阈值，规则一旦要看距离，用户就猜不到这次会不会立刻生效；③ 候选点借用 `renderRoute` 的起点样式，颜色与路线起点相同，将来这一页如果要画路线，得换成独立 marker。

验证：327 个 JVM 用例全过（44 类，与上一节持平，这次改的是界面状态机）；`lintRelease` 0 errors、279 warnings（与基线持平，删掉的那条文案抵掉了新增的告警）；`assembleDebug` 与 `compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此确认条在搜索态/键盘弹出时的挤压、`renderRoute` 单点标记在两种地图引擎上的实际观感、以及保持中确认传送后目标 App 是否真的跟着挪，都还只是静态验证，真机效果待复验。
