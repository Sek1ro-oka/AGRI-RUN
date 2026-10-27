# 本地 Issue 清单

本项目为单人开发；2026-10-27 起代码镜像到 <https://github.com/Sek1ro-oka/AGRI-RUN>（公开仓库，私钥、`local.properties` 与高德 Key 均不入库）。Issue 仍以本文件记录，按 agent 规则用本文件代替 GitHub Issues：
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
