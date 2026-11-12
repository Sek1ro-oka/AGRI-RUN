# 更新说明

本文件保存历史版本的功能与修复记录，按主题归档，便于追溯。
较新的版本说明见 GitHub Releases（<https://github.com/Sek1ro-oka/AGRI-RUN/releases>）与 git 提交历史。

## 0.1.1 变更（针对真机反馈）

| 反馈 | 处理 |
| --- | --- |
| 打开后地图一片黑 | 瓦片源不再写死：默认 OSM 官方，另备 OSM France 与 OSM Germany；地图区域先铺浅灰底 + 「地图加载中」提示；样式 10 秒未就绪就把原因显示在底部，并保留手动输入与单点发送。菜单「地图瓦片源」可随时切换 |
| 点 3 个点就自动连回起点 | 闭合改为用户可选（菜单「闭合路线」，勾选 = 连回起点）。不闭合时运行到终点后**沿原路返回**，一圈 = 单程的两倍，界面显示「不闭合（原路返回）· 单圈」 |
| 开发者选项的模拟位置应用列表里没有本应用 | 清单补声明 `android.permission.ACCESS_MOCK_LOCATION`（AOSP 的 AppPicker 用它过滤候选应用），已用 aapt2 确认 0.1.1 的 APK 里包含该权限 |
| 想直接传送单点位置 | 菜单「发送单点位置」：输入经纬度（默认填地图中心），前台服务每 500 ms 提交同一个坐标、速度 0，通知栏可停止 |

测试：0.1.1 当时是 107 个 JVM 用例（route-core 56 / simulation-core 16 / app 35），lint 0 error。

## 0.1.2 变更（针对第二轮反馈）

| 反馈 | 处理 |
| --- | --- |
| 想要「把当前定位传送到地图上选点」 | 菜单「传送到地图选点」：进入后点一下地图，模拟位置立刻搬到该点并钉住（速度 0），复用单点模式的前台服务与通知；再点一次菜单或点「停止」结束。运行中会先要求停止，避免一个任务被两个入口抢 |
| 地图太老，想用高德/百度 | 菜单「地图瓦片源」新增 **高德地图**（GCJ-02）。高德底图与 WGS-84 之间自动做双向换算（新增 `ChinaShift`：正向公式 + 5 次不动点迭代求逆），在高德底图上点选的点也会先转回 WGS-84 再存，因此换底图不会让路线漂移。百度未接入（其瓦片是 BD-09 私有矢量接口，需要官方 SDK key；见 `docs/ISSUES.md` I-036） |
| 「还没办法真正实现虚拟定位」 | 新增**位置自检**：诊断页每 3 秒读一次 `LocationManager.getLastKnownLocation(gps/network/fused)`，与前台服务最后提交的坐标对比，给出「系统已接受（偏差 X 米，带模拟标记）／偏差过大／未收到／无模拟标记」四种结论并解释该怀疑谁。若自检显示 gps 已接受而目标 App 仍不动，说明是目标 App 自己过滤模拟位置（本应用无权、也不应绕过） |

测试：**144 个 JVM 用例**（route-core 78 / simulation-core 24 / app 42），lint 0 error（app 16 warning、探针 1 warning）；另有 API 35 模拟器实测记录，见 `docs/04_模拟器实测记录.md`。
当时的真机行为（传送是否立刻生效、高德底图是否可达、自检四种结论的真实表现）尚未实测；0.1.3 已在模拟器上补齐大部分，
真机仍未复验，见 `delivery/KNOWN_ISSUES_已知问题.md`。

## 0.1.3 变更（模拟器实测 + 子代理功能）

| 事项 | 处理 |
| --- | --- |
| 底图在国内/模拟器里打不开 | 根因是 OSM 域名解析到黑洞地址（`10.112.4.179`）后 `SSLHandshakeException`。默认瓦片源改为**高德**，OSM 仍可在菜单里选；另加瓦片探测（HEAD 请求 4 秒超时）与地图区可见提示，不再只给一块空白 |
| 加完节点看不到点在哪 | 首个节点自动放大到 16 级并居中；后续节点落在视野外时自动重新取景（`RouteMapController.isVisible`） |
| 想把路线带到别处 | 新增 **GPX 导入/导出**（`route-core` 的 `GpxCodec`，子代理实现）：`trk/trkseg/trkpt` 单段折线，读写都按 WGS-84 |
| 想在模拟器里验证 | 调试包增加 `x86_64`（release 仍只有 `arm64-v8a` / `armeabi-v7a`），并在 API 35 模拟器上跑通「运行 → 探针读到 mock 帧 → 传送 → GPX 往返 → 高德底图坐标对齐」全流程 |

测试：**144 个 JVM 用例**（route-core 78 / simulation-core 24 / app 42），lint 0 error（app 16 warning、探针 1 warning）。
模拟器实测的截图、系统读数与复现步骤见 `docs/04_模拟器实测记录.md` 与 `delivery/evidence/emulator/`；
**真机（真我 GT Neo5）仍未复验**，不能拿模拟器结论代替。

## 0.2.0 变更（按审查方案 A—G 落地）

| 项 | 变化 |
| --- | --- |
| 路线默认几何 | 新路线默认**不闭合**；工具栏下方固定一行「将当前末点连接到第一个点」，随时勾选/取消（审查 R-001） |
| 终点策略 | 开口路线可选「到终点停止（默认）」或「原路往返」；到终点停止时只跑一次、停在末点，不再瞬移回起点（审查 5.1 / 5.2） |
| 旧路线兼容 | 文件 schema 升到 v2；v1 的 `closed=false` 仍按旧的「原路往返」解释，运行结果不变 |
| 速度 | 新增 3 / 6 / 9 / 12 km/h 配速按钮；运行中点击立即改速，位置连续、不跳变、不补算旧时间 |
| 地图切源 | 切换瓦片源后自动重放当前草稿，不再丢点；起点用橙色圈标出，顶点不再逐个画点 |
| GPX | 多段 GPX 只导入第一段并提示原因，避免段间被连成一条不存在直线 |

测试：**173 个 JVM 用例**（route-core 102 / simulation-core 24 / app 47），lint 0 error（app 29 warning）；模拟器实测记录见 `docs/06_0.2.0_变更与实测.md`。
仍未做：官方高德/百度 SDK（缺 Key）、悬浮地图与搜索、常用点、真机复验。

## 10. 补充：可编辑配速与地图 Key 适配层（0.2.0 build 6）

- 配速改为三种具名预设（行走 5.0 / 跑步 9.0 / 骑行 15.0 km/h，单击套用、长按改值、可恢复默认），保留手动输入；模拟器首屏已验证按钮文案。
- 新增 `MapProviders` 适配层：Key 从 `local.properties` 的 `amapKey=` 注入 `BuildConfig.AMAP_KEY`，不进仓库；无 Key 时明确提示「当前使用非官方栅格瓦片」，有 Key 也只标「待接入」——`MapProvidersTest` 断言不会谎称官方 SDK 已生效。
- 用例总数 173 → **185**（新增 `SpeedPresetsTest` 8、`MapProvidersTest` 4）；lint 仍 0 error、app 29 warning。
- 版本：`versionName 0.2.0`、`versionCode 6`，签名证书 SHA-256 仍为 `14f0286f…02a9c`（同一把本地 key，可覆盖安装 build 5）。

## 0.2.1 变更（高德官方 SDK 可选引擎）

- 新增 `RouteMapHost` 抽象与 `AmapRouteMapView`：`local.properties` 填 `AMAP_KEY`、且设备是 arm（arm64-v8a / armeabi-v7a）时启用高德官方 3D SDK；其余情况（模拟器、无 Key、SDK 初始化失败）自动退回原瓦片底图，不会白屏。
- 判定逻辑在 `MapBackendPolicy`（9 个单测）：Key 为空 → 瓦片；含 arm → SDK；只有 x86 → 瓦片。
- 代价：release APK 从 32.98 MB 增到 **69.07 MB**（两份 arm so）。SDK 路径**未在任何设备上跑过**，只在模拟器验证了回退路径；细节与风险见 `docs/07_地图引擎与高德SDK.md`。
- 测试：**194 个 JVM 用例**（route-core 102 / simulation-core 24 / app 68），lint 0 error（app 31 warning）。

## 0.3.3 变更（真机验收意见 R32-01…R32-07）

真机验收确认：目标 App「农林快跑」**已经能记录模拟运动距离**，核心需求达成。本版修的是真机上暴露的四个 P1 与三个 P2：

| 缺陷 | 处理 |
| --- | --- |
| R32-01 路线运行中点 A/B/C 只跳一下就不动了 | 抽出 `TaskModeSwitch`：RUNNING 保持不动状态机、PAUSED 不自动恢复、只有 STARTING 才调 `providerReady()`；不给状态机加 RUNNING→RUNNING 自转移 |
| R32-02 探针的高德“对照通道”其实没生效 | `setMockEnable(true)` 挪到 `setLocationPurpose(Sport)` **之后**；报告用 getter 实读并打 ✓/✗ 自检 |
| R32-03 反复传送后发布节奏变快 | 新增 `TickScheduler`：同一 Handler 上最多一个待执行 tick，连续排程 20 次只投递 1 次 |
| R32-04 主界面切后台后悬浮条冻住 | 订阅从 Activity 挪到单例 `OverlayHost`，`observeForever` 与 `removeObserver` 成对；旋转屏幕不再丢窗口 |
| R32-05 探针把错误回调算进距离/速度 | `ChannelStats` 分开「有效帧 / 错误回调」；无速度样本显示「未提供」；报告标注是传送测试还是路线测试 |
| R32-06 保存 A 期间切到 B，B 被误标已保存 | 保存令牌改为 `routeId + draftGeneration + editRevision` 三者全等 |
| R32-07 文档与日志口径矛盾 | `docs/12` 修正“已加 GOGOGO_REFERENCE”的错误说法；首帧日志只报观测、不给结论式建议 |

测试：**256 个 JVM 用例、0 失败**（route-core 107 / simulation-core 55 / app 89 / location-probe 5），
lint **0 error / 34 warning**，release APK 73,185,200 B（versionCode 14），探针 12,736,975 B（0.1.4 / 4）。
详细记录见 `docs/15_0.3.3_R32修复与交付记录.md`。

## 0.3.2 变更（可用性修复：悬浮条、传送、拖动、探针）

这一版修的是「描述里写着、实际却不工作」的几处：

| 问题 | 处理 |
| --- | --- |
| 悬浮条只有运行中才显示，冷态在别的 App 里调不出来 | 悬浮条常显（只要开关打开且系统允许），未运行时显示「未运行 · 点 A/B/C 可传送到槽位」，点槽位即可发起传送 |
| 路线运行中点 A/B/C 只写日志、界面无反应 | 路线运行中传送改为**就地切成单点任务**（不重启服务、不重建 provider），并 toast「已发送传送命令」 |
| 拖动悬浮条会飞出屏幕、位置不保存 | 每帧夹到屏幕内，松手写入偏好，下次原位恢复；去掉 `FLAG_LAYOUT_NO_LIMITS` |
| 保存期间继续编辑，dirty 被无条件清掉 | 保存前记编辑代次，保存后只有代次未变才清 dirty，否则提示再存一次 |
| 诊断页看不出实际发送间隔 | 每 20 帧打一条「实际间隔：标称 X ms，中位 Y ms（±Z%），最长 W ms，样本 N 帧」 |
| 「地图瓦片源」弹窗仍说本版本只用栅格瓦片 | 文案改为「ABI 受支持时用高德官方 SDK 渲染，否则回退栅格瓦片」 |
| 探针把两个来源写在一行 | 日志追加改为同步 + 折掉行内换行 |
| 探针切到后台就停止接收 | 0.3.1 起后台继续，退出页面才停（0.3.2 保持） |

测试：`route-core 102` + `simulation-core 46`（新增 `PublishCadenceTest` 6 例）+ `app 68` = **216 个 JVM 用例，0 失败**。

## 0.3.1 变更（针对“目标 App 不累计里程”）

真机现象：探针四通道（gps / network / fused(framework) / gms-flp）都收到连续帧、模拟标记全为真、位移速度比 1.00，但第三方运动 App 的里程始终为 0。

- 新增 `FixQualityProfile`（simulation-core）：按速度给出水平/垂直/速度/航向精度与卫星数、平均与最高载噪比；移动仍 3 米、静止 5 米。
- 新增 `LocationFiller`（app）：framework 与 GMS FLP 两条后端共用一份填充，补齐 `setVerticalAccuracyMeters`、`setSpeedAccuracyMetersPerSecond`、`setBearingAccuracyDegrees`、`setElapsedRealtimeUncertaintyNanos` 与 `extras{satellites, meanCn0, maxCn0}`（network 来源不写卫星字段）。
- 新增菜单**兼容性：发送间隔**（500 / 1000 / 2000 ms，存 `ui` 偏好）：15 km/h 下 500 ms 一帧只有约 2.1 米，小于声明的 3 米精度，容易被目标 App 的距离滤波当抖动；2000 ms 一帧约 8 米。
- 诊断页在首次发布时打印一行「发布字段（每帧一致）：…」；当「每帧位移 < 声明精度」时额外写一条 warning。
- 定位探针：每帧多显示 `卫星=N spdAcc=…`；切到后台继续接收（此前一离开页面就停止订阅），可「探针在后台、目标 App 在前台」取证。
- 测试：**210 个 JVM 用例**（route-core 102 / simulation-core 40 / app 68），0 失败；lint 0 error、35 warning；release APK 69.78 MB（versionCode 12，可覆盖安装 0.3.0）。
- 复测步骤与判定表见 `docs/12_目标App里程不累计_排查与修改.md`；仍未解决的是目标 App 是否采信，属对方策略，`isMock` 标记无法清除。
