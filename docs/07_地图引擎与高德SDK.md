# 07 地图引擎：高德官方 SDK 与瓦片兜底（0.2.1）

## 为什么做这件事

真机反馈「地图太老」，0.1.3 起默认用高德栅格瓦片（`webrd0{1-4}`，GCJ-02，显示层做 WGS-84↔GCJ-02 换算）。
瓦片方案有两个硬伤：

1. 地址不是公开接口，字段随时可能变，也没有 SLA；
2. 没有官方道路/POI 数据、没有离线，搜索与路径规划都做不了。

所以 0.2.1 引入高德官方 3D SDK 作为**可选引擎**：有 Key 且设备是 arm 架构时用 SDK，
其它情况（模拟器、没配 Key、SDK 初始化抛异常）自动退回原有瓦片实现，保证「装上就能看见地图」。

## 结构

```
RouteMapHost（接口）── 画路线/画当前位置/取景/手势/生命周期/engineLabel
   ├─ RouteMapController     MapLibre + 栅格瓦片（默认兜底，任何 ABI 都能跑）
   └─ AmapRouteMapView       高德 3D SDK（GCJ-02 原生，进出做 ChinaShift 换算）
MapBackendPolicy             纯 Java 判定：Key + SUPPORTED_ABIS → 用哪个引擎
```

- `app/src/main/java/org/localroute/app/ui/MainActivity.java` 只依赖 `RouteMapHost`，
  引擎选择发生在 `onCreate`：`MapBackendPolicy.choose(BuildConfig.AMAP_KEY, Build.SUPPORTED_ABIS)`。
- 选中 SDK 后若构造抛异常（动态库缺失、Key 被拒、隐私合规未初始化），catch 后**当场换回瓦片实现**，
  并把原因写进诊断日志，不会白屏。
- 瓦片源的「切换地图瓦片源」菜单在 SDK 模式下会提示「底图由 SDK 提供」，不假装能切。

## 判定规则（`MapBackendPolicy`，9 个单测覆盖）

| AMAP_KEY | SUPPORTED_ABIS | 结果 | 理由文案 |
| --- | --- | --- | --- |
| 空/空白/null | 任意 | 瓦片 | 未配置 AMAP_KEY，使用栅格瓦片底图 |
| 有 | 含 arm64-v8a 或 armeabi-v7a | **高德 SDK** | 检测到 arm64-v8a 与 AMAP_KEY，启用高德官方 SDK |
| 有 | 只有 x86_64 / x86 | 瓦片 | 当前架构 x86_64 不在高德 SDK 支持范围（仅 arm），退回栅格瓦片底图 |
| 有 | 读不到 | 瓦片 | 读不到 CPU 架构信息，保守使用栅格瓦片底图 |

架构比较忽略大小写；`SUPPORTED_ABIS` 里只要出现 arm 就算命中（真机可能把 x86 排在前面）。

## 坐标

- 存储、界面、引擎计算一律 WGS-84；高德 SDK 的 `LatLng` 是 GCJ-02。
- `AmapRouteMapView` 内部：`toDisplay()` 做 WGS84→GCJ02（画线、画点、取景），
  地图点击与 `cameraCenter()` 做 GCJ02→WGS84（回到业务坐标）。
- 换算实现在 `route-core/src/main/java/org/localroute/core/ChinaShift.java`（7 个单测）：
  境外原样返回，境外逆向同样不变，往返误差 < 0.5 米。

## 代价与风险（如实记录）

| 项 | 实测/估算 |
| --- | --- |
| release APK 体积 | 32.98 MB → **69.07 MB**（`libAMapSDK_MAP_v10_0_600.so` 的 arm64 + armv7 两份） |
| debug APK 体积 | 84.36 MB（还要带 x86_64 的 MapLibre） |
| 支持架构 | 只有 arm64-v8a / armeabi-v7a；x86 模拟器无法加载 SDK |
| 验证层次 | 编译、单测（`MapBackendPolicyTest` 9 条）、模拟器上的**回退路径**（日志 + 截图）；**SDK 渲染路径没有在任何设备上跑过** |
| 隐私合规 | 10.x 需要 `MapsInitializer.updatePrivacyShow/updatePrivacyAgree`，已在构造时调用（老版本缺方法时静默忽略） |

## 怎么启用

1. `local.properties` 加一行 `AMAP_KEY=<你在高德开放平台申请的 Key>`；
   Key 绑定「包名 + SHA-1」，本机签名 SHA-1 取自 `keystore/route-workshop-release.jks`。
2. 重新 `assembleRelease` 装到 arm 真机上；启动日志应出现
   「地图引擎：检测到 arm64-v8a 与 AMAP_KEY，启用高德官方 SDK」。
3. 若看到「改用瓦片底图」，说明 Key 校验失败或 so 未加载，界面上方仍有地图可用，
   诊断页会记录异常文本。

## 已验证 / 未验证

- ✅ 编译通过（`assembleRelease`/`assembleDebug`）、lint 0 error、194 个 JVM 用例全绿。
- ✅ release APK 内确认存在 `lib/arm64-v8a/libAMapSDK_MAP_v10_0_600.so` 与 `lib/armeabi-v7a/...`。
- ✅ x86_64 模拟器（API 35）：`MapBackendPolicy` 判定回退，日志「当前架构 x86_64 不在高德 SDK 支持范围」，
  底图正常加载、路线与配速等界面无回归（`delivery/evidence/emulator/012-0.2.1-瓦片引擎在x86_64上回退正常.png`）。
- ❌ SDK 渲染路径（真机上的瓦片清晰度、手势、`animateCamera` 取景、标记图标）**未实测**。
- ❌ 未在真机上验证 Key 校验结果；若高德侧 Key 未绑定本机 SHA-1，会走回退分支。

## 2026-10-27 追加：Key 已配置

- 用户提供的 Key 写在 `local.properties` 的 `amapKey=`（32 位，文件被 `.gitignore` 排除，不进 source.zip）。
- 构建产物核对：`BuildConfig.AMAP_KEY` 非空；`aapt2 dump xmltree app-debug.apk --file AndroidManifest.xml`
  显示 `com.amap.api.v2.apikey` 的 `value` 为该 32 位串；`com.amap.api:3dmap:10.0.600` 的 arm so 在包内。
- 模拟器（x86_64）实测：debug 包可安装并正常启动，日志仍为
  「当前架构 x86_64 不在高德 SDK 支持范围（仅 arm），退回栅格瓦片底图」——Key 已打包，但**架构闸门按预期拦住了 SDK**。
- release 包（arm-only）在 x86_64 模拟器上安装失败：`INSTALL_FAILED_NO_MATCHING_ABIS`，
  这条恰好反证了「release 只含 arm so、只能装真机」。
- 仍未验证：真机上的 SDK 渲染、Key 鉴权是否通过（Key 需绑定包名 `org.localroute.app` 与
  签名 SHA-1 `B8:CB:D6:D9:...:7B:2F`）。
## 0.2.2 真机反馈修复（2026-10-27）

真机（arm64 + AMAP_KEY）报告「日志说已启用高德 SDK，但界面一片空白、点不了地图、也传送不了」。两个原因，都已修：

1. **MapLibre 视图仍在最上层**：`activity_main.xml` 里的 `MapView`（MapLibre）是常驻控件，高德 SDK 的 MapView 被 `addView(..., 0, ...)` 插到它下面。MapLibre 画的是自己的空白画布并吃掉触摸事件，于是既看不见高德底图，也点不到地图（传送依赖点击）。
   修法：`MainActivity` 选择 SDK 引擎后立刻 `binding.mapView.setVisibility(View.GONE)`（`app/src/main/java/org/localroute/app/ui/MainActivity.java`），把触摸让给 SDK 地图。
2. **`getMap()` 在构造时为 null**：高德 `MapView` 的异步初始化在真机上可能晚于构造，原实现直接判失败后再也不重试。
   修法：`AmapRouteMapView` 增加 `setupDone` 标记，构造时取一次、`onResume` 再取一次，拿到非空 map 才 `setup()`；失败时状态提示改为「稍后会自动重试」。

同时新增菜单「定位到当前位置」（`R.id.action_locate_me` → `MainActivity.locateMe()`）：先读 GPS/网络/fused 的最后一次定位；都读不到时用 `LocationManager.getCurrentLocation(GPS)` 请求一次实时定位，拿到后 `focusOn` + 红色跑者标记。整段流程有 `DiagnosticLog` 记录（`定位：...`），便于真机上排查。

**验证层次**：该项只能在 arm 真机验证；本机 x86_64 模拟器按策略退回瓦片引擎，因此本次只验证了「瓦片引擎 + 定位到当前位置」（模拟器上 `adb logcat -s RouteWorkshop` 能看到 `定位到当前位置：31.230000,121.473700 via gps`，截图 `evidence-emu/53-locate-ok.png`）。