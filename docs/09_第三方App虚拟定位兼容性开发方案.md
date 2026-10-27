# 第三方 App 虚拟定位兼容性：开发任务书与验收方案

日期：2026-10-27（Asia/Hong_Kong）。

本地审查基线：`E:\agri-run\route-workshop`，提交 `ce826df8da34e71221f47a450b57c4798a65f30c`，版本 0.2.3 / versionCode 10。

本文已经核对本地关键源码、GoGoGo 固定提交及 Android / Google 官方文档。本文是开发方案，尚未实施其中的功能，也未验证用户目标 App。它补充此前 `05_对标审查与改进方案.md`，替代 `08_0.2.3_悬浮条折叠.md` 中把第三方不识别过早归因为模拟检测的判断。

## 1. 可以直接交给写手的任务指令

> 请继续开发“第三方 App 虚拟定位兼容性”，不要把探针收到坐标等同于目标 App 已采用坐标，也不要仅凭 isMock 标记就结束排查。
>
> 先修复本项目自检的错误归因，增加独立的多通道接收探针，验证 GPS、network、Android framework fused 和设备具备时的 Google Play services FLP；根据实验结果补充官方模拟位置输出后端。然后在同一手机、同一目标 App、同一测试路线下，对照 GoGoGo 与本项目，定位差异并修复本应用能够控制的问题。
>
> 本轮先交付代码、测试、诊断报告和对照结果。目标 App 名称或设备尚未提供时，先完成不依赖它们的工程工作，并将目标验证标为待验。不要要求必须先有目标 APK 才能开始；也不要宣称无需任何目标信息就能保证所有 App 都接受。
>
> 所有模拟输出保留系统规定的 mock 标记。目标是补齐标准定位接口兼容性，不通过修改第三方 App、隐藏标记或伪造运动传感器来替代兼容性修复。

**审查结论：写手说“无法清除系统模拟标记”有依据，但直接推出“本应用层面没有可做的工作”证据不足。本项目确实存在可以立即推进的诊断与接口覆盖缺口。**

## 2. 先纠正三个技术结论

### 2.1 isMock 的版本说错了

`Location.isMock()` 加入于 Android 12 / API 31；旧方法 `isFromMockProvider()` 加入于 API 18，在 API 31 废弃。不能写成“Android 11 / API 30 起才有模拟位置识别”。[Android Location 官方说明](https://developer.android.com/reference/android/location/Location#isMock())、[API 30 → 31 差异](https://developer.android.com/sdk/api_diff/31/changes/android.location.Location)。

标准测试 provider 输出会被标为模拟位置。发送端改动自己构造的 Location 对象，不能保证改变 framework 最终交给其他进程的标记。[LocationManager 官方接口](https://developer.android.com/reference/android/location/LocationManager#setTestProviderLocation(java.lang.String,%20android.location.Location))。

但“能够被识别为模拟”不等于“目标已经检查并拒绝”。目标可能没有订阅到对应来源、拿到旧缓存、缺精确定位权限，或只在特定状态记录轨迹。必须用结果区分。

### 2.2 第三方接收位置与第三方认定正在运动不同

分开记录以下四层：

| 层次 | 需要的证据 | 不能推出的结论 |
| --- | --- | --- |
| L1：发布成功 | 系统接口成功、输出字段和时间戳完整 | 其他 App 已收到 |
| L2：独立客户端收到连续位置 | 独立进程持续回调与对应时间序列 | 目标 App 采用相同接口 |
| L3：目标地图或轨迹变化 | 目标 App 内的位置/轨迹实际变化 | 运动里程或活动类型一定认可 |
| L4：目标记录采用 | 目标测试记录的距离、速度等确实更新 | 其他版本、其他设备全部兼容 |

“地图上标记移动”“轨迹画出来”“距离累加”“识别为跑步”分别验收，不合并为一句“认不认”。

### 2.3 GoGoGo 是参考实现，不是普遍兼容保证

本次读取的 GoGoGo 固定提交为 `96139eee5e2e174901464d42eeaf67b00f9229f4`。公开 `ServiceGo.java` 同样使用 `LocationManager.addTestProvider()`、`setTestProviderLocation()` 向 GPS / network 注入位置，设置速度、航向及时间戳；调度代码含约 100 ms 的等待。这是公开模拟接口实现，不能据此推导它清除了 mock 标记。

该项目当前 README 也说明，系统策略、坐标系、缓存和目标应用逻辑会影响采纳结果。源码、当前发布 APK、历史版本可能不同，要记录实际测试版本。

如果用户所用 GoGoGo 在同一目标 App 上有效，应将其作为重要的对照线索。先复现实验，再比较本项目的输出与订阅链路，不能反过来否定用户体验；同样不能未经复现就保证复制公开实现必然成功。

来源：[固定提交 ServiceGo.java](https://github.com/ZCShou/GoGoGo/blob/96139eee5e2e174901464d42eeaf67b00f9229f4/app/src/main/java/com/zcshou/service/ServiceGo.java)、[固定提交 README](https://github.com/ZCShou/GoGoGo/blob/96139eee5e2e174901464d42eeaf67b00f9229f4/README.md)。

## 3. 本地源码中已确认的缺口

以下位置以基线提交为准，路径均相对于主工程。

### C-01：自检直接归咎于目标 App，属于错误诊断

文件：`simulation-core/src/main/java/org/localroute/simulation/LocationVerifier.java:72`。

当前 `ACCEPTED` 文案含：

> 如果目标 App 仍然不动，问题在目标 App 自己过滤模拟位置，不在本应用。

这个结论超出了证据范围。读取同一 provider 的缓存，不能证明其他定位通道已覆盖、独立客户端持续收到，或目标实际执行了过滤。

修改为：

> 此定位来源的回读与本次模拟输出一致。目标 App 的接收通道与采用情况尚未验证，请运行多通道探针或目标 App 对照测试。

同时更新 `LocationVerifierTest.explain_acceptedBlamesTargetApp`：验证文案描述证据边界，不固化“责任一定在目标”的结论。`NOT_MARKED_MOCK` 也只报告观测值，不自动解释为“系统把模拟位置当真实位置”。

### C-02：当前自检只读缓存，且判定没有使用位置年龄

文件：`app/src/main/java/org/localroute/app/diagnostics/SystemLocationReader.java`、`ui/DiagnosticsActivity.java:194`。

当前只调用 `getLastKnownLocation()`。虽然界面显示位置年龄，但传给 `LocationVerifier.evaluate()` 的只有预期点、观测点、mock 布尔值及距离容差，没有时间新鲜度或连续性。

旧缓存刚好同点可能被判成功；运动时拿最新发送点与稍早回调比较，也可能错误判断偏移。应增加连续回调证据，并根据观测时间匹配那一时刻的预期路线位置。

### C-03：独立探针只覆盖 GPS / network

文件：`location-probe/src/main/java/org/localroute/probe/ProbeActivity.java:36`。

当前 `PROVIDERS` 只有 GPS、network；没有 framework fused、Criteria 选择方式、Google Play services FLP 的独立读取验证。已有探针结果只能证明已测通道。

### C-04：framework fused 与 Google FLP 被混淆的风险

文件：`simulation-core/src/main/java/org/localroute/simulation/MockLocationSession.java:30`、`app/src/main/java/org/localroute/app/simulation/AndroidMockProviderApi.java`。

当前尝试 GPS / network / 字符串 `fused`，全部通过 Android `LocationManager` 调用。本次检索主应用、探针和依赖配置，没有发现 `FusedLocationProviderClient`、`setMockMode()`、`setMockLocation()` 接入。

**framework 内名为 fused 的 provider 注册成功，不足以证明 Google Play services FLP 客户端收到相同数据。** 是否间接传递必须实测；需要时可接入 Google 官方提供的 FLP 模拟接口。

### C-05：不同 provider 使用同一属性配置

文件：`app/src/main/java/org/localroute/app/simulation/AndroidMockProviderApi.java:51`。

当前对所有 provider 使用相同的低功耗、高精度及 requiresNetwork/requiresSatellite 配置。GoGoGo 则区分 GPS 与 network 属性。该差异可能影响基于 Criteria 的选择，属于需实验验证的兼容性因素，不是已证实的故障根因。

新增按 provider 的属性配置和 Criteria 选择记录；仅在证据说明需要时调整默认值。不要把“功耗设高”“加 satellites 字段”写成必定生效的秘诀。

### C-06：已有完整字段，但不等于已验证运动数据一致性

`SimulationService.fixFor()`、`AndroidMockProviderApi.setTestProviderLocation()` 已设置位置、精度、速度、航向、挂钟及 elapsed realtime。移动精度目前为固定 3 米、静止 5 米，海拔固定 10 米，属于模拟参数，不是传感器实测值。

应验证路径位移/时间与速度相符、暂停输出 0、结束帧合理，以及独立客户端收到的字段是否保留。500 ms 是调度目标，Handler 与后台系统不保证严格实时；不能未经测量就写“节奏完全稳定”。

## 4. 开发包 A：独立多通道接收探针

保持探针为独立包名、独立进程，只通过公开定位接口接收，不读取主应用内部状态冒充系统回读。

需要以下读取模式：

| 模式 | 方法 | 目的 |
| --- | --- | --- |
| GPS | LocationManager 连续更新 | 验证卫星来源的模拟回调 |
| network | LocationManager 连续更新 | 验证网络来源 |
| framework fused | 存在时订阅该 provider | 验证平台融合来源 |
| Criteria | 记录实际选中的 provider，再订阅 | 复现依赖条件选择的客户端 |
| GMS FLP | 设备支持时使用官方 FLP 连续更新 | 验证 Google 定位通道 |
| 缓存/单次 | last-known 与 current 分开显示 | 区分旧缓存和新结果 |

每帧记录：runId（通过测试清单关联）、读取模式、provider、回调到达时间、Location 的 elapsedRealtimeNanos / time、位置年龄、经纬度、accuracy、hasSpeed/speed、hasBearing/bearing、mock 标记。

年龄阈值按模式声明，例如连续 500 ms 测试中采用 5 秒作为初始“过期”阈值；实际阈值应可配置。没有新回调、过期缓存和位置不一致应使用不同状态。

报告记录首帧延迟、回调数量、间隔中位数/P95/最大值、最长断流、速度与位移一致性。客户端可能按请求条件降采样或批量回调，不要求所有客户端都恰好每 500 ms 回调一次。

GMS 不存在、版本不满足、权限不足、来源不存在都显示 `UNAVAILABLE` 或明确原因，不能悄悄回退 GPS 后标为 FLP 已通过。

## 5. 开发包 B：可选择的标准输出后端

### 5.1 架构

保留路线计算和主服务，增加 `LocationOutputBackend` 抽象：

```text
RouteEngine / 分段速度进度
             ↓ 同一份带时间戳的位置帧
SimulationService / 输出协调器
             ├─ FrameworkBackend：gps / network / 可用的平台 fused
             └─ GmsFlpBackend：设备支持时的官方模拟接口
```

统一帧结构带 sessionId、generation、sequence、WGS-84 坐标、模拟速度、航向、精度和时间戳。输出确认按后端分别记录，不能把某一个成功合并成全部成功。

### 5.2 GMS 官方接口的实施要求

Google 官方提供 `FusedLocationProviderClient.setMockMode(true)` 和 `setMockLocation(Location)`。其模拟模式影响使用 FLP 的客户端，包括其他进程，但输出仍带 mock 标记。因此它是另一条标准模拟输出通道，不是隐藏或消除检测。[Google 官方文档](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)。

实施步骤：

1. 固定与工程兼容的 `play-services-location` 版本，先检查设备的 Google Play services 可用性；大陆设备可能没有可用 GMS，不能把它设为基础功能硬依赖。
2. 用户已在开发者选项中选本应用后，进入 GMS 模拟模式；等待返回 Task 成功，失败须报告具体状态。
3. 使用同一服务时间源生成位置帧，再向 GMS 提交；按后端要求维护时间戳递增，记录异步 Task 的完成与异常。
4. 以单序列、有限在途任务的方式发送。遇到积压采用有界策略保留新状态，不创建多个倒序完成的发布循环。会话切换或停止后，用 generation 拒绝旧回调污染状态。
5. 停止时先停止新帧与在途发布，再退出模拟模式并观察完成结果；不能只清理 framework providers。退出失败需保留可见错误和恢复入口。
6. 加入“Framework”“GMS FLP”“组合”可选后端用于实验；默认配置以设备验证结果决定。组合模式同时注入的作用、冲突和清理必须经过独立读者验证。

GMS 模拟模式会影响该设备其他使用 FLP 的客户端，应在用户开启会话时清楚显示，并在正常停止时恢复。不要自动安装 Google 服务、强制启用不存在的来源或后台无提示开启模拟。

### 5.3 不需要重做的部分

保留已有 WGS-84、路线不可变快照、分段变速、暂停时间处理、通知与悬浮控制。新增后端接在统一帧之后，不在每个后端各算一条路线。初次位置速度为 0 本身不是缺陷，应按启动/移动/暂停/完成状态解释。

## 6. 开发包 C：GoGoGo 与本项目的可复现对照

### 6.1 需要记录的信息

不需要目标 APK 才能先开发 A/B 包，但要验证“这个第三方 App 可用”，至少需要目标 App 名称、版本和测试设备；若问题仍不明，用户可再提供安装包或日志做只读分析。

记录设备型号、Android/ROM 版本、是否有 GMS及版本、双方 APK 版本/哈希、目标 App 版本与权限、路线来源/坐标系、网络与节电状态。上游源码参考版本与实际 APK 版本分别记录。

### 6.2 实验顺序

1. 先记录未模拟时目标 App 与独立探针的基线。
2. 选择同一条可控闭合路线、同一速度和时长。每次只允许一个模拟提供者生效；切换开发者选项后确认旧会话已停止，避免两个工具同时覆盖。
3. 运行 GoGoGo，记录探针多通道结果，以及目标 App 的地图、轨迹、距离、速度、错误提示。
4. 清理上一轮会话和测试界面状态，再运行本项目同条件测试；不要删除目标 App 用户数据。
5. 再重复一次双方对照，排除顺序、缓存和偶然后台冻结。若 GoGoGo 实际版本不支持同一路线或相同运行方式，明确记录条件差异，不能声称严格等条件。

每轮包括静止、连续移动、暂停、继续、改速、完成和停止恢复。目标 App 必须进入自己的轨迹记录流程；仅停留地图首页不能证明它开启了运动统计。

### 6.3 结果如何指导开发

| 观测 | 下一步 |
| --- | --- |
| 本项目独立探针也没新帧 | 修本项目输出、权限或生命周期 |
| GPS 更新，FLP 不更新 | 验证 FLP 模拟后端及其生命周期；不归咎于目标 |
| GoGoGo 有效，本项目无效 | 比较来源、字段、时间戳、频率、启动顺序和 provider 属性，逐项改变一个变量 |
| 两者地图都变，目标都不累计运动距离 | 区分目标记录流程、数据条件和明确拒绝提示；不能靠增加发送频率断言可修 |
| 本项目目标 App 有轨迹且距离更新 | 记录这组版本/设备/配置为通过，不扩大成全部兼容 |
| 目标明确拒绝模拟位置 | 如实记录不兼容；继续输出标准模拟数据不会改变其接受策略，使用目标提供的测试模式或集成接口（若有） |
| 无目标设备或名称 | 交付已完成代码与探针结果，保留目标验收待办，不写已解决 |

对未知原因使用 `UNKNOWN`。没有原始回调或明确提示，不能把“没有里程”自动诊断为 isMock 检查；APK 中存在相关 API 引用也不能单独证明该分支在本次运行执行了。

## 7. 开发包 D：真实的回归与交付

### 7.1 必须补的测试

- 自检：旧缓存不判连续接收成功；没有年龄/回调证据时不归因于目标过滤。
- 时间序列：同一单调时间匹配预期位置，不能把延迟回调一律判为偏移。
- 输出协调：GMS 进入失败、单帧 Task 失败、超时积压、迟到回调、停止后回调、重复停止和 provider 权限撤回。
- 运动正确性：9 km/h 运行 60 秒约 150 米，之后 18 km/h 再运行 60 秒累计约 450 米；暂停不累计，改速无位置跳变。
- 集成：在具备 GMS 的设备/模拟器上，由另一应用的 FLP 实际收到；在无 GMS 设备上基础 framework 功能可运行并说明 FLP 不可用。
- 清理：停止后恢复正常位置来源，且状态反映各后端的真实清理结果。

JVM fake 只能验证后端协调逻辑；不能把 mock 掉 Google Task 的测试称为跨进程兼容通过。模拟器与真我 GT Neo5 分开记录，Android 14/15 的服务限制和厂商后台策略需按实际设备复验。

### 7.2 验收分成三种结果

1. **工程交付完成**：后端、探针、诊断和测试代码已交付，构建检查通过。
2. **接收兼容通过**：指定设备的独立多通道客户端收到持续正确帧；mock 标记允许且预期存在。
3. **目标 App 验收通过**：指定 App 版本中需要的地图/轨迹/距离行为分别实测成立。

任何一层通过不能替代下一层。目标拒绝采用时，不能把探针通过包装成用户问题已解决；目标尚未提供时也不能宣称问题永远无法解决。

### 7.3 每次交付产物

```text
docs/compatibility/目标App_设备_版本.md
delivery/compatibility/<runId>/manifest.json
delivery/compatibility/<runId>/publisher.csv
delivery/compatibility/<runId>/reader.csv
delivery/compatibility/<runId>/observations.md
delivery/compatibility/<runId>/screenshots/
```

manifest 记录版本、包名、APK 哈希、设备、后端、模拟应用选择及测试时段；observations 逐项记录是否定位、是否画轨迹、是否累计距离、是否报错。导出前允许用户去除敏感地点，日志不写地图 Key、签名口令或账号凭据。

## 8. 建议开发顺序

| 顺序 | 任务 | 可开始条件 | 完成证据 |
| --- | --- | --- | --- |
| 1 | 修 C-01/C-02 与版本错误 | 当前源码即可 | 文案、时间新鲜度和匹配测试 |
| 2 | 升级独立探针 | 当前源码即可 | framework 多通道实测报告 |
| 3 | 抽象后端、增加可选 GMS 输出 | 编码不依赖目标 APK；集成需要可用 GMS 环境 | 双端 FLP 回调与清理证据 |
| 4 | 同机对照 GoGoGo | 可操作设备、双方安装版本、目标 App | 两轮完整对照表 |
| 5 | 根据证据修兼容问题 | 对照确认差异 | 修改前后相同条件复验 |
| 6 | 发布带配置说明的版本 | 构建、回归、交付记录齐备 | APK/哈希/测试报告与已知限制 |

不要在完成对照之前随意添加随机抖动、假步数、传感器伪造或扩大权限；这些既不能证明定位兼容，也会使问题更难归因。无需把 Root、Xposed 或修改 ROM 当作本轮工作的前提。

## 9. 本次已做与未做

**已做：**读取本地 0.2.3 的服务、模拟接口、自检判定、诊断页面、探针和依赖；读取 GoGoGo 固定提交的定位服务；核对官方 API；据此形成开发任务。

**未做：**本轮未修改功能代码、未重新运行功能测试、未构建新版 APK、未接入 GMS 后端、未在目标 App 或真机上实验。此前版本的测试数量和截图不能算成本方案已经实现。

当前还缺目标 App 名称/版本及可复现的同设备对照，故无法负责任地承诺目标会认定正在跑圈。可以立即推进的是上述代码与验证工作，而不是用一句能力边界代替整个开发任务。

## 10. 参考资料

- [Android Location：isMock / isFromMockProvider](https://developer.android.com/reference/android/location/Location)。
- [Android API 31 中 Location 变化](https://developer.android.com/sdk/api_diff/31/changes/android.location.Location)。
- [Android LocationManager 测试 provider 接口](https://developer.android.com/reference/android/location/LocationManager)。
- [Google FusedLocationProviderClient：setMockMode / setMockLocation](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)。
- [GoGoGo 固定参考实现](https://github.com/ZCShou/GoGoGo/blob/96139eee5e2e174901464d42eeaf67b00f9229f4/app/src/main/java/com/zcshou/service/ServiceGo.java)。
- [GoGoGo 固定参考说明](https://github.com/ZCShou/GoGoGo/blob/96139eee5e2e174901464d42eeaf67b00f9229f4/README.md)。

资料核对日期：2026-10-27。涉及具体 SDK 版本和设备行为，实施时再次核对。
