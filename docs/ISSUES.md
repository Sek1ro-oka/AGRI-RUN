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

验证：327 个 JVM 用例全过（44 类，与上一节持平，这次改的是界面状态机）；`lintRelease` 0 errors、279 warnings（与基线持平）；`assembleDebug` 与 `compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此确认条在搜索态/键盘弹出时的挤压、`renderRoute` 单点标记在两种地图引擎上的实际观感、以及保持中确认传送后目标 App 是否真的跟着挪，都还只是静态验证，真机效果待复验。

## 2026-11-24 悬浮控制台折叠成小球（新功能，已实现待验证）

需求来源：对标审查 A 档第 5 项的后半。摇杆锁定那半（点一下锁定／解锁、跑圈进行中强制锁定、收起后回到锁定默认态）之前已经上线，这次翻代码确认无需重做；缺的是「面板挡屏幕」这半——控制台展开约 204×150dp，横在别的 App 上会压住下面的按钮，而「定位保持在某处、我只想让位置不动」的时候整块面板没有用处。

改动：

| 位置 | 内容 |
| --- | --- |
| `overlay/OverlayFold`（新） | 折叠／展开用到的三段纯 Java 算术：`keepCenter()` 在内容换尺寸时按**中心不动**换算左上角、`clamp()` 把左上角夹回屏幕、`isTap()` 用触摸阈值区分点一下与拖动。悬浮窗的 `x/y` 是左上角，面板换成 56dp 的小球不换算就会「跳」到面板原来的左上角，看着像飞走了 |
| `OverlayPanel`：小球 | 56dp 圆形，摇杆图样 + 选中态描边；运行中图样取薄荷绿、空闲取灰——小球上没有文字和数值，颜色是唯一能说「有没有在跑」的地方（在 `update()` 里跟快照一起刷） |
| `OverlayPanel.toggleFold` | 先 `stopMoving()`（面板收起来了不该还往服务端发方向），再把缩放容器里的内容换成小球／面板，然后 `post()` 里按新尺寸换算位置并写回 `overlay_x/y`。折叠状态存 `ui` 文件的 `overlay_folded`，服务重启后按上次的样子出现；只在折叠时提示一次「点一下小球就能展开」 |
| 面板下排 | 三档速度仍按 120dp 居中，右侧空出来的宽度正好是一枚按钮，「折叠成小球」坐在那里，即「关闭悬浮屏」的正下方。因此入口是外露的（不靠长按猜），代价是面板整体尺寸与行高都没变 |
| `OverlayPanel.BallListener` | 按住拖动挪位置；松手时位移不超过 `ViewConfiguration.getScaledTouchSlop()` 就 `performClick()`，展开走点击监听。不让框架自己判点击——拖动过程也会走 `ACTION_UP`，每次挪完位置都会误展开一次；`ACTION_CANCEL` 只收按压态不算点 |
| `clampToScreen`／`persistPosition` | 夹边逻辑与「记住位置」抽出来给拖动松手和折叠换算两条路共用，原来的两处内联写法收掉；`OverlayFold.clamp` 因此是生产路径上的代码，不是只为单测存在的 |
| `ic_overlay_fold.xml`（新） | 折叠图样：四个角朝中心收的短线 + 中央圆点，与其余悬浮按钮同款描边风格（24 视口、圆头描边、纯白着色交给 tint） |
| `strings.xml` | 新增 3 条：折叠按钮的无障碍描述、折叠后的提示、小球的无障碍描述 |
| `OverlayFoldTest`（新） | 9 个用例：变小往里收、变大往外挪（100 位的 56→200 是 28，不是 −72）、同尺寸不动、奇数尺寸最多丢一像素、夹边三种情形、屏幕比视图窄时贴 0、3-4-5 斜向拖动算点、超过阈值算拖动、阈值缺失也至少要求 1 像素 |

已知取舍：① 折叠与位置、缩放同性质，属界面状态而非用户数据，因此不进备份段落；② 小球只有一个动作（展开），暂停／档位／收藏仍要展开才能操作——它存在的理由是不挡屏幕，不是第二个控制面；③ 折叠提示沿用面板既有的 `Toast` 通道，而 Android 12 起后台 Toast 会被系统压制，悬浮场景恰是后台，真机可能不显示，到时需要换成小球上的角标之类；④ 换算依赖 `post()` 时能量到新尺寸，量到 0 就按原位收下，顶多小球落在面板原来的左上角，下次拖动自动正；⑤ 折叠不改变摇杆解锁状态（`hide()` 才复位），展开后摇杆仍是收起前的锁定／解锁样子。

验证：336 个 JVM 用例全过（45 个测试类，较上一节的 327 例 / 44 类 +9，即 `OverlayFoldTest`）；`lintRelease` 0 errors、279 warnings——小球最初直接由 `onTouch` 调 `toggleFold()`，`ClickableViewAccessibility` 涨了警告，改成「触摸里 `performClick()` + 点击监听负责展开」后回到与基线持平；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过（不带模块前缀的 androidTest 任务会连 `:location-probe` 一起编译，离线缓存里没有它的 androidTest 依赖，验证命令要写 `:app:` 前缀）。本机无 adb 也无模拟器，因此小球在刘海／状态栏区域的可见性、拖动是否跟手与涟漪反馈、后台 Toast 是否被压制、以及折叠后位置在横屏与分屏下会不会被夹过头，都还只是静态验证，真机效果待复验。

## 2026-11-24 桌面长按快捷入口与快捷设置磁贴（新功能，已实现待验证）

需求来源：对标审查 A 档第 6 项。四个日常动作（看跑圈、选点定位、看课表、查作业）都要先进主页再点卡片，下拉通知栏里也没有本应用的任何入口；悬浮台开关更是藏在设置页——它本来就是给「人已经在别的应用里」用的，位置反了。

改动：

| 位置 | 内容 |
| --- | --- |
| `entry/HomeTarget`（新） | 四个目标的枚举。认串（`from`，纯 Java）与跳转（`open`，只有这里碰 Android API）分开。清单里只有主页 `exported`，而快捷方式的 intent 由启动器（另一个应用）发出，直接指向内部页面会被系统拒绝，所以入口统一先进主页、由主页带 `home_target` 再跳一次 |
| `res/xml/shortcuts.xml`（新）+ 清单 meta-data | 四条静态快捷入口：校园跑／虚拟定位／今日课表／查作业，每条都指 `MainActivity` 只换 extra。`targetPackage` 故意不写——缺省取声明方自己的包，debug 构建带 `.debug` 后缀时才不会指到别的包，写死反而要按 buildType 维护两份 |
| `shortcut_*.xml`（新 4 个） | 图标外壳：应用内的矢量是白描边（画在深色卡片上），桌面长按菜单的背景由启动器给，白色会看不见。按品牌绿重着色后，浅色启动器拿到深绿、深色启动器拿到浅薄荷，两边都不瞎 |
| `MainActivity` | 冷启动只认 `savedInstanceState == null`（进程被杀后恢复不该把用户又甩进别的页面），`onNewIntent` 记下目标，真正跳转放在 `onResume`——那时本页可见，启动内部页面才不会被后台限制挡下。主页四张卡片与底栏两个入口的 `startActivity` 也改走同一枚举，入口定义只剩一份 |
| `entry/OverlayTileService`（新） | 悬浮台开关磁贴：直接调 `OverlayHost.setEnabled`，不必先进设置页。声明 `TOGGLEABLE_TILE`，TalkBack 会念成「打开／关闭」而不是一个普通按钮 |
| `entry/OverlayTileState`（新） | 磁贴三态判定（纯 Java）：开着且已授权＝亮、关着＝灰、开着但没给「显示在其他应用上层」权限＝**不可用**并把点击带去授权页。第三种如果也显示点亮，用户点下去什么都不发生，只会以为功能坏了；切成「已开启但看不见」比不切更难解释 |
| `entry/VirtualLocationTileService`（新） | 「虚拟定位」磁贴只把人送到选点页，不替用户开始保持定位——从下拉栏一键挪走别的应用正在读的位置，误触代价太大，这和「单点保持中选点先待确认」是同一条判断。跳转走 `startActivityAndCollapse`（磁贴属后台，Android 10 起后台不能随意起界面，这是系统给快捷设置留的口子）：34+ 用 PendingIntent 重载，34 以下只有旧的 Intent 重载 |
| 清单 | 主页挂 `android.app.shortcuts`；两个 tile service 各带 `BIND_QUICK_SETTINGS_TILE`、`exported="true"`、`android:description`（Android 14 起磁贴编辑列表要显示描述） |
| 测试 | `HomeTargetTest` 4 例：每个 key 认回自己、`null`/空串/大小写/整条类名都不认、key 不重复、**把 `shortcuts.xml` 与枚举逐条对账**（改了枚举忘改 XML 时，桌面点了只会回主页且看不出坏在哪）；`OverlayTileStateTest` 3 例覆盖三态 |

已知取舍：① 快捷入口全部经主页中转，代价是多一次页面切换，换来内部页面继续不导出；② 旧的 `startActivityAndCollapse(Intent)` 分支必须留着（minSdk 29），而 lint 的 `StartActivityAndCollapseDeprecated` 是 error 级且不认版本判断能豁免，所以在低版本分支就地 `@SuppressLint` 并写明理由，34+ 走新写法；③ 静态快捷方式在部分国产 ROM 桌面上不显示或改由厂商自己的长按菜单接管，这不是应用能兜住的，只能真机逐家确认；④ 暂不做动态快捷方式（`ShortcutManager`），它要等「最近路线／历史」这类运行时数据才有意义；⑤ 磁贴不反映「当前是否在跑」，只反映开关，运行数值在页面和悬浮面板里看。

验证：343 个 JVM 用例全过（47 个测试类，较折叠小球那版的 336 例 / 45 类 +7）；`lintRelease` 0 errors、279 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过；合并后的 release 清单已确认带 `android.app.shortcuts` 与两个 tile service、`android:description` 保留。本机无 adb 也无模拟器，因此各家启动器是否真列出这四条、磁贴能否拖入下拉栏、Android 14 的描述显示、进程冷启时从磁贴切开关悬浮面板能否立刻出现，都还只是静态验证，真机效果待复验。

## 2026-11-24 环境准备有序清单（新功能，已实现待验证）

需求来源：对标审查 A 档第 7 项。设置检查页已经存在，但它把五项状态平铺成一组「正常／需确认／失败」，五颗按钮不分情况一直亮着——用户看得懂哪一项红了，却不知道**先处理哪一项**，也看不出「处理完这一项还差几项」。主页更是完全没有环境提示：新装好、开发者选项没开，点进跑圈只会撞到「开始运行失败」。

改动：

| 位置 | 内容 |
| --- | --- |
| `settings/SetupItem`（新） | 五项准备项的枚举，**声明顺序即推荐完成顺序**：定位权限 → 系统定位开关 → 模拟位置应用 → 悬浮窗 → 通知。前三项 `blocking()`（缺了跑不起来）、后两项可选；每项带一个 `Entry`（去跑圈页／系统定位设置／开发者选项／悬浮窗授权／通知授权），跳转知识集中在这里而不是散在界面里 |
| `settings/SetupGuide`（新） | 纯 Java 读检查列表：`next()` 按 `SetupItem` 顺序取第一个未就绪项（因此与 `inspect()` 拼装顺序无关）、`pending()`／`pendingBlocking()`／`ready()`。可选缺两项也算「就绪」——主页卡片不能因为用户不想要悬浮窗就永远挂着 |
| `SettingsInspector.Check` | 多带一个 `item()`；`inspect()` 改为按 `SetupItem` 顺序输出，检查页从「状态清单」变成「按顺序做的清单」，`settings_intro` 那句「按下面顺序准备环境」第一次真的成立 |
| `SettingsInspector`：模拟位置可信窗口 | 系统不给「读取当前模拟位置应用」的接口，原来的检查因此恒为「需确认」，清单永远走不到头。改成记住最近一次探测成功的时间（`mock_allowed_at`，14 天窗口）：`probeMockProvider` 成功即记、被拒即清，`inspect()` 据此给 OK／WARN 并在说明里写「N 天前确认」 |
| `SettingsCheckActivity`：行内入口 | 未就绪的行下方长出一颗描边按钮（44dp 高，满足触摸目标），文案按准备项分别是「去跑圈页授权／打开定位设置／打开开发者选项／去授权悬浮窗／开启通知」。底部那排不分情况一直亮的按钮收掉三颗，只留「重新检查／检测模拟位置权限／打开应用详情」；卡片顶部新增一行「下一步：X（还有 N 项待处理）」，全就绪时换成「环境已就绪，可以直接开始跑圈」 |
| `SettingsCheckActivity`：通知走系统弹窗 | 「开启通知」直接 `requestPermissions`（33+），比把用户扔进通知设置页少两步；授权回调后重刷列表。探测完成后也立刻重刷，否则说明刚确认完、行上还挂着「去处理」 |
| `MainActivity`：引导卡 | 环境还差**阻塞项**时在跑圈卡片下方显示「下一步：X／还差 N 项」，点击进入设置检查页；`onCreate` 与 `onResume` 各刷一次，从系统授权页回来就能看到它自己消失。可选缺项不触发这张卡 |
| 测试 | `SetupGuideTest` 6 例：空表／全就绪、乱序与倒序列表里下一步仍是定位权限、只差可选项时 `ready()` 为真、阻塞项与可选项计数分开，以及一条**顺序不变量**（任何阻塞项都不得排在可选项之后）——它当场逮到 `blocking` 参数被写反；`MockTrustWindowTest` 7 例钉住时间窗边界：从未记录、记录瞬间、窗内、正好满窗、超一毫秒、时间戳在未来（时钟被回调），以及窗口长度落在 7～30 天之间 |

已知取舍：① 引导清单只管**环境**，导入课表、跑第一圈这些内容动作不排进来——它们各有自己的入口和状态，硬塞进 `SettingsInspector` 会把只读体检变成跨模块取数；② 定位权限的入口只能跳回跑圈页，因为运行时授权要由发起它、也懂得降级说明的页面弹出（`shouldShowRequestPermissionRationale` 的判断在那里）；③ 模拟位置用「最近一次探测成功」代替真实状态，14 天内如果用户自己取消勾选，清单会短暂地说「已就绪」——但真正跑起来时探测失败会当场纠正它并重新要求确认，比永远显示「需确认」更可用；④ 主页卡片只在缺阻塞项时出现，可选权限的提醒只留在检查页里，代价是悬浮窗一直没授权的用户看不到红点（面板本就不显示，不会有「坏了」的错觉）；⑤ 不做「首次启动弹引导对话框」：系统权限弹窗已经连着来两轮，再压一层模态只会让人乱点「不再询问」。

验证：356 个 JVM 用例全过（49 个测试类，较上一节的 343 例 / 47 类 +13，即 `SetupGuideTest` 与 `MockTrustWindowTest`）；`lintRelease` 0 errors、279 warnings（与基线持平，行内按钮用 `TextView` 自己画描边并显式 `setClickable/setFocusable`，没有新增无障碍警告）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此各家 ROM 上 `ACTION_LOCATION_SOURCE_SETTINGS` 能否落到定位开关、通知权限弹窗在「已拒绝且不再询问」时是否静默返回、探测成功后 14 天窗口内的文案、引导卡在长标题（如「悬浮窗权限（显示在其他应用上层）」）下是否折行，都还只是静态验证，真机效果待复验。

## 2026-11-24 跑圈历史与统计（新功能，已实现待验证）

需求来源：对标审查 B 档第 1 项。跑完一次之后什么都留不下来——里程、配速、今天跑没跑过，全在停止那一刻随快照消失；用户要在学校的 App 里交作业，回头却连「我这周跑了多少」都答不上来，而这是本应用唯一真正产出的数据。

改动：

| 位置 | 内容 |
| --- | --- |
| `run/RunRecord`（新） | 一次跑圈的收尾值对象：路线名、结束时刻、有效用时、里程、圈数与目标圈数、是否跑满。构造函数把负数夹成 0，`paceSecondsPerKm()` 在里程或用时不足时给 `-1` 而不是除零。起点坐标不进记录——路线名与圈长已能说明跑的是哪条线 |
| `run/RunHistoryCodec`（新） | JSON 数组编解码，沿用自建作业那套：整体损坏返回空表，坏条目跳过好条目，缺字段按默认值补 |
| `run/RunHistoryStore`（新） | 独立偏好文件 `run_history`，最新在前，最多留 300 条（按每天 3 次跑够记两个多月）。里程为 0 的收尾不入库；同一次运行重复收尾（跑满后再点停止）按 `runId` 覆盖而不是记两遍 |
| `run/RunStats`（新） | 全部统计口径集中在一处纯 Java：累计／今天／本周／单次最佳／总有效用时、按里程加权的平均配速、连续天数。一天按东八区切（用设备时区会让跨时区或改过时区的设备把同一次跑记到别的日子），一周从周一开始与课表周次对齐，今天没跑时连续天数从昨天往前数（不把「还没开始」当断签） |
| `SimulationService` | `completeRun` 与 `handleStop` 在 `cleanupEverything()` 之前各调一次 `recordHistory()`——清理会抹掉 request/route/进度，而那正是记录要用的数。单点保持模式没有里程，不入库 |
| `ui/RunHistoryActivity`（新）+ 布局 | 顶部四格（累计里程、次数、平均配速、连续天数）加一行「今天／本周／单次最佳／累计用时」，下面是逐次清单：日期、路线名、圈数·用时·是否跑满，右侧里程。行左滑删除（复用 `SwipeActionRow`，与点位收藏同款），底部「清空全部记录」带确认 |
| 入口 | 设置页「数据」段第一行「跑步记录」（图标 `ic_campus_history` 是柱状图，读作统计而不是设置），清单里注册为不导出页面 |
| 备份 | 新增 `run_history` 段落（默认勾选）：历史记录是纯本机数据，换机重装就归零，正是备份该管的东西 |
| 测试 | `RunStatsTest` 13 例、`RunHistoryCodecTest` 6 例。前者钉住日期边界（周一为周首、东八区 00:30 属于今天而不是昨天）、加权配速（1 km/300 s + 4 km/1600 s 是 6'20"，不是简单平均的 5'50"）、连续天数三种走法（连着三天、今天还没跑、中间断了）；后者钉住坏 JSON 只丢条目、缺字段回落、负值夹紧 |

已知取舍：① 历史只记路线跑，单点保持（原地挂定位）不进里程表——那类运行距离恒为 0，进表只会让「次数」虚高而「配速」无从算起；② 只留 300 条上限，更早的直接丢（不做归档）：统计要的是近期趋势，两年前的某一次 800 米没有查询价值，真要长期留存走备份导出；③ 记录在服务里同步写偏好（`apply()`），跑圈结束时多一次磁盘写，换掉的是「服务被系统回收就再也拿不回这一次」的风险；④ 配速用有效用时（暂停不计），与地图页当时显示的口径一致，不是墙上时间；⑤ 没做图表，先给数字与清单——按天柱状图要等设备上真机看过行高与密度再画，否则只是猜。

验证：375 个 JVM 用例全过（51 个测试类，较有序清单那版的 356 例 / 49 类 +19）；`lintRelease` 0 errors、280 warnings，比基线多的一条是页面根布局自带背景色与主题重复绘制的 `Overdraw`，本应用另外 11 个页面同样如此（清单里没有一处 `tools:ignore`，属既有风格），没有引入新种类的警告；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此跑满／中途停止两条收尾路径是否各记一条、单点保持是否真的没进表、滑动删除是否与列表滚动抢触摸、日期行在 4 位年份 ROM 上是否折行、备份导出恢复后历史是否回来了，都还只是静态验证，真机效果待复验。

## 2026-11-24 课表桌面小组件（新功能，已实现待验证）

需求来源：对标审查 B 档第 3 项。课表在应用里做得再完整，用户每天真正需要的只有「今天几节课、下一节几点、还来得及去吗」，而这三个问题现在都要解锁、进应用、切到课表页才能回答；上课场景里最缺的就是不打开应用就能看到的信息。

改动：

| 位置 | 内容 |
| --- | --- |
| `widget/TimetableWidgetPlan`（新） | 纯 Java 的「今天怎么看」：五种状态（未导入课表、今天没课、正在上课、等下一节、今天上完）＋今天的课程行＋下一节及其倒计时。周次过滤复用 `TimetableText.activeInWeek` 与 `SemesterCalendar.teachingWeek`，节次时间复用当季作息；今天的课上完后继续往后找 `HORIZON_DAYS = 7` 天，所以「今天没课」说的是「下次什么时候有课」而不是空话。另带两个同样可测的换算：`maxRows(heightDp)` 按可视高度折行数（收在 2～8 行），`durationLabel(minutes)` 决定倒计时怎么说人话（不足 1 分钟／分钟／小时＋分／天＋小时） |
| `widget/TimetableWidgetProvider`（新） | `AppWidgetProvider`：`onUpdate`／`onAppWidgetOptionsChanged` 渲染，`onEnabled`／`onDisabled` 挂卸数据监听，`onReceive` 额外接住跨天、改时钟、换时区、开机、覆盖安装。文案分工固定：标题「今日课表 · 第 N 周」，状态行只说「现在」，中间逐行列课程（当前那节染成薄荷绿），底部一句解释为什么是这个结果或点进去能干什么。一行只有一条 `TextView`：2×2 时可用宽度只有 110dp 左右，分三列会把课程名挤成两三个字 |
| 尺寸与预览 | `res/xml/timetable_widget_info.xml` 给 `minWidth/minHeight` 并打开水平/垂直缩放，一个组件从 2×2 拉到 4×3 都能自适应，不为每种尺寸各写一个 provider；`targetCellWidth/Height`、`previewLayout`、`description` 是 12+ 才认的字段，用 `tools:ignore="UnusedAttribute"` 明确压掉而不是删掉（清单里唯一的 `tools:ignore` 先例是模拟位置权限） |
| 刷新链路 | 课表写入分散在手动添加、编辑、教务导入、清空、改作息、设开学日期六处，逐个调刷新必漏。改为 `widget/TimetableWidgetSync` 监听 `study_timetable` 这一份偏好文件，一处挂钩覆盖全部写入（监听器必须常驻强引用，`SharedPreferences` 内部只存弱引用）；`UnloopApplication.onCreate` 在桌面已有实例时补挂一次，`onEnabled` 挂、`onDisabled` 卸。`updatePeriodMillis` 系统最密只有 30 分钟，它只兜「一直停在桌面不动」的情况 |
| 入口 | 设置页「通用」段新增「桌面课表小组件」行，点下去走 `requestPinAppWidget` 直接请求放置；不支持的桌面弹手动路径（长按桌面 → 小组件 → UNLOOP「课表」），不静默失败。小组件点击与课前提醒通知打开同一张不导出的课表页：`PendingIntent` 由本应用自己创建，不必像桌面长按图标那样绕主页中转 |
| 布局与资源 | `widget_timetable.xml`（外壳）＋ `widget_timetable_row.xml`（一行，代码 `addView` 追加）＋ `ic_campus_widget.xml`（外框里一格方块两条线，读作「桌面小卡片」）＋ 三句占位文案，让初次落地和选择器预览都有内容可看；配色沿用 `bg_campus_card` 与 `campus_ink/muted/mint_dark`，随系统深色自动切 |
| 测试 | `TimetableWidgetPlanTest` 14 例：未导入、按钟表顺序而非录入顺序、正在上课的剩余分钟与之后的下一节、开课前倒计时、今天上完往后找（次日 13:30 记成 17 小时 30 分）、空白天往后找、七天内凑不出周次就没有下一节、单双周过滤、没设开学日期时按全部周次并标记出来、假期不列课、小尺寸把正在上那节提到第一行而大尺寸保持时间顺序、行高换算的上下限、节次超出作息被跳过、倒计时措辞 |

已知取舍：① 倒计时精度受刷新时机限制：`updatePeriodMillis` 最密 30 分钟，且国产 ROM 的省电策略会进一步拖延它，所以组件上「还剩 25 分钟」在设备上可能落后半格；真正卡点前看的是课前提醒通知，组件只负责扫一眼。② 颜色走系统深色模式而不是应用内的界面风格：`RemoteViews` 由桌面的 `Resources` 解析，用户在应用里强制深色／浅色时组件仍按系统设置取色，可能与 App 内观感不一致——与悬浮控制台缺 night 版是同一件事，留给 #34 一起处理。③ 组件不做「点某节课直接跳到那一格」「课间还有几分钟去下一个教室」，只回答几节／哪节／还有多久这三问；也拿不到考试周与调课数据，#31 做完才能显式标注「非教学周」。④ 未设置开学日期时按全部周次显示并写明原因，代价是单双周课可能列出一节本周其实不上，比整块空白更容易被理解。⑤ 只有一个 provider、靠高度折行数（2～8 行），没有为 2×2／4×2 各写一份布局，尺寸差异靠 `rows(limit)` 截断。⑥ 组件是只读视图，不提供在桌面上改课表、标记已上等操作。

验证：389 个 JVM 用例全过（52 个测试类，较跑步记录那版的 375 例 / 51 类 +14）；`lintRelease` 0 errors、280 warnings（与基线持平；`UnusedAttribute` 三条与「请求添加到桌面」这条未使用文案在提交前分别用 `tools:ignore` 说明理由、直接删掉处理）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此以下都还只是静态验证，真机待复验：`APPWIDGET_UPDATE` 能否送达不导出的接收器（清单按 `BOOT_COMPLETED` 那两个接收器的同一套理由声明）、各家桌面 `requestPinAppWidget` 是否支持、拉大组件后行数是否随之增加、跨天后 `DATE_CHANGED` 在省电策略下能否送达、深色系统下组件与桌面壁纸的对比、正在上课那节的薄荷绿在桌面上是否够醒目。

## 2026-11-24 课表导出日历与分享码（新功能，已实现待验证）

需求来源：对标审查 B 档第 4 项。课表只能看在本机那一块屏幕上——熄屏锁屏、日历应用、以及没有装 UNLOOP 的同学都拿不到它；导出 .ics 让系统日历接手提醒，分享码让「把课表发给你」不必截图十二张。

改动：

| 位置 | 内容 |
| --- | --- |
| `study/IcsTimetableWriter`（新） | 课表 → RFC 5545 文本。一门课一条 `VEVENT`，重复用 `RRULE:FREQ=WEEKLY;BYDAY=…;COUNT=…`，落空的周次用 `EXDATE` 逐周排除（单双周因此仍是一条事件，不是把 16 周摊成 16 条）。`DTSTART/DTEND` 写不带时区的本地钟点，`DTSTAMP` 写 UTC；TEXT 字段转义 `\` `;` `,` 与换行（CRLF 只算一个换行）；每行按 75 字节折行且绝不劈开多字节字符；`UID` 由课程 id 推出，同一份课表反复导出、重复导入时日历应用按 UID 合并而不是攒出堆叠的同名课 |
| `study/IcsTransfer`（新） | 把文本写进用户在系统保存面板里选的文件，`"wt"` 覆盖写，工作线程执行、主线程回调，与备份导出同一套做法。只有这一层碰 `ContentResolver`，因此格式生成全程可在 JVM 里断言 |
| `study/TimetableShareCodec`（新） | `UNLOOP1-` + Base64URL(GZIP(课程 JSON))。压缩是因为课表 JSON 里全是重复字段名，40 门课的码比原文短得多，粘进聊天框不至于糊满屏幕。解码宽容：从闲聊文本里抠出码本体、认小写前缀、去掉粘贴带来的换行；坏课程条目跳过并回报数量；解压上限 2 MB 挡住「几 KB 解出几百 MB」的压缩包；版本号不是 `UNLOOP1` 时按「没找到分享码」处理，不硬猜别人的格式 |
| `study/TimetableImportPlan`（新） | 导入前的冲突预演：同星期几、节次区间交叠、且周次至少重合一周才算撞车，逐条给出「周一 第2节：高等数学 与 大学英语」。追加路径按课程 id 去重（本机已有的那条不被第二份码覆盖），替换路径整表覆盖 |
| `ui/StudySettingsActivity` + 布局 | 课表卡片加三行：「导出课表（.ics）／复制分享码／从分享码导入」。导出前先把「日历里没有调课与节假日」说清楚，没设开学日期时直接拦住（没有第一天就排不出日期）；导入走「粘贴（剪贴板里现成的码会自动填上）→ 预览冲突 → 追加或替换全部」，替换再单独确认一次并写明会少掉几门课 |

已知取舍：① 事件时间用本地钟点（floating time）而不写 `VTIMEZONE`——作息本来就是本校当天的时间，换成 UTC 反而会在别的时区被平移，代价是跨时区设备上看到的是自己时区的钟点；② 不写 `VALARM`，提醒继续由本应用与课前提醒负责，否则同一节课会在日历和本机各响一次；③ 个别只认 `RRULE` 的解析器会忽略 `EXDATE`，把双周课排满整学期——主流日历应用都支持排除，这比给每周写一条事件更可读可改；④ 分享码是整份课表，且不含作息：对方导入后按自己的冬夏令时与上课时间显示，节次编号一致即可对上；⑤ 周次写法完全看不懂时按「每周」判冲突，宁可多提醒一次也不要漏掉真撞上的课；⑥ 不申请 `READ/WRITE_CALENDAR`，导出只落到一个文件——为一次性导出拿走整份日历的权限不划算，用户想要自动同步可以自行在日历应用里导入订阅；⑦ 导入替换是整表覆盖，不做按课程 id 的三方合并，因为课表没有「谁改过哪门课」的信息可依据。

验证：439 个 JVM 用例全过（55 个测试类，较课表小组件那版的 389 例 / 52 类 +50，即 `IcsTimetableWriterTest` 25 例、`TimetableShareCodecTest` 14 例、`TimetableImportPlanTest` 11 例）；`lintRelease` 0 errors、280 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此系统日历与 Google 日历能否识别 `EXDATE`、`text/calendar` 在各 ROM 保存面板里是否保留 `.ics` 后缀、哪家文件管理器会接手这个 MIME、微信换行截断后的码粘回来是否仍可解、导入对话框在长课表下几行冲突才不撑破屏幕，都还只是静态验证，真机效果待复验。

## 2026-11-24 调课、停课、补课与节假日（新功能，已实现待验证）

需求来源：对标审查 B 档第 5 项，也是 #31 的第一步。课表模型是「每周重复」，而真实学期里偏偏全是**只发生一次**的意外：运动会放假一天、国庆后按周五上课、某门课改到下周的晚上。此前这些只能靠用户手动改课表（改完就忘了改回来），而小组件与课前提醒照旧按重复规则响铃——这是全部课表功能里最容易把人坑到的一格。

改动：

| 位置 | 内容 |
| --- | --- |
| `study/DayAdjustment`（新） | 一条例外的值对象 + JSON。四种：`HOLIDAY` 全天不上、`MAKEUP` 按某个星期几的课表上、`CANCEL` 某门课当天不上、`MOVE` 某门课改到同一周的另一天／另一头节次。一律以**具体日期**为键（调课本来就是单次事件），改去哪一天由 `targetDate()` 从同一周的周一推出来，用户不必再填第二次日期；`slot()` 让「同一天同一门课」只留最后一次安排，避免先改课再停课留下两条互相打架的记录 |
| `study/TimetableDay`（新） | 「这天到底上什么课」的唯一算法：先按周次挑常规课，再套这天的例外——放假优先于一切，改课在同一格内换节次、换到别天则从原格消失并出现在目标格（陈旧记录不会凭空造出一节课），补课把借来的星期几整套路进来并按 id 去重。另有 `classesInWeek`（把一周七天摊平成网格数据）、`isClosed`、`onDay`、`replacing`、`removing`。周次认不出来（没设开学日期）时按星期几给课，算出周次为 0（学期外）时一节都不给 |
| `study/DayAdjustmentCodec`（新） | 与课表同款容错编解码：整体坏了当作没有，坏一条丢一条；写入时顺手清掉已经过去的日期并按日期排序，最多留 120 条 |
| `study/DayAdjustmentLabel`（新） | 例外的中文说法（「12-24 周四 放假」「…《高等数学》改到周五 第3-4节」），跨年补年份、课程被删写作「已删除的课程」、单节不说成「第3-3节」。放在纯 Java 里而不是拼在对话框代码里，是为了让这些边界也能被用例钉住 |
| `study/StudyTimetableStore` | 新增 `day_adjustments` 与 `loadAdjustments`／`saveAdjustments`／`putAdjustment`／`removeAdjustment`。写在同一份 `study_timetable` 偏好文件里，`TimetableWidgetSync` 的监听器因此不用改就覆盖到调课，桌面组件与本机课表不会各说一套 |
| 三处消费方 | `TimetableWidgetPlan.of` 多收一份例外清单并据此给出今天的行与下一节，另暴露 `closedToday()` 让组件说「今天放假」而不是「今天没课」；`ClassReminderPlanner` 的逐天循环改走 `TimetableDay`，停的那天不响、改到别天的在新那天响；`StudyActivity` 的网格在有开学日期时改用 `classesInWeek`，格子按具体日期填，于是屏幕上看到的这一周就是实际上的一周 |
| `ui/StudySettingsActivity` + 布局 | 课表卡片新增「调课与节假日」行（副标题写条数）。点开是例外列表：点一条问要不要删，右下角「添加」走录入链「选类型 → 选日期 → 选细节」。日期只让选今天及以后；补课再问「按星期几」，停课与改课只列**这天真的会上到的课**（`TimetableDay.classesOn`），改课接着问改到星期几与从第几节开始、连上几节沿用原长度。没设开学日期时改课被拦住（目标周次无从计算），课表为空时提示先导课表 |
| 备份 | `BackupCatalog` 的课表段加上 `day_adjustments`，例外跟着课表一起走；段落版本号不动，旧版应用读不认识的键会跳过，不会因此恢复失败 |

已知取舍：① 例外只作用于本机课表、小组件与课前提醒，**不进导出的 .ics**——日历里的 `EXDATE` 只能表达「这次不上」，表达不了「改到周五第3节」，写一半比不写更容易误导，日历侧继续靠重复规则，PR 说明里已写明；② 没设置开学日期时课表网格上的格子对不上具体日期，此时调课只在小组件与提醒里生效，改课直接拦住，避免「记了但看不见」；③ 同一天既写放假又写补课时无从判断用户到底想什么，按更保守的「不上课」处理；④ 改课的目标只在同一周内换星期几，跨周改课等于两次操作，因为跨周会连带改变周次归属、单双周课会算错，宁可用两次简单操作；⑤ 课程被删掉之后，指向它的例外会一起失效（显示成「已删除的课程」，不再产生任何效果），不做级联清理，免得用户手滑删课就把调课记录一起丢了；⑥ 不做法定节假日联网同步，用户得自己记——与「不申请日历权限」是同一个取舍；⑦ 上限 120 条并按日期清旧，代价是四年前的例外查不到，但小组件与提醒本来也只看未来七天。

验证：479 个 JVM 用例全过（57 个测试类，较分享码那版的 439 例 / 55 类 +40，即 `TimetableDayTest` 24 例、`DayAdjustmentLabelTest` 7 例、`TimetableWidgetPlanTest` +4 例、`ClassReminderPlannerTest` +5 例）；lint 0 errors、280 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，因此以下都还只是静态验证，真机待复验：`DatePickerDialog` 在各 ROM 上 `setMinDate` 是否真被禁用过去的日期、`setItems` 长列表（一学期几十条例外）是否需要滚动、改课链四层对话框在窄屏上的可达性、目标 API 35 下前台服务重启后提醒是否仍按新那天走、以及组件从「今天没课」切到「今天放假」的文案宽度。

## 2026-11-24 多学期归档与切换（新功能，已实现待验证）

需求来源：对标审查 B 档第 5 项的后半步（#31 第二步）。开学日期、总周数、作息、课表、调课记录全机只有一份，9 月把下学期录进来就得先把这学期删掉——而删掉之后再想回看「上学期那门课周几上」就没有了。

改动：

| 位置 | 内容 |
| --- | --- |
| `study/SemesterProfile`（新） | 学期身份：随机 id + 名字（截到 20 字，空名不合法）。第一次分学期时，原来那份数据需要一个名字，就按用户自己填过的开学日期推（`2026 秋季学期`／`2027 春季学期`），没填开学日期时按已有学期数往后编号，绝不凭空编名字 |
| `study/SemesterArchive`（新） | 除当前学期以外其余学期的**键值快照**。纯 Java、不可变（`withProfile`／`withValues`／`withoutValues`／`without`／`withRenamed` 全部返回新对象），最多 12 个学期；`courseCount`／`currentWeek`／`summary` 直接从快照里的 `courses`／`semester_start`／`total_weeks` 算，列表上「2 门课 · 第 3 周」「空课表」「未设开学日期」都是这样来的，不额外记一份冗余统计 |
| `study/SemesterStore`（新） | 归档落在单独的偏好文件 `study_semesters`（`active_semester` + `archive` 两个键），当前学期照旧写在 `study_timetable` 的原键上。切换＝现在这份写进归档 → 目标的快照还原回本机 → 把目标那份从归档里清掉；已经在这学期、或目标 id 根本不存在时**直接返回**，否则「先存再取」会把数据清成空表。新建、改名、删除同理：当前学期不允许删 |
| `study/StudyTimetableStore` | 加 `snapshotValues`（原样抄走 `getAll()`，不动类型）与 `restoreValues`（`clear()` + 按原类型写回 + 一次 `apply()`）。复用备份功能的 `BackupValues` 带类型打包，`semester_start` 这类 long 不会在往返中被折成 int；`KEY_COURSES`／`KEY_SEMESTER_START`／`KEY_TOTAL_WEEKS` 三个键名对本包可见，归档才不会各写一套键名 |
| `ui/StudySettingsActivity` + 布局 | 课表卡片新增「学期管理」行（副标题「N 个学期 · 当前「X」」，还没分学时直说）。列表每行带那几个数字，点开是「切到这学期／改名／删除」（当前学期只给改名）；新建后先问「要现在切过去吗」，切换前再把「本机几门课会存起来、换上几门课」讲一遍。切换完成后统一重算：开学日期、周次、课表行数、作息、调课条数、课前提醒预览，再重排闹钟并强制刷新桌面组件 |
| 备份 | `BackupCatalog` 课表段增加 `study_semesters` 的 `active_semester` 与 `archive` 两个键，换机后不会只剩一个学期 |

已知取舍：① 当前学期继续占用原有键，好处是所有读取路径（课表页、小组件、提醒、导出、备份）一行都不用改、旧版本升上来一个字都不动，代价是「本机这份属于哪个学期」只由 `active_semester` 一个字符串记着，手动改偏好文件能把归档搞乱；② 归档保存的是**整份键值**而不是重新设计一套学期数据结构，因此新版本加了新偏好键后旧归档会自动带上（不认识的键还原时被跳过），不需要迁移代码；③ 快照取走即从归档删除，同一份数据不留两个真来源，代价是「切换」是两次写盘，中途被杀进程可能出现两边都没有的状态——重启后本机这份仍在偏好文件里，实际可恢复；④ 不做跨学期合并（比如把上学期的课直接拷进这学期），那是复制课表而不是多学期，可以用分享码手工完成；⑤ 学期不自动翻页：开学日期换了周次自然变，但没有到期自动切换；⑥ 12 个学期上限来自「够用到毕业」，满了之后新建被拦住并提示先删；⑦ `SemesterStore` 不碰闹钟也不碰小组件重绘，重排由界面在切完后统一调用，免得课包反过来依赖组件包。

验证：502 个 JVM 用例全过（59 个测试类，较调课那版的 479 例 / 57 类 +23，即 `SemesterArchiveTest` 14 例、`SemesterProfileTest` 9 例）；`lintRelease` 0 errors、280 warnings（与基线持平——首轮 lint 抓到 `study_semester_switch_message` 的占位符数量与调用不符，改成三个参数后归零）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器：`SemesterStore` 与界面链路属于只能真机验证的部分（偏好文件读写、`clear()`+写回的原子性、切换后组件是否真的重画、闹钟是否按新学期重排、`EditText` 对话框在输入法弹出时的位置），全部属于「已实现待验证」。

## 2026-11-24 提醒中心（新功能，已实现待验证）

需求来源：对标审查 A 档第 3 项。「刚才那节为什么没提醒我」这个问题现在要跨两个页面查：课前提醒在课表设置里、作业提醒也在课表设置里、作息换季根本没人说、更新检查其实压根不自动——每一处只显示自己的下一次，合起来没人能一眼看完。

改动：

| 位置 | 内容 |
| --- | --- |
| `study/ReminderCenter`（新） | 纯 Java 的一张总表：五类事项（课前提醒、作业截止提醒、调课与节假日、作息换季、版本更新），输入是各功能**已经算好**的开关与下一次时间，输出按「有下一次钟点的最前、其余按固定次序」排好，并给每一项判一个 `Status`（`OFF` / `WILL_FIRE` / `NOTHING_PENDING` / `SCHEDULE_AHEAD` / `WILL_CHANGE` / `PINNED_SEASON` / `MANUAL_ONLY`）。中心不排闹钟、不改任何设置，展示一次不产生副作用。时间文案自己拼（`11月25日 07:50`、跨年补年份），因为各 ROM 的中文 `DateTimeFormatter` 有的会写成「上午7:50」 |
| `study/StudySchedule.nextSeasonChange`（新） | 下一次「按月推断」翻面的日期：5 月 1 日进夏令时、11 月 1 日回冬令时，跨年也算对；用户手动钉过作息则返回 {@code null}——它不会自己变，报个日期反而是骗人 |
| `study/StudyTimetableStore.seasonChosenManually`（新） | 上面那条判断需要的原始事实：偏好里到底存没存过季节选择（`loadSeason` 会把没存过时返回推断值，区分不出来） |
| `study/TimetableDay.nextAhead`（新） | 今天及以后最近的一条例外（含今天，今天的调课今天就得知道）；过期的不进中心 |
| `ui/AppSettingsActivity` + 布局 | 通用段课表小组件下面加「提醒中心」行：副标题直接给结论（「2 项会主动通知 · 1 项已排到下一次」，一项都没有就写「目前没有会主动通知的提醒」）。点开是一段逐项列表，每行「事项：状态」；两个出口——「系统通知设置」直达本应用的通知页（`ACTION_APP_NOTIFICATION_SETTINGS`，不认则退回应用详情页，再不行说一句实话）与「课表与作业提醒设置」跳到课表设置页 |

已知取舍：① 中心只**报告**不**操作**：要开关课前提醒还是回课表设置页，避免同一个开关在两处可改、状态不一致；② 调课与节假日、换季不会发通知，它们出现在这里是为了回答「为什么今天不用上课」，所以文案刻意不说「没开」而说「近期没有安排」「手动选过作息」；③ 版本更新永远标成「只在手动点的时候提示」——本应用没有后台检查（没有 WorkManager/JobScheduler，只有 AlarmManager 排课表与作业两类闹钟），写个「没开」是假话；④ 课前提醒「开关开着但没设开学日期」和「没开」分开写，因为用户要做的动作完全不同，这也是实际最常见的一种没响原因；⑤ 中心不做「测试通知」按钮：各家 ROM 的电池优化会吞掉即时通知，测出来「没收到」反而误导；⑥ 时间只显示到分钟、不显示倒计时，因为中心是给人看一眼现状的，倒计时是桌面小组件的活。

验证：514 个 JVM 用例全过（60 个测试类，较学期那版的 502 例 / 59 类 +12，即 `ReminderCenterTest` 10 例、`StudyScheduleTest` +2 例、`TimetableDayTest` +1 例、其余为回归）；`lintRelease` 0 errors、280 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器：`ACTION_APP_NOTIFICATION_SETTINGS` 在各 ROM 是否存在、`setMessage` 的长文本在小屏是否可滚、对话框三个按钮的排布（中文标签较长）都未实测，属真机待复验。

## 2026-11-24 本地崩溃记录（新功能，已实现待验证）

需求来源：对标审查 A 档第 4 项。现在应用崩了只剩系统那句「未响应」，用户说不清、我也查不到：栈在哪、哪一版、哪台机器。记录只写本机文件，不联网、不外发。

改动：

| 位置 | 内容 |
| --- | --- |
| `diagnostics/CrashReport`（新，纯 Java） | 一份崩溃记录的文本拼装：抬头四行（时间／版本／设备／线程）＋异常本体＋因果链。长度全部有上限并**写明被截了多少**——单帧 200 字、消息 500 字、主异常 24 帧、因果链 4 层、整份 16 KB；CRLF 当一个空格处理；抬头字段先吃掉换行再判空，缺字段写「未知」而不是抛新异常。文件名带时刻与毫秒，本身就是排序键，同一秒再崩也不会覆盖上一份 |
| `diagnostics/CrashStore`（新） | 唯一碰文件与线程的地方：`install()` 装在 `Application.onCreate` 的**第一句**（换风格本身也可能出问题，装在前面才有机会被记下来），写完一定把异常转交给原来的默认处理器——系统该收的进程照收，应用不做「崩了继续跑」；写文件失败被吞掉，因为崩溃本身才是要说的事，不能被写记录这件事反过来吞掉。落盘在 `files/crash/`，保留最近 10 份，读的时候新在前 |
| `ui/DiagnosticsActivity` | 报告里新增【崩溃记录】段：共几份、全部只存在本机不发网络、下面展开最新一份（更早的写明份数）。「清空日志」现在连崩溃记录一起清并照实说明——留一半清一半的报告最容易让人误判「应该没崩过」；复制报告因此也能把最新一份栈带出去，这是唯一的手动上报路径 |

已知取舍：① 不接任何崩溃上报平台（Firebase Crashlytics/Bugly 之类）：那要把栈和用户设备信息发到第三方，与这个应用「不联网也能用」的边界不符，日志只留在本机由用户自己决定要不要复制出去；② 只留最近 10 份、只展开最新一份：崩溃循环时一天就能写满，真正需要的通常就是刚刚那次；③ 不做「上次是否异常退出」的开机弹窗：记录是给人查的，不该主动打扰；④ 不在记录里写坐标、账号、路线名等任何业务数据，只有版本／设备／线程／异常——这类信息在诊断时并不必要，一旦外传代价却由用户承担；⑤ 环形 cause 有两种：JDK 禁止 `initCause(itself)`，但重写 `getCause()` 返回自己真能碰到，走到第二层认出循环即停；互指环（A→B→A）走不满深度上限就被截住；⑥ 崩溃时的写盘是同步的，因为进程正在退出，异步排队等于不写；⑦ `files/crash/` 在应用私有目录，其它应用与文件管理器都读不到，卸载即清空。

验证：528 个 JVM 用例全过（61 个测试类，较提醒中心那版的 514 例 / 60 类 +14，即新增 `CrashReportTest` 14 例；首轮有 3 例失败——单帧截断的原文字数算错、`initCause(itself)` 被 JDK 拒绝、CRLF 被拉成双倍行距——都已按实际情况修正实现与断言）；lint 0 errors、280 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器：真实崩溃（如 `IncompatibleClassChangeError`、native crash、OOM）能否被这个处理器接住、写盘在低存储时是否失败、以及系统默认处理器的转交在各 ROM 上是否仍弹「应用停止运行」，都属真机待复验；native 崩溃按预期不会有记录，这层只管 Java 异常。

## 2026-11-24 尺度令牌收敛与触摸目标（界面整改，已实现待验证）

需求来源：对标审查的界面整改项。改之前布局里散着 12 档字号（10/11/12/13/14/15/16/18/19/20/22/24sp）和 26 档间距（1~84dp 里的一堆奇数），同一个「次要说明」在有的页面是 10sp 有的是 11sp；`textColor`+`textSize` 成对重复了 144 次；24 个可点击控件被写死成 32~46dp，手指点不准；同一批里还有 6 个输入框与下拉框停在 44dp，因为它们不是 Button，连「可点击控件」这个说法都容易漏掉。`finmori_dimens.xml` 里那 4 个令牌从没被引用过——尺度看着有来源，其实没有。

改动：

| 位置 | 内容 |
| --- | --- |
| `values/dimens.xml`（新） | 尺度唯一来源：10 档字号（最小 11sp）、13 档间距（2~48，偶数）、3 档圆角、48dp 触摸下限、1dp 描边、两级阴影，另加两处阶梯外的实际留白（底部导航对齐 56、悬浮球避让 84）。首页入口图标 32dp 单独成令牌，那是用户自己挑过的尺寸 |
| `values/styles.xml`（新） | 9 个文字角色（`Text.Unloop.Body/Caption/Note/Meta/Action/Value/Heading/Section/Metric`）把「颜色＋字号」绑成一次定义；4 档按钮样式（`Widget.Unloop.*`）在 `style=` 顶掉主题按钮样式之后，把 48dp 下限和圆角补回来 |
| `values/finmori_dimens.xml`（删） | 四个从未被引用的令牌，留着等于假装尺度已经统一 |
| `res/layout/*.xml` | 20 个非小组件布局改成 756 处 `@dimen/` 引用、144 处角色样式、33 处按钮样式，非零的裸字号与裸间距清零（只剩 `padding="0dp"` 一处，那是「取消内边距」本身）；54 处低于下限的控件尺寸抬到 48dp，其中 6 处是 `dialog_study_course/homework` 里的输入框与下拉框——它们不是 Button，差点被按按钮的思路漏掉；另 22 处本就写着 `48dp` 的改引用同一令牌，数值没动 |
| `ui/SettingsCheckActivity` | 程序化创建的入口行 44dp → 48dp：全应用只有这一处在 Java 里写死了低于下限的可点击高度 |
| `ui/UiScaleTest`（新，9 例） | 拦住下一次回潮：布局不许出现裸字号/裸间距、可点击控件必须 ≥48dp（样式自带下限的除外，判定里把 `EditText`/`Spinner`/`AutoCompleteTextView` 也算作手指目标）、`@dimen/` 引用必须存在、字号阶梯必须单调且不低于 11sp、令牌不许没人用、按钮样式必须自带 48dp |

已知取舍：① **小组件布局不参与收敛**——`widget_*.xml` 的可用高度是桌面格子给的，跟着主尺度放大就会把行挤掉，所以它们保留字面值，测试也按文件名跳过；② 就近合并确实动了显示值，一次性对账脚本（未入库）按元素解析改动前后的实际数值：文字只有 12 处变化（10sp→11sp 共 11 处、19sp→18sp 1 处），间距与圆角约 84 处按阶梯就近、最大位移 4dp，其余逐值相同；③ `Widget.Unloop.Button` 把圆角定成 16dp，改前布局显式写 `style="@style/Widget.Material3.Button"` 会顶掉主题的按钮样式，圆角其实一直在 Material 默认的 20dp 上漂着；④ 只收敛布局层，Java 里 `dp()` 的字面值仍然成堆——悬浮台、课表网格那些尺寸是按内容与手势算出来的，搬进令牌只会把算法常数藏起来；⑤ 角色样式只绑颜色与字号，`android:textStyle` 与 `layout_*` 留在布局里，否则「加粗的次要说明」与「不粗的」会被拆成两套样式；⑥ 没把 `layout_width`/`layout_height` 写进样式（小组件与部分 ROM 上对此的可靠性和预期不一致），按钮样式只补最小尺寸与圆角；⑦ 测试是读源文件的静态检查，不校验渲染结果——它能保证不再出现魔法数，保证不了看着顺眼；⑧ **本轮只收了「尺度」没收「图形尺寸」**：收敛后布局里还剩 349 处 dp 字面值，其中 174 处是 `0dp`（约束/权重与清零的 inset，写令牌反而看不懂）、27 处是 1dp 分隔线（就是发丝线本身，不是阶梯上的一格）、其余 148 处是 10~56dp 的图标砖、箭头、进度条一类图形尺寸——它们跟着 drawable 走、不跟着文字阶梯走，硬塞进 `dimens.xml` 只是把 20 个不同用途的数字压成一个没人能解释的令牌。要收它们得先给每类图形定语义，那是另一轮。

验证：537 个 JVM 用例全过（62 个测试类，较崩溃记录那版的 528 例 / 61 类 +9，即新增 `UiScaleTest` 9 例；首轮有 2 例失败——元素标签解析只处理了单行属性，`ImageButton` 一个都没识别出来；把标签提取换成按 `<` 后首个标识符取，才真正开始拦触摸目标。第二轮暴露的是规则本身的盲区：`clickable()` 只认 Button 一族，课程与作业两个弹窗里 1 个 `Spinner` + 5 个 `EditText` 全在 44dp 上，测试照样绿——把输入控件并进同一判定、把它们抬到 48dp 之后才算闭环）；lint 0 errors、**263 warnings（基线 280，−17：删掉未引用资源与一批硬编码字号提示）**；`assembleDebug`、`assembleRelease`（签名包 74,177,165 B，签名证书 `CN=UNLOOP Release`）与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，属真机待复验：+1sp 与 ±4dp 的整体观感、紧凑控件抬到 48dp 后会不会顶到边界（画线工具条的撤销/取消/完成、季节两个 chip、作息节数的 −/+ 步进器、周切换的左右箭头，以及新增/编辑课程与作业弹窗里六个改成 48dp 的输入框会不会把键盘顶出屏），以及 `style=` 提供的 `minHeight` 在各 Material 版本上的实际生效情况，都必须上机逐屏看。

## 2026-11-24 0.10.0 发布记录

v0.9.9 之后并入 main 的功能与整改 PR 共 15 个（#71 备份/恢复、#72 常用点位收藏、#73 坐标粘贴识别、#74 预览与生效分离、#75 悬浮台折叠小球、#76 快捷入口与磁贴、#77 有序清单、#78 跑圈历史、#79 课表小组件、#80 ICS 与分享码、#81 调课与节假日、#82 多学期、#83 提醒中心、#84 本地崩溃记录、#85 尺度令牌收敛；#70 是 0.9.9 自己的发布记录文档，不算功能）由 PR #86 `c99a5d3` 升版到 0.10.0 / versionCode 40，合并提交 `a74e9f6`，附注标签 `v0.10.0` 指向它（0.9.x 用的是轻量标签，这次改为附注标签带一句版本说明；`UpdateChecker` 读的是 `releases/latest` 的 `tag_name`，与标签类型无关，不受影响）。Release 地址 <https://github.com/Sek1ro-oka/AGRI-RUN/releases/tag/v0.10.0>。

| 项 | 结果 |
| --- | --- |
| 附件命名 | `route-workshop-0.10.0-release.apk`，符合 `UpdateChecker` 的识别约定 |
| 本机构建 | 74,396,188 字节，sha256 `cd6d7bc3e850a64b6b18db671120cf5be827cbc45e01ad63e0927cac3984d8e5`，`aapt2 dump badging` 读到 `versionCode='40' versionName='0.10.0'` |
| 签名 | `apksigner verify --print-certs` → `CN=UNLOOP Release, O=Local Dev, C=CN`，SHA-256 `01547534…82f4`，与 0.9.5–0.9.9 同一主线，老设备可覆盖安装；0.9.4 及更早仍需先卸载（见 2026-11-23 签名定案） |
| 线上复核 | 发布后 `releases/tags/v0.10.0` 返回 `draft=false`、附件 `state=uploaded`、size 74,396,188；再回下载该附件，字节数与 sha256 与本机产物逐字节一致（不是只比大小） |
| 发布前测试 | `:app:testDebugUnitTest` 537 例 / 62 类 0 失败；`lintRelease` 0 errors、263 warnings；`:app:compileDebugAndroidTestJavaWithJavac` 通过；构建产物即 tag 指向的 `a74e9f6` 状态 |

**发布过程的传输事故（值得记一条）**：`github.com` 的 git 传输今天长时间拒绝 TLS 握手（schannel 报 `failed to receive handshake` / `server closed abruptly (missing close_notify)`，换 `-c http.sslBackend=openssl` 报 `unexpected eof while reading`），而 `api.github.com` 始终正常——所以能读 PR、能建 Release，却推不动分支。靠有界重试循环恢复（#85 那次是第 14 次尝试成功的，0.10.0 这次第一次就成功）。坑在于**一次重试返回过 `rc=1 Everything up-to-date`，看着像成功，实际远端 `feat/ui-scale` 并不存在**：判断是否真推上去，只能以 `git ls-remote` 或 refs API 为准，不能信 push 的一句话。

仍待真机复验：备份/恢复后各页面读数是否一致、提醒中心与桌面小组件在各厂商 ROM 后台限制下能否真响、崩溃记录能否读到、课表调课与多学期切换的边界、尺度收敛后的整体观感（+1sp、±4dp）与紧凑控件抬到 48dp 后会不会顶到边界（画线工具条、季节 chip、作息 −/+ 步进器、周切换箭头、弹窗输入框与软键盘）。

遗留风险一条：**新签名 key（`CN=UNLOOP Release`，2026-11-22 起用）至今没有异地备份**。0.9.4 及更早已经因为旧 key 不可追认而定了「只能卸载重装」的结论；这把 key 再丢一次，就没有「覆盖安装」这条路可走了。

## 2026-11-24 课表连堂占位与作业统一提前量（用户反馈修复，已实现待验证）

需求来源：用户反馈两条——「课表有些课长度显示错误，课时三节课，课表只占了一节课」（确认这门课来自教务导入），以及「作业提醒应该是统一提醒，就和上课时间一样，自定义提前多少分钟提醒」。

**课表那条的定位**：视图没有错。`ui/TimetableGridView` 的方块上下边一直按 `startPeriod`/`endPeriod` 算（`headerHeight + (start-1)*rowHeight` 到 `headerHeight + end*rowHeight`），给它一条 3–5 节的记录它就画三行高。错在上游两处：① `study/JwTimetableParser` 的旧节次正则只认 `^(\d{1,2})([-~至到](\d{1,2}))?$`，外加一条「3–4 位数字就整串对半切」的紧凑规则，于是三节连堂常见的写法「030405」「1-2-3」「01-02-03」全都落到 `continue`——**整条记录被静默丢掉**；② 同一门课在 `kbList` 里被拆成多行（换教室、单双周、教务自己把连堂写成 `03,04,05`）时，旧逻辑每行各存一条、彼此不去重也不合并，界面上就是几块只占一节的短条。两条叠起来正好是用户看到的样子。

改动：

| 位置 | 内容 |
| --- | --- |
| `study/JwTimetableParser`（节次解析重写） | 改成「按逗号/顿号/分号分段 → 逐段解析 → 压成连续区间」：区间「3-5」「3~5」「3至5」「第3-5节」、逐个罗列「3-4-5」「01-02-03」、两位一节紧凑「0102」「030405」、一位一节紧凑「345」全部收下。**两位数字只当节次号，绝不拆成两节**——「16-17」是越界的第 16、17 节，不是 1、6、1、7 节，判失败才能提示用户核对 |
| `study/JwTimetableParser`（同名课程合并） | 同一门课（同名＋同教师＋同地点＋同星期＋同周次）的所有节次先进一个 `TreeSet`，再压连续区间：「3-4」＋「5」合成一块 3–5，「1-2」＋「7-8」仍是两块，重复行自然去掉；旧的逐行去重键因此删掉 |
| `study/JwTimetableParser.Result`（新 `unparsedCourses()`） | 把「有节次文本却读不出来」的课程名报出来。教务系统换一种写法就悄悄少几节课，用户拿到的是一张看起来完整的课表——这种情况必须说话 |
| `study/TimetableText.parsePeriods` | 手填入口同样接受「3-4-5」链式写法；三个以上数字必须连续递增，「1-3-5」这种断开的写法一条记录表达不了，返回 null 让界面报输入错，而不是悄悄占成 1–5 |
| `ui/StudyDialogs.showImportPreview`、`ui/JwImportActivity` | 导入预览多一行「N 门课的节次没能识别，未导入：…（等）」；一条都没解析出来时状态栏列出课程名，不再只说「格式无法识别」 |
| `study/CustomHomework` | 去掉每条作业自己的提前量（字段、JSON 的 `lead`、`withLeadMinutes`、`leadLabel`），改成 `remindEpochMillis(int leadMinutes)` 由调用方传全局值；旧存档里残留的 `lead` 读时直接忽略，不报错也不迁移 |
| `study/HomeworkReminderPlanner` | 提前量常量（1–10080，默认 60 分钟）与 `clampLead`/`leadLabel` 搬进这里，形状与 `ClassReminderPlanner` 对齐；`upcoming`/`next`/`dueBetween` 各多一个 `leadMinutes` 参数 |
| `study/StudyTimetableStore` | 新增 `homework_reminder_lead_minutes` 读写（存在课表同一份偏好文件里，与课前提醒并排）。默认 60 分钟＝旧版每条作业的默认值，升级后提醒时刻不会凭空变化 |
| `ui/StudySettingsActivity` | 作业卡片加「提前提醒时间」一行，与课前提醒同一交互；`pickReminderLead`/`pickHomeworkReminderLead` 共用一个 `promptLead(标题, 报错, 当前值, 下限, 上限, 保存回调)`，保存后立刻重排闹钟 |
| `res/layout/dialog_study_homework.xml` | 删掉每条作业的提前量输入框，改留一行说明「提前量在学习设置里统一设置」，并按全局值算出「提前 1 小时，将在 12-01 22:59 提醒你」的预览 |
| `ui/StudyActivity`、`ui/AppSettingsActivity`、`HomeworkReminderScheduler`、`HomeworkReminderReceiver` | 一律按全局提前量算提醒点；作业列表的小标签显示全局值 |
| `backup/BackupCatalog` | 新键登记在「作业」分节下（键本身存在 `study_timetable` 文件里，但跟着作业分节走才符合恢复时的直觉） |

已知取舍：① **已经导入的课表不会自动修**：存下来的记录分辨不出「本来就只有一节」还是「被截断过」，写迁移会把本来正确的课改成错的。要么重新导入（选覆盖），要么点开那门课把节次改成「3-5」；② 每条作业的自定义提前量按用户决定取消，只留设置页一处——以前按个别作业设过 10 分钟、2 天的，升级后统一按全局 60 分钟，可能变早也可能变晚；③ 全局上限保留 7 天（再早就该用日历而不是提醒），也不做「前一天/当天」这种快捷档，与课前提醒保持同一个数字输入；④ 合并只认「同名＋同教师＋同地点＋同星期＋同周次」，同门课真的分两段（例如 3-4 与 6-7）不会被并成 3-7；⑤ 提醒中心仍只显示下一个提醒点，不把提前量塞进摘要行。

验证：543 个 JVM 用例全过（62 个测试类，较 0.10.0 基线的 537 例 +6：`JwTimetableParserTest` 10→13、`HomeworkReminderPlannerTest` 10→13、`CustomHomeworkCodecTest` 7→7 但换掉两条钉旧常量的断言、`TimetableTextTest` 用例数不变只加断言；首轮 3 例失败——「16-17」被按一位一节拆成第 1、6、7 节（于是给两位数字定死只当节次号），`dueBetween` 与 `leadIsClampedBeforeUse` 两条是我对窗口和 30 天窗口的算术想当然，按实际语义改正期望）；lint 0 errors、263 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。本机无 adb 也无模拟器，属真机待复验：教务系统实际返回的节次写法是否已被这几种形态覆盖、重新导入后三节连堂是否真占满三行、同名课程被拆成多行时的合并会不会把本该分开的两块并成一块（同名同师同室同周次却确实两段独立课）、节次提示「N 门课没能识别」在真实数据里会不会误报、全局提前量改大之后已过提醒点的作业不会补提醒、以及这条闹钟在各厂商 ROM 后台限制下能否真响。

## 2026-11-24 教务导入自动定位本周（新功能，已实现待验证）

需求来源：用户提出「另外我希望导入课表可以自动根据课表上的日期来定位本周第几周」。此前 `semester_start` 只能在「学习 · 设置 → 开学日期」手填，没填时课表页顶部常驻一句「设置开学第 1 周日期后，可一键定位本周」——导入完还要再跑一趟设置页点日历，等于把教务系统本来就知道的事让用户重做一遍。

改动：

| 位置 | 内容 |
| --- | --- |
| `study/JwTimetableJavascript.FETCH_JS` | 抓课时顺带读学期锚点：先取当前文档的 `#xq` 下拉框（选中值＝本周、选项数＝总周数）与页面全局 `xnWeeks`/`dates`，缺什么再去解析课表首页 HTML 里的 `var xnWeeks = '9'`、`dates[0] = new Date(2026, 8, 28)`、`dates[0] = '2026-09-28'` 三种写法（正方 `new Date` 的月份是 0 基，脚本里加回 1）。首页 HTML 与学期探测共用一次请求（`indexHtml` 缓存）。**日历分支全程尽力而为**：`readCalendar` 自己吞掉异常，取不到就交空串／空数组，绝不让课表导入因为它失败 |
| `study/JwSemesterInfo`（新） | 纯 Java 推算。键名按候选列表读（`xnWeeks`/`xq`/`zxs`/`dqz`/`xzz`、`dates`/`xnDates`/`xqDates`、脚本归一的 `weekCount`），周次容忍「第9周」这类带字写法、拒绝 100 这种显然不是周次的数；日期接受 `2026-09-28`、`2026/9/28`、`20260928`，落在 2015–2035 之外（教务占位的 `0000-00-00`、`1970-01-01`）一律不当日期。`plan(today, currentTotalWeeks)` 三条结论：① 教务周次与校历日期算出的周一致 → 用校历里的真实开学日（可能落在周中）；② 对不上或只给了周次 → 以今天所在周的周一倒推，第 1 周起点＝`mondayOf(today) - (N-1)` 周，保证「本周第 N 周」与网格上的日期一定自洽；③ 只给校历日期 → 按日期算本周，今天不在窗口内就只记日期、不动当前查看的周。总周数只增不减 |
| 同上：`termCoversToday` | 只有「按今天倒推」这条路要把关：`xnm`＋`xqm` 能判出这份课表属于哪个学期，导入往学期时整条推算作废，免得把用户手调好的学期挪走。校历日期自带学期，不受这条约束；缺 `xnm` 或 `xqm` 判断不了时放行，正常导入当前学期不该被拦住 |
| `study/StudyTimetableStore.applySemesterAnchor`（新） | 开学日期、总周数、当前周**一次 editor 提交**。分三次写会让监听偏好文件的小组件连刷三帧，中间那帧是「新周次配旧开学日期」的错位课表；传 0／null 的项保持本机原值，周次按生效的总周数收窄 |
| `ui/StudyDialogs.showImportPreview` | 预览多一行推算结果，校历口径／倒推口径／今天不在学期内三种说法分开写；推算出的开学日与手设值不同时追加「手设的开学日期 X 已被替换，可在学习·设置里改回」，不静覆盖。原先「按课程最大周扩总周数」那段并入同一次锚点写入，行为不变 |
| `ui/JwImportActivity` | 把 `JwSemesterInfo.read(root)` 一并交给预览对话框 |
| `ui/StudyActivity` | 教务导入返回后先 `initializeWeek()` 再渲染：以前只重画课表，用户进导入页之前手动翻到的那一周会盖住刚定位好的本周 |
| `res/values/strings.xml` | 新增 4 条文案 |

已知取舍：① **字段名没有真机证据**：`xnWeeks`／`dates`／`#xq` 是正方 kbcx 页面的常见形态而不是各校统一的接口约定，脚本按候选键与页面变量逐个试；全部落空时功能整体退化为原状（不写锚点、不报错、预览不多这一行），用户仍须手填开学日期；② 已经导入过的课表不会补定位——锚点只在导入确认那一刻推算，历史数据没有周次来源，不猜；③ 往学期课表只在「按周次倒推」这条路上拦（校历日期明确给出就照实记），判断依据是 `xnm`/`xqm` 推知的学期窗口，写法另立短学期的学校会误放行；④ 总周数按校历条目数／周次下拉数算，仍收进 1–30，与手动设置同一约束；⑤ 归档学期（`SemesterArchive`）的起始日期由归档快照决定，导入推算只影响当前偏好文件；⑥ 不做「按课程日期反推开学日」——正方课表里根本没有具体上课日期，只有周次。

验证：561 个 JVM 用例全过（63 个测试类，较上一节基线 543 例 / 62 类 +18，全在新增的 `JwSemesterInfoTest`：候选键与带字周次、四种日期写法与占位值、倒推锚点 2026-11-23 → 2026-09-28、校历一致／不一致时取谁、只给校历且今天不在学期内、总周数只增不减、学年学期把关）；抓取脚本另用 Node 搭 DOM／fetch 桩跑通六种形态——只有首页脚本变量（`xnWeeks=9`、`weekCount=3`、`dates=[2026-09-28,2026-10-05]`）、页面就在课表页（`#xq` 选中值 11 优先于 `window.xnWeeks`，`window.dates` 里混着 `Date` 对象与坏值仍只收到两条合法日期）、校历日期写成字符串且 kbList 嵌在 `data` 下、日历请求失败但学期已知（仍 `ok:true` 且三个周次字段为空）、日历请求失败且学期未知（照旧报 `NETWORK_ERROR` 而不是静默导入半份）、首页没有 `#xq` 下拉（只拿到校历日期）。lint 0 errors、263 warnings（与基线持平）；`assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。桩里的 HTML 是我按公开形态写的，不是学校真实页面；本机无 adb 也无模拟器，属真机待复验：教务页面到底把周次放在哪、导入后课表页是否直接落在本周、预览里新增那行在窄屏会不会把对话框撑出滚动、`applySemesterAnchor` 一次提交后小组件与课前提醒是否按新周次重排。

## 2026-11-24 0.10.1 发布记录

v0.10.0 之后并入 main 的两个 PR——#88 `a21168b`（课表连堂占位修复 + 作业统一提前量）与 #89 `d4288ee`（教务导入自动定位本周）——由 PR #90 的 `def327f` 升版到 0.10.1 / versionCode 41（只动 `app/build.gradle` 两行），合并提交 `d02de50`，附注标签 `v0.10.1`（标签对象 `61a3caaf`，解引用指向 `d02de50`）。Release 地址 <https://github.com/Sek1ro-oka/AGRI-RUN/releases/tag/v0.10.1>，`releases/latest` 已返回 `v0.10.1`。

| 项 | 结果 |
| --- | --- |
| 附件命名 | `route-workshop-0.10.1-release.apk`，符合 `UpdateChecker` 的识别前缀，0.10.0 设备能认出主包 |
| 本机构建 | 74,397,540 字节，sha256 `5c271cd82d5ba114d13d9b07de80b6c64d67e57beefe4d3f8cfec7fa9882c72d`；`aapt2 dump badging` 读到 `versionCode='41' versionName='0.10.1'` |
| 签名 | `apksigner verify --print-certs` → v2 方案通过，`CN=UNLOOP Release, O=Local Dev, C=CN`，证书 SHA-256 `01547534…82f4`——与 0.9.5–0.10.0 同一把 key，**0.10.0 可直接覆盖安装，不需要卸载** |
| 发布前测试 | `:app:testDebugUnitTest` 561 例 / 63 类 0 失败（数字从 `app/build/test-results/testDebugUnitTest/*.xml` 现汇总，不抄旧文档）；`lintRelease` 0 errors、263 warnings（与 0.10.0 持平）；`assembleRelease` 成功，产物即 tag 指向的 `d02de50` 状态 |
| 线上复核 | 发布后 `releases/tags/v0.10.1` 返回 `draft=false`、附件 `state=uploaded`、`size=74397540` 与本机一致；`git ls-remote`／refs API 确认远端分支 `def327f`、标签 `61a3caaf`。**逐字节复核已完成**（2026-11-25 补充）：线上附件 74,397,540 字节全部下载回来，整包 sha256 `5c271cd82d5ba114d13d9b07de80b6c64d67e57beefe4d3f8cfec7fa9882c72d` 与本机产物一致，`apksigner verify` 对下载件通过 |

**这条证据是怎么补上的（过程值得记，坑不在发布端而在复核端）**：发布当晚本机下行 `github.com` 极不稳定——整包下载 180 秒只到 3,681,088 字节，请求前 64 KB／前 1 MB 分段三次全部拿不到字节，断点续传爬到 32,013,236 字节后连续 13 次重试不进一个字节。改成「按 1 MB 分块请求、每块先与本机产物同偏移算 sha256 比对、通过才落盘」之后一次跑通：34 块全部一次命中，0 块内容不符，整包哈希与本机产物一致。中途还出现过一种必须警惕的假成功——有一轮拼出来的文件大小恰好等于 74,397,540，但 `aapt2` 报 `failed opening zip: Invalid offset`、`apksigner` 报 `Failed to read AndroidManifest.xml`，分块哈希从第 36 块开始在两个值之间来回重复，说明链路（或中间缓存）返回过**长度正确、内容错误**的响应；这正是应用内更新坚持「先辨魔数、再比摘要，不只比大小」的原因：`UpdateManager.looksLikeApk` 只认文件头的 ZIP 魔数 `PK`，用来区分「线路给了网页」与「安装包真坏了」，`UpdateVerifier.verify` 在 Release 提供 digest 时核对整包 SHA-256、拿不到摘要时至少核对包名与签名。另记一条实现细节：这条链路对 Range 请求可能多给几个字节（请求 1,048,576 实收 1,048,580，多出的正是下一块开头），判定要写成「收到的字节数不少于请求长度，截断后再比内容」，写成「长度不等就失败」会把好数据丢掉。`api.github.com` 的 `releases/assets/<id>` + `Accept: application/octet-stream` 与 `github.com` 直连两条通道内容一致，可互为备份。推送分支与打标签本身各一次成功；复核用的下载与 git 写操作不要并行——74 MB 下行会把同一条链路的上行挤到全部握手失败。

**真机回报（2026-11-25）**：三项已由用户在真机验证后回报「没问题」，本节据此闭环——#88 的连堂课在网格里占了应有的格数、作业统一提前量到点提醒正常、#89 教务导入后课表页直接落在本周。证据是口头回报，仓库里没有对应的日志或截图附件，也没有复现步骤记录。

仍待真机复验（本机无 adb、无模拟器）：`unparsedCourses()` 报出的课名是否与实际丢课一致、预览新增那行在窄屏会不会撑出滚动、`applySemesterAnchor` 一次提交后小组件与课前提醒是否按新周次重排。0.10.0 的整批复验项（备份恢复、提醒中心与各厂商 ROM 后台限制、崩溃记录可读性、ICS 与分享码、调课与多学期边界、悬浮台折叠小球、Android 12+ 的 Toast 压制、+1sp/±4dp 观感与 48dp 控件顶边界）一并继承下来，见 2026-11-24 0.10.0 发布记录一节。

遗留风险更新一条：**`CN=UNLOOP Release` 这把 key 的库外备份已于 2026-11-25 补做**（此前状态是「至今没有异地备份」，旧 key 不可追认的经过见 2026-11-23「旧签名无法追认」一节）。0.9.4 及更早因为签名换过 key 已经定了「只能卸载重装」，这把再丢一次就没有覆盖安装这条路。备份目录 `D:\UNLOOP\backup\release-signing-2026-11-25\`，内含 `unloop-release.jks`（2,648 字节，sha256 `d92c8f5a…6a3a`）、`keystore.properties`（122 字节，sha256 `dd478d1f…a821`）、`SHA256SUMS.txt` 与记录恢复步骤的 `README.md`；副本哈希与仓库原件逐一相符，且**从副本**（不是从原件）`keytool -list` 读出的证书指纹 `01547534…82f4` 就是上面线上包用的那把 key。仓库这边顺带确认 `keystore.properties`（`.gitignore:29`）与 `unloop-release.jks`（`*.jks`，`.gitignore:26`）从未进过任何提交——`git log --all -- keystore.properties unloop-release.jks` 为空，`app/build.gradle` 里只有 `keystoreProperties['storePassword']` 这类引用，没有明文口令。**仍未做的一件事：这份备份和仓库在同一块物理盘上，挡不住整盘损坏，需要再复制一份到机外介质（U 盘／加密网盘／另一台机器），复制后在新位置重跑一次 `sha256sum -c SHA256SUMS.txt`。**

## 悬浮窗工具条重做（2026-11-25）

| 编号 | 标题 | 处理 | 状态 |
| --- | --- | --- | --- |
| [#31](https://github.com/Sek1ro-oka/AGRI-RUN/issues/31) | 悬浮窗明确展示控制与常用地点传送 | 常驻工具条展示状态、暂停/继续、结束及更多；结束需确认，展开后提供摇杆、速度档位和隐藏入口。模拟器检查了暂停、继续、取消和确认结束、隐藏后继续运行及拖动。常用地点传送留待后续处理。 | 未关闭 |

## #93/#94 复核发现的行为缺陷（2026-11-25，fix/overlay-readiness-behavior）

0.10.1 发布后合入的 #93（跑前准备面板）与 #94（悬浮工具条重做）经逐行复核发现四条行为缺陷，本支一并处理。复核基线：合入后的 main `674ad9e`。

| 项 | 根因 | 处理 | 状态 |
| --- | --- | --- | --- |
| 跑前准备面板强制浅色，深色模式白底深字 | 面板用 `android.R.style.Theme_Material_Light_NoActionBar`，另有 8 处 `Color.WHITE` 与 5 个 `0xff…` 写死色；而设置页确实支持跟随系统／深色（`AppearancePreferences.java:83`） | 新增 `Theme.Unloop.FullScreenDialog`（父样式 `Theme.Material3.DayNight.NoActionBar`）；面板底色／卡片／可选区／徽标／按钮描边收进 `readiness_*` 令牌并补 `values-night` 变体；状态栏与导航栏不再写死白色，按 `R.bool.fm_light_bars` 决定明暗图标；开始按钮文字改用 `fm_on_brand`（夜里 `fm_link` 翻成浅薄荷，白字会糊） | 已实现待验证 |
| 通知权限不再自动申请 | #93 删掉了起跑前的 `neededPermissions()/requestPermissionsThenStart()`，只剩用户点面板「通知」那一行才申请；不点就没有跑圈前台通知 | `beginReadiness()` 在 Android 13+ 且未授予、本 Activity 实例未问过的前提下先拉起系统授权框，结果回来后走 `continueReadiness()` 继续原判就绪流程；每实例只问一次；顺带把 `onSaveInstanceState` 的保存条件从「对话框已显示」放宽为「计划存在」，避免授权框停留期间重建丢计划 | 已实现待验证 |
| 悬浮窗可能留下摘不掉的孤儿窗口 | `sync()` 的 try 把 `addView` 成功之后的 `scaleHost.post(...)` 也包住，catch 里无条件 `root=null; scaleHost=null`；此后 `hide()` 因 `scaleHost == null` 直接 return，窗口却还挂在屏幕上 | catch 内先按「已挂上」判断，能摘就 `removeViewImmediate`，再清引用；`running` 只在 `addView` 成功后置位 | 已实现待验证（异常路径无法人工触发，仅代码级收口） |
| 结束跑圈的瞬间摇杆能把运行复活成单点任务 | `moveStep()` 每 500 ms 读的是面板缓存 `snapshot`，而服务端的 `SimulationService.drive()` 见到非活跃快照会直接 `start(createSinglePoint(anchor))`；LiveData 回调与驱动节拍排在同一条主线程，结束事件落到面板之前有一次驱动机会 | `moveStep()` 改为每次直读 `SimulationStateRepo.get().current()`，非活跃或已暂停立即 `stopMoving()`（内部会 `releaseDrive` 让速度归零） | 已实现待验证 |

验证：`:app:testDebugUnitTest` 561 例、`:route-core:test` 107 例、`:simulation-core:test` 55 例（合计 723，数字从各模块 `build/test-results/**/TEST-*.xml` 现汇总）全部通过、0 失败；`:app:lintDebug` 0 errors、283 warnings——与合入后的 main 逐条一致（按检查项 id 分布 diff 过）；`:app:assembleDebug` 通过。补一句过程：通知权限那版初稿把 `Manifest.permission.POST_NOTIFICATIONS` 直接用在 `beginReadiness()` 里，lint 立刻多出一条 `InlinedApi`（minSdk 29），改成「版本判断与申请写在同一个 `if` 里」后才回到 283，这类常量的用法以后要连着版本判断一起写。

本机无法验证的部分（无 adb、无模拟器，需要真机或模拟器复验）：① 深色模式下跑前准备面板的实际观感——状态栏图标是否可见、卡片描边对比度、开始按钮在夜间的可读性；② 「系统授权框 → 跑前准备面板」这个先后次序是否比原来顺手（现在是先弹系统框，回来后才可能弹面板）；③ 摇杆在结束那一拍是否真的不再复活单点会话；④ 悬浮窗 `addView` 异常收口只能靠代码审查，构造不出真实异常。另外遗留一条不在本支范围：#93/#94 新增的写死中文文案与程序化建视图带来的 lint 增长（0.10.1 基线 263 → 合入后 283，本支没有把它压回去）尚未收进 `strings.xml`，留给下一次界面收敛。

## 2026-11-25 主页设置入口移进底部导航栏（界面整改，模拟器已验证、真机未复验）

需求来源：用户给出当前主页截图（右上角齿轮 + 底部「首页／校园快跑／学习」胶囊导航），要求「使用最新基线，把右上角设置按钮移动到导航栏内右下角」，并拍了三条：真·移动（不留双入口）、新项与其余三项同款、交付为新分支 + PR 先不合并。

基线核对：本地停在 `feat/virtual-location-page` @ `cf76320`，它是 `upstream/main` 的祖先，所以从最新基线开分支不丢今天的虚拟定位改动。GitHub 上 main 实际已到 `5a2bcea`，而本地 remote-tracking 还停在 `7d93011`——**不先 `git fetch upstream` 就会拿旧基线开工**。`7d93011..5a2bcea` 那 11 个提交只碰 `OverlayPanel`、`CampusRunActivity`、`colors.xml`（含 night）、`themes.xml`、文档与截图，没动主页布局、`MainActivity`、`menu_main.xml` 和那条界面用例，本支行号因此在新基线上仍然成立。

| 位置 | 内容 |
| --- | --- |
| `res/layout/activity_main.xml:14` | 删掉 `MaterialToolbar` 的 `app:menu="@menu/menu_main"`。工具栏留着——它还是 `UNLOOP` 标题的来源；56dp 高度与纵向结构一点没动 |
| `res/layout/activity_main.xml:436-458` | 导航栏加第 4 列 `navSettings`，结构逐行照抄 `navStudy`：`0dp + layout_weight=1`、`clickable/focusable`、22dp 图标 `ic_campus_settings` 配 `app:tint="@color/fm_nav_idle"`、11sp 文案 `@string/home_app_settings_title`（值就是「设置」） |
| `res/menu/menu_main.xml` | 整份删除，全仓只有主页那行 `app:menu` 引用它。`ic_campus_settings` 仍被跑圈页 `buttonAdvanced` 与学习页 `buttonStudySettings` 使用，不会变成未使用图标；`home_app_settings_title` 由新导航项接手——不新增字符串，也不留下未使用资源 |
| `ui/MainActivity.java:48-49`、`59` | 导航栏绑定处加 `navSettings → startActivity(AppSettingsActivity)`，按压动画处加 `FinmoriMotion.press(binding.navSettings)` |
| `ui/MainActivity.java:58-63` | 删掉 `toolbar.setOnMenuItemClickListener` 整段，这是 `R.id.action_settings` 在源码里唯一的引用；`R` 本身还被 93-107 行的文案用到，import 不动 |
| `androidTest/…/CampusWorkspaceTest.java:153` | 点击目标从 `R.id.action_settings` 换成 `R.id.navSettings`，其余断言一字不改（`rowUpdate` → `cardUpdate` 显示、`pressBack` 后 `rowActivation` 仍在），证明的还是同一条链 |

已知取舍：① 走直接 `Intent` 而不是 `HomeTarget`——`entry/HomeTarget` 那套枚举只为「清单里只有主页 exported，桌面长按快捷方式与快捷设置磁贴必须先落主页再转发」而存在，`res/xml/shortcuts.xml` 里没有设置类快捷方式，为导航栏单独加一个 SETTINGS 属于扩范围；② 新项用灰色待选态而不是绿色强调态——严格说它是动作而不是第四个目的地，但用户拍板「与其余三项同款」，外观一致性优先；`ic_campus_settings` 里写死的 `@color/campus_mint_dark` 会被 `app:tint` 覆盖，同栏的 `ic_study_homework` 正是同一色值、同一覆盖方式，深色下自动走 `values-night` 的 `fm_nav_idle`（#94A1B4）；③ 主页之外的页面本来就没有这条导航栏（`bg_fm_nav` 只有主页和学习页那两个页内标签在用），所以设置入口跟着导航栏一起只在主页出现，与「齿轮原来也只在主页」等价，没有拿走任何原有路径；④ 跑圈页与虚拟定位页顶部那两颗齿轮打开的是 `SettingsCheckActivity`／`StudySettingsActivity`，与中文「设置」撞名但不是同一个页面，按名字不动。

验证（分口径）：

- **源码**：全仓 `grep` 已无 `action_settings`／`menu_main` 引用。
- **自动化（本机）**：`:app:assembleDebug`、`:app:lintDebug` + `:location-probe:lintDebug`、`:app:assembleDebugAndroidTest` 全部 BUILD SUCCESSFUL。lint 0 errors、284 warnings，新增的那条落在 `activity_main.xml:436`，是 `UseCompoundDrawables` 在第四列上又报一次——同一条检查原本已在 373/392/414 三列各报一次，本支没有引入新类型的告警（上一节记录的基线为 283，那个数是在另一台机器上量的，本支没有重新复测）。JVM 用例 `:route-core:test` 107 + `:simulation-core:test` 55 + `:app:testDebugUnitTest` 561 = 723，0 失败（数字从各模块 `build/test-results/**/TEST-*.xml` 现汇总）。
- **模拟器**（AVD `agri_ui_review`，API 35 x86_64，720×1280 @ 320dpi ⇒ 宽 360dp，正是最窄的常见档位）：单跑相关用例 `advancedSettingsAndUpdaterAreReachableWithoutChangingTask` → `OK (1 test)`，即「点导航栏设置进设置页 → `rowUpdate` 打开更新页 → 返回后 `rowActivation` 仍在」这条链在设备上成立。再用 `uiautomator` 读到 `navSettings` 实际边界 `[508,1044][656,1164]` = **74×60dp**，满足 `docs/FINMORI_UI.md` 的 48dp 下限；`input tap` 中心点之后 `dumpsys activity` 的 `topResumedActivity` 是 `AppSettingsActivity`——真实手势路径也成立。浅色与深色各截图一次：工具栏只剩标题、齿轮已不在；四列「首页／校园快跑／学习／设置」标签都不截断，设置图标呈灰色待选态。
- **同机对照（这条决定了上面那些失败算不算本支引入）**：整类 7 条用例在改动后有 3 条失败。我在 `E:/codes/agri-run-baseline-check` 另开一个 detached worktree 检出**未改动**的 `5a2bcea`，装到同一台模拟器跑同一份用例集，得到**同名同数的 3 条失败**（`runningControlsHomeShortcutAndExternalCommandsStayInSync`、`homeTimetableShortcutOpensWeekView`、`overlayPresetRemainsTheNaturalSpeedTarget`），所以这三条是本模拟器上的既有问题，不是本支引入。已看到原因的两条：服务状态等不到 `RUNNING` 超时、`cardTimetable` 在这块屏幕比例下可见度不足 90% 被 Espresso 拒绝点击；第三条的报错文本被只保留末尾 40 行的输出截断了，没有逐条深挖——既有失败不在本支范围。对照 worktree 已删除，模拟器已关闭，深浅模式已还原。
- **真机**：未做。

这台机器与上一节的记录不同：`AGENTS.md`／`docs/BUILD.md` 写的 `D:\Java\jdk-21` 与 `E:\Android\Sdk` 在此都不存在，实际用 `JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`（JDK 17，AGP 8.9.2 够用）与 `sdk.dir=C:\Users\Xiaoyuan\AppData\Local\Android\Sdk`（platform 35 + build-tools 35.0.0）；工程路径 `E:\codes\AGRI-RUN` 是纯 ASCII，第 2 节的中文路径坑不适用。上一节「本机无 adb、无模拟器」那句对这台机器不成立，本支正是靠这里的模拟器才把导航点击跑成设备级证据。

不在本支范围：`docs/11_使用手册.md:104` 与 `delivery/README_安装与使用.md:104` 写的「打开 App → 右上角菜单 → **设置检查**」本来就和当前代码不符（齿轮开的是 `AppSettingsActivity`，设置检查页在主页是从引导卡进的）。这是既有的文档漂移，本支只在 PR 里点出来，不顺手改写；改完之后那两句里的「右上角菜单」更是彻底没有对应物了，等下一次界面文案收敛一起处理。

## 2026-11-27 更新进度、取消重试与启动提醒（Issue #112，已实现待真机验证）

需求来源：Issue #112「更新页显示更新下载进度，优化整体更新流程，每次有新版本更新时，弹窗提醒用户一次，不要重复提醒，尽量复用或改进现有组件」。此前更新页只有一句「正在下载」，进度只在系统通知里；应用也完全没有自动检查，提醒中心明写「只在手动点的时候提示」。

与用户对齐的四条：① 冷启动静默检查，不阻塞进入 App，失败不打扰；② 复用更新页现有的「发现新版本」完整对话框，点「下载并安装」直接开始下载；③ 更新页做到进度条 + 百分比 + 已下/总量，并支持取消下载与失败重试；④ 同一版本只自动弹一次，点「暂不更新」后不再自动弹，手动检查不受限。

| 位置 | 内容 |
| --- | --- |
| `update/UpdatePromptPolicy`（新） | 纯 Java 的「要不要自动弹」判断：远端版本确实更新、且与已提醒版本不同才弹。`UpdatePromptPolicyTest` 覆盖同版本、旧版本、空值/空白与跨版本升级 |
| `ui/UpdateDialogs`（新） | 手动检查与启动检查共用的「发现新版本」对话框；展示即写 `prompted_version`（同一版本不再自动弹），自动模式关闭按钮写「暂不更新」并说明本版本不再自动提醒；没有 APK 时改为打开发布页 |
| `update/UpdateManager` | 新增 `DownloadState`（状态/已下/总量/百分比，排队、下载中、系统暂停都算 active）、`sizeLabel`、`cancelDownload`（移除系统任务 + 删除文件 + 清记录）、`retryDownload`（用失败记录里的原始附件地址重新探测线路）、`canRetry`、`promptedVersion`/`markPrompted`；下载开始顺手保存附件原始地址，重试不必再查一次 Release |
| `ui/MainActivity` | 首页冷启动静默检查一次（后台线程，失败只写诊断日志）；结果返回时要求页面仍 `RESUMED`、有窗口焦点、没在引导/跳快捷入口，否则不弹、留给下次冷启动；发现新版本时直接复用完整对话框，点下载后交给系统下载器，进度看系统通知或更新页 |
| `ui/UpdatesActivity` + 布局 | 卡片内新增 `LinearProgressIndicator`，每 500ms 读一次 `DownloadManager` 刷新「正在下载 0.13.0… 42% / 31.2 / 74.4 MiB」；选择线路和校验阶段显示不确定进度；下载中显示「取消下载」，失败显示「重试」（无附件地址的旧记录退回重新检查）；页面离开即停轮询，广播到完成/失败仍照旧处理 |
| `study/ReminderCenter` + 文案 | 更新项从 `MANUAL_ONLY` 改为 `STARTUP_CHECK`，提醒中心写「启动时静默检查，新版本只提醒一次」；2026-11-24 提醒中心一节里「版本更新永远只在手动点的时候提示」的口径随本支作废 |

已知取舍：① 只在首页冷启动时检查，没有 WorkManager/JobScheduler 后台轮询；从快捷方式直接进目标页的启动不查，回到首页后的那次 `onResume` 才查；② 同一版本只自动弹一次，是否装上是用户的选择，失败或已下载未安装不会再次自动弹（按用户拍板），更新页里仍有失败重试与安装入口；③ 自动检查每次冷启动都会并发请求 GitHub 与镜像的 Release 元数据（几 KB），失败静默；④ 下载进度靠 500ms 轮询，`DownloadManager` 的系统通知仍保留，页面不在前台时轮询停止；⑤ 取消下载会删除半成品与记录，不能续传；重试会重新探测最快线路，可能换线路，旧的失败任务留在系统下载列表里（这样探测再次失败时页面仍保留「重试」，不会连状态都丢）；⑥ 升级前留下的失败记录没有附件地址，按钮退回「重新检查」；⑦ `app_update`（下载记录与已提醒版本）不进备份，换机后需要重新提醒一次；⑧ 启动提醒的「下载」与更新页一致，只负责开始下载，安装仍需用户在系统安装器确认；⑨ 首页的检查走进程级后台线程，旋转不会打断刚点下的线路探测，但已弹出的对话框不会跨旋转保留。

验证（分口径）：

- **自动化（本机）**：`:app:testDebugUnitTest` 591 条、`:route-core:test` 109 条、`:simulation-core:test` 55 条，0 失败（数字从各模块 `build/test-results/**/TEST-*.xml` 现汇总；app 较基线 584 增加 7 条，即 `UpdatePromptPolicyTest` 4 + `UpdateManagerStateTest` 3，`ReminderCenterTest` 为改名不增数）；`:app:lintDebug` 0 errors / 287 warnings；`:app:assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。
- **仪器测试**：新增 `UpdateDialogTest` 两条（启动对话框展示文案、展示即写已提醒版本、点「暂不更新」不会创建下载；点「下载并安装」会执行下载回调）；在新建的 Nexus 5 / API 35 x86_64 AVD 上跑 `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.localroute.app.update.UpdateDialogTest`，`Starting 2 tests` / `Finished 2 tests`，BUILD SUCCESSFUL。此前这台机器 `emulator -list-avds` 为空，是 SDK/AVD 迁移到中文路径后 QEMU 无法打开镜像；用 ASCII 目录映射后原 AVD 也能正常冷启动。
- **真机/界面**：启动检查与对话框已在模拟器上走通，但更新页进度条与两行进度文案的视觉效果仍未截图。需要补看：首页启动后弹窗出现时机是否打扰、更新页进度条在深浅色与窄屏下的排版、取消后系统通知是否消失、失败重试是否能重新选到可用线路、下载完成后校验与安装链路是否仍正常。

## 2026-11-27 环境检查的定位权限直达（Issue #115，模拟器已验证）

需求来源：Issue #115——功能页的授权卡进入环境检查页后，点「定位权限」原本会跳去跑圈页；跑圈页只有在选路线并点开始后才打开跑前准备，落地页只是一张普通地图，授权入口和跑圈流程重叠。用户明确要求「直接提供权限设置的按钮，而非直接跳转」，并确认保留功能页授权卡与环境检查页，只把定位权限行改成直达。

| 位置 | 内容 |
| --- | --- |
| `settings/SetupItem` | `LOCATION_PERMISSION` 的入口从 `Entry.RUN` 改为 `Entry.RUNTIME_PERMISSION`，删掉「运行时授权只能在跑圈页发起」的旧约定；其余四项入口不变 |
| `ui/SettingsCheckActivity` | 行内「授权定位」直接处理：首次或还能再询问时 `requestPermissions(FINE + COARSE)`；用私有偏好 `location_asked` + `shouldShowRequestPermissionRationale` 判断永久拒绝，永久拒绝后按钮变「打开权限设置」并走已有的 `openAppDetails()`；`onRequestPermissionsResult` 与 `onResume` 都重绘检查列表，不再 `startActivity(CampusRunActivity)` |
| `res/values/strings.xml` | 删除 `guide_action_run`（去跑圈页授权），新增「授权定位」与「打开权限设置」两条文案 |
| 未改 | 功能页授权卡仍是「下一步」总览入口，点击进检查页；首次引导页和跑圈页跑前准备已有的直接授权逻辑不动，避免把三处流程一起改大 |

已知取舍：① 永久拒绝后打开的是系统「应用详情」页，不是直接定位权限子页——AOSP 没有统一的本应用单权限设置 action，用户还要点一次「权限」；② `location_asked` 存在 `SettingsCheckActivity` 的私有偏好，只用于区分「第一次请求」与「不再询问」，不参与授权判断；授权成功后会清掉，系统自动回收闲置权限后仍可重新弹框；③ 检查页仍负责总览所有环境项，功能页卡片不直接执行单项授权；④ 授权结果返回后靠 `onResume` / `onRequestPermissionsResult` 刷新，不额外发广播；⑤ 只授予「大致位置」仍算未授权（运行需要精确位置），行会继续提示授权或打开设置；⑥ 首次引导页与本页各自记录是否问过，若在引导页已永久拒绝，本页第一次点「授权定位」可能不弹框、直接变成「打开权限设置」，再点一次即可。

验证（分口径）：

- **自动化**：`:app:testDebugUnitTest` 591 条、`:route-core:test` 109 条、`:simulation-core:test` 55 条，0 失败（本支实跑，数字与上一节相同）；`:app:lintDebug` 0 errors / 287 warnings；`:app:assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。
- **模拟器**（Nexus 5 / API 35 x86_64，1080×1920 @480 ⇒ 360dp 宽）：功能页卡片显示「下一步：定位权限（精确位置）」并进入检查页；行内按钮显示「授权定位」而非「去跑圈页授权」；点击后 `topResumedActivity` 是 `com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity`，系统授权框出现，未跳跑圈页；连续两次拒绝后按钮变为「打开权限设置」，点击打开系统 Settings 的 UNLOOP 应用详情（含 Permissions 行）；用 `pm grant` 同时授予 FINE 与 COARSE 后返回，行状态变为「已授权」、动作按钮消失；随后用 `pm revoke` 撤销并重新进入，按钮回到「授权定位」（`location_asked` 已随授权清除，仍可重新弹框）。
- **截图**：授权前、永久拒绝后、系统授权框、应用详情各一张，本地复核，不入仓库。
- **真机**：未验证（本机模拟器覆盖了按钮文案、系统弹窗与三处页面跳转；真机权限页的 ROM 差异仍需复验）。

## 2026-11-27 首页 C+：固定六节的跨天课程（用户确认方案，模拟器已验证）

需求来源：首页原来是「单日时间轴 + 周条」，看一天要翻日期，课表信息密度低，跨天不可见。讨论后用户确认 C+：不再固定几天，改为固定「接下来最多 6 节课 + 2 条待交作业」；课表占主区域、作业占次区域，两者同屏；周条去掉，日期信息放进日分组标题；课多时底部提示去完整课表。

| 位置 | 内容 |
| --- | --- |
| `home/HomeUpcoming`（新） | 纯 Java 的「接下来」模型：从今天起按 `HomeAgenda`/`TimetableDay` 的同一套算法收集未结束的课，最多 N 节，按天分组；今天已结束的课不占行，保留 `finished/total` 给标题；放假日保留一个空组；记录上限之外第一天的日期与节数；没有开学日期时只看 7 天，避免按星期无限重复；`HomeUpcomingTest` 覆盖跨天上限、日中间截断、放假日、无开学日期边界、学期结束、零课程/全部结束、7 天外不重复 |
| `ui/MainActivity` | 单日时间轴、周条与折叠逻辑换成「接下来」：渲染日分组与课程行，页头「接下来 / 完整课表 →」+「第 N 周 · 现在 HH:mm」；作业固定只渲染 2 行，页头「待交作业 N / 全部 →」；屏幕真的放不下时从尾部收课程、再收作业，底部改说「还有更多课」/「还有 N 份」，表头 48dp 链接仍可进完整页面；行点击进完整课表 |
| 布局/资源 | 新增 `item_home_upcoming_day`、`item_home_upcoming_row`、`item_home_homework_row`；课程/作业行 44dp（比 48dp 触摸下限略紧，换首屏密度），表头链接保持 48dp；删除周条、单日 agenda 布局与 `home_date_format`/`home_agenda_count` 等旧文案 |
| `res/values/finmori_strings.xml` | 新增「接下来」「今天/明天/周X」日标题、「已上 N 节 · 余 N 节」「放假」「还有 N 节」「还有更多课」「待交作业 N」「全部 →」等文案 |

已知取舍：① **6+2 是目标不是硬保证**：393×851dp 模拟器上正好 6 节 + 2 条、首页不滚动；360×640dp 上自适应收成 2 节 + 2 条，两卡仍同屏，底部显示「还有更多课」，不再强行塞满或让整页滚动；② 首页不再提供按天浏览/周条，看某一天或整周空档要进完整课表；课程/表头都可点；③ 44dp 行是密度与触摸下限的折中，大字体下行高改为 wrap+min、不会裁切，但触摸目标仍小于 48dp；④ 今天已结束的课不占行，用日标题「已上 N 节」带过；放假日只在展示窗口内保留空组；⑤ 没有开学日期时只扫 7 天，避免按星期无限重复；有开学日期时扫到学期末，上限之外只提示「还有 N 节/更多课」，不计算全学期总数；⑥ 首屏收行是自适应兜底，不提供手动展开；被收掉的课通过表头「完整课表 →」进入；⑦ 作业固定 2 行，`全部 →` 进学习页/作业页。

验证（分口径）：

- **自动化**：`:app:testDebugUnitTest` 603 条、`:route-core:test` 109 条、`:simulation-core:test` 55 条，0 失败（app 较基线 591 新增 `HomeUpcomingTest` 12 条）；`:app:lintDebug` 0 errors / 287 warnings；`:app:assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。
- **模拟器（高屏）**：API 35 / 1080×2340 @440（393×851dp）上 `HomeUpcomingUiTest` 1/1 通过：6 个课程行、2 个作业行，首页内容高度不超过视口；浅色与深色各截图一次（真实页面，非合成）。`CampusWorkspaceTest#bottomNavReachesAllFourDestinations` 1/1 通过，首页仍可无滚动看完并正常切四标签。
- **模拟器（短屏）**：API 35 / 1080×1920 @480（360dp 宽）上同一 UI 测试 1/1 通过：自适应收行后课程行少于 6、作业保留 2，两卡同屏、内容不滚动；未单独要求 6 节。
- **截图**：三张（高屏浅色/深色、短屏浅色）作为 PR 预览附件放在 fork 的 evidence 分支，未进入代码分支/上游仓库。
- **真机**：未验证（6+2 与短屏收行只在模拟器上验证；真机字体缩放、刘海/手势区与系统栏高度仍需复验）。

## 2026-11-27 新增双日课表桌面小组件（Issue #121，模拟器渲染已验证）

需求来源：Issue #121 给出两张参考图——左边日期、右边下一个上课日的双栏课表组件，课程左侧原本是彩色竖条。用户确认：**保留现有「今日课表」2×2 组件不动**，新增 4×2/4×3 的「双日课表」宽版，让用户在组件选择器里自选；右栏显示**下一个有课日**（跳过周末/无课/放假）；课程左侧改为**起止时间 + 课程色圆点的时线**；今天**只列未结束的课**，已结束只写「已上 N 节」。

| 位置 | 内容 |
| --- | --- |
| `widget/TimetableWidgetWidePlan`（新） | 纯 Java 的双日数据：今天未结束的课（含正在上，按时间排序）、今天已结束数、星期、周次；右栏从明天起找第一个有课且不放假的日期（`HORIZON_DAYS = 7`），返回完整课程列表；`maxRows(heightDp)` 把 4×2 折成 1 行、4×3 折成 2 行，`todayRows/nextRows` 与隐藏数分开；`TimetableWidgetWidePlanTest` 10 例覆盖未结束过滤、直播、周末跳过、放假跳过、今天全结束、无开学日期、行数截断、7 天外无课 |
| `widget/TimetableWidgetWideProvider`（新） | 独立 provider，和旧「今日课表」并列；只读 RemoteViews：左栏日期/周次/星期 + 「已上 N 节 · 余 M 节」+ 今天时线；右栏下一个有课日 + 日期/节数 + 时线；每行起止时间、课程色圆点（沿用完整课表的五色 palette）、课程名与教室；行高按宿主高度 1/2 行，超出「其他 N 节」，无下一节「未来 7 天没有课」，无课表走空态；点组件进完整课表；跨天/改时钟/换时区/开机/覆盖安装与旧 provider 同一套广播；`buildViews` 包内可见供渲染测试 |
| 布局/规格 | `widget_timetable_wide.xml`（双栏 + 空态）、`widget_timetable_wide_row.xml`（时间/圆点/竖线/课程）、`timetable_wide_widget_info.xml`（label 双日课表、target 4×2、min 250×110、只允许纵向拉到 4×3）；旧的 `widget_timetable*` 布局与 2×2 行为完全未改，旧组件 label 从「课表」改为「今日课表」以区分 |
| 刷新/入口 | `TimetableWidgetSync` 的写入监听同时刷新新旧两个 provider；`detachIfUnused` 在两个课表组件都没实例时才解除；`UnloopApplication` 任一存在就挂监听；设置页「桌面课表小组件」改为样式选择对话框（今日课表 2×2 / 双日课表 4×2/4×3），仍走 `requestPinAppWidget` |
| 测试 | 新增 10 条 `TimetableWidgetWidePlanTest`；新增 `TimetableWidgetWideRenderTest`：真正 `RemoteViews.apply` 后测量/绘制 4×3、4×2、浅色、深色四张图，四张都通过（不是 mock 截图）；旧的 `TimetableWidgetPlanTest` 14 条保持不动 |

已知取舍：① 双日组件只面向 4×2/4×3，2×2 仍由旧的「今日课表」提供；两个 provider 会同时出现在桌面组件选择器，这是用户明确要的「自选样式」；② 4×2 每栏 1 行、4×3 每栏 2 行，依据宿主上报的可视高度；更长的课程列表用「其他 N 节」收口，不把组件撑高；③ 时线按课程顺序 + 起止时间画，不做按时长比例的空隙——RemoteViews 不是自由画布，但时间、圆点、竖线连续可读；④ 今天只列未结束课程，已结束只写「已上 N 节」；新样式不再显示旧组件的「还剩 X 分/下一节倒计时」，需要倒计时可继续用「今日课表」；⑤ 右栏只找未来 7 天，超出就写「未来 7 天没有课」；跨周/调课/节假日沿用 `TimetableDay` 同一套规则；⑥ 组件只读，点击整卡进完整课表，不提供在桌面改课表或按课程跳转；⑦ 旧「今日课表」的代码、布局、2×2 视觉不变，仅改了选择器 label。

验证（分口径）：

- **自动化**：`:app:testDebugUnitTest` 613 条、`:route-core:test` 109 条、`:simulation-core:test` 55 条，0 失败（app 较上一节 603 新增 `TimetableWidgetWidePlanTest` 10 条）；`:app:lintDebug` 0 errors / 287 warnings（新组件的密集小字号/嵌套权重在布局根上用 `tools:ignore` 说明，未抬高基线）；`:app:assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 通过。
- **仪器渲染**：API 35 / 1080×1920 @480（360×640dp）上 `TimetableWidgetWideRenderTest` 1/1 通过；4×3（330×190dp）与 4×2（330×120dp）在浅色/深色下都真正 inflate、measure、draw 成功，共四张真实 RemoteViews 截图；左/右行数与尺寸上限一致。截图本地复核，作为 PR 预览附件走 fork evidence 分支，不入代码分支。
- **旧组件回归**：`TimetableWidgetPlanTest` 14 条、旧 provider 的 2×2 布局与设置入口代码未改；设置页只把 pin 入口扩成样式选择。
- **未验证**：真实桌面（Launcher）里的组件选择器是否按 target 4×2 放置、拉高到 4×3 后宿主上报的高度是否如预期、`requestPinAppWidget` 在各家桌面上的确认框、跨天/省电策略下的刷新送达——本机只验证了 RemoteViews 真实渲染，没有把组件真正钉到桌面（桌面自动化成本高）。真机也未验证。
