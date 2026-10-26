# 测试计划与执行记录（T01–T24）

执行环境：无真机接入（`adb devices` 空），全部 JVM 单元测试在本机 JDK 21 上执行；
需要手机才能验证的项目**一律标注「未实测」**，不做任何推测性结论。

图例：✅ 自动化覆盖　⚠️ 代码已实现但只能在真机验证（未实测）　❌ 未实现

| 编号 | 场景 | 验收标准 | 覆盖情况 | 证据 |
| --- | --- | --- | --- | --- |
| T01 | 0 点、1 点、全重复点 | 无法开始，原因明确 | ✅ | `RouteCompilerTest`：0 点/1 点/全重合均抛 `InvalidRouteException(ROUTE_INVALID)` |
| T02 | 重复终点与相邻重复点 | 长度与去重后一致，无除零 | ✅ | `RouteCompilerTest`：相邻重合点被丢弃、终点回到起点时不多算零长边 |
| T03 | 两点闭合 | 正确往返，闭合边计入长度 | ✅ | `RouteCompilerTest`：两点单圈 = 2 × 直线距离 |
| T04 | 闭合小路线跑 1 圈 | 到终点恰好完成 1 次并停止 | ✅ | `RouteEngineTest`：`progressAt(target)` → finished、位置精确回起点、圈数 = 1 |
| T05 | 同一路线跑 5 圈 | 依次 +1，最终恰好 5，无第 6 圈 | ✅ | `RouteEngineTest`：4 圈后 1 秒仍为 4、`4.5 圈`不算 5、超时后距离被夹到目标 |
| T06 | 整圈边界前后与最后一个 tick | 无越界，距离限制在目标 | ✅ | `RouteEngineTest`：超过目标 10 秒仍等于目标距离与起点坐标 |
| T07 | 运行中暂停 30 秒 | 位置固定、速度 0、距离与有效时间不增长 | ✅ 逻辑 / ⚠️ 真机 | `ActiveElapsedClock` 暂停不累计；服务在 PAUSED 下以 0 速度提交同一位置（`SimulationService.doTick`） |
| T08 | 暂停后继续 | 从暂停点继续，无跳跃、不补算暂停时间 | ✅ 逻辑 / ⚠️ 真机 | `clock.pause/start` 配对，`handleResume` 用 `elapsedNanos` 续算 |
| T09 | 连续点开始/暂停/停止 | 只有一个任务，停止可重复调用 | ✅ 状态机 / ⚠️ 真机 | `LoopStateMachine` 非法迁移抛异常；非运行状态 `stop()` 为空操作；`START_NOT_STICKY` |
| T10 | 250 / 500 / 1000 ms 时间步 | 同等有效时间下位置与距离一致 | ✅ | `RouteEngineTest`：三种 tick 在同一有效时间得到相同 `Progress` |
| T11 | 调度间隔大于 5 秒 | 自动暂停并解释原因，不跨越大段路线 | ⚠️ | 策略抽到 `route-core` 的 `TickGapPolicy`（`defaults()`、`shouldPause(last,now)`、`reasonForGap`），tick 里命中即 RUNNING→PAUSED，有效时间只累计到上一次成功 tick，通知与界面都会带上原因；10 个单测覆盖边界，服务接线本身仍未上真机 |
| T12 | 跨 ±180° 合成路线 | 沿短方向插值 | ✅ | `GeoMathTest`：跨日界线 slerp 落在日期线附近；`RouteCompiler` 用球面插值 |
| T13 | NaN / 无限 / 越界坐标、速度、圈数 | 拒绝输入，不写入可运行路线 | ✅ | `GeoPoint` 构造校验、`RunConfig` 边界、`RouteCompilerTest`、`RouteEngineTest`、`RunConfigTest` |
| T14 | 未选模拟应用 / 权限拒绝 / 定位关闭 | 显示正确原因，不进入假 RUNNING | ⚠️ | 启动前逐项检查并 `failAndStop`；`SecurityException` 映射 `MOCK_APP_NOT_SELECTED`；只有首次提交成功才 `providerReady()` |
| T15 | 独立读取系统位置 | 能收到沿路线移动的测试位置 | ⚠️ | 已交付独立包名 `org.localroute.probe`（定位探针），显示坐标/速度/移动累计/是否标记为模拟位置 |
| T16 | Home / 切应用 / 锁屏 10 分钟 | 服务继续，独立日志持续更新 | ⚠️ | 前台服务 + PARTIAL WakeLock + 定时续期；`onTaskRemoved` 才停止 |
| T17 | 通知中暂停/继续/停止 | 与页面一致，停止释放资源 | ⚠️ | 通知两个 Action 与页面共用同一 `handlePause/handleResume/handleStop` |
| T18 | 旋转屏幕 / 重建 Activity | 路线、进度、按钮状态正确，不重开任务 | ⚠️ | 界面只订阅 `SimulationStateRepo` 的 LiveData，不持有服务引用 |
| T19 | 完成 / 停止 / 出错 | provider、tick、锁、通知按设计释放 | ✅ 部分 / ⚠️ 真机 | `MockLocationSession.stop()` 逆序只移除本次创建且幂等（12 个单测）；`cleanupEverything()` 幂等 |
| T20 | 保存、杀进程、重开 | 路线保留，不自动重启模拟 | ⚠️ | 路线落盘 `files/routes/<id>.json`；服务 `START_NOT_STICKY`，无自启动广播 |
| T21 | 地图网络失败 | 路线与引擎仍可用 | ⚠️ | 地图瓦片失败只影响底图，路线数据与引擎不依赖网络 |
| T22 | 连续运行—停止 20 次 | 无残留任务/锁泄漏/服务僵尸 | ⚠️ | 每次运行独立 `MockLocationSession` 与 WakeLock，`cleanup()` 幂等 |
| T23 | 5000 点路线 | 编辑与运行无 ANR，按距离查找不全量遍历 | ✅ 编译 / ⚠️ 真机 | `RouteCompilerTest` 校验 5000 点上限与 5001 点拒绝；`pointAt` 用前缀距离二分 |
| T24 | 同包同签名覆盖安装 | 保留路线数据，版本号正确 | ⚠️ | `versionCode 1 / versionName 0.1.0`；升级脚本见 `docs/BUILD.md` |

## 自动化测试清单（95 个）

| 模块 | 套件 | 用例数 |
| --- | --- | --- |
| route-core | `GeoMathTest` | 9 |
| route-core | `RouteCompilerTest` | 12 |
| route-core | `RouteEngineTest` | 13 |
| route-core | `LoopStateMachineTest` | 7 |
| route-core | `TickGapPolicyTest` | 10 |
| simulation-core | `MockLocationSessionTest` | 16 |
| app | `RouteJsonCodecTest` | 9 |
| app | `RouteRepositoryTest` | 10 |
| app | `RouteEditorViewModelTest` | 9 |
| 合计 | 9 个套件 | **95** |

执行命令与结果：

```powershell
Set-Location E:\agri-run\route-workshop
.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest --console=plain
# BUILD SUCCESSFUL；51 + 16 + 28 = 95 个用例，0 失败
```

## 如何把「未实测」变成「已实测」

1. 手机开「开发者选项 → 选择模拟位置信息应用 → 路线工坊」，安装两个 APK。
2. 先跑 T15：装定位探针 → 开始监听 → 在路线工坊里开始 → 探针应显示坐标移动且标注「模拟位置」。
3. 按 T04/T05/T07/T16/T17/T18/T20/T22 顺序各跑一遍，记录实际耗时与截图。
4. 全部结果回填本文件对应行的「证据」列，并把状态从 ⚠️ 改为 ✅ 或 ❌。
