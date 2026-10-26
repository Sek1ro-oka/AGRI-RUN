# 路线工坊（Route Workshop）

一个**完全离线**的 Android 小工具：在地图上点出一条闭合路线，设置速度与圈数，然后用**Android 系统模拟位置**把
“沿这条路线匀速跑 N 圈”的坐标源源不断地喂给系统，让第三方运动 App 以为你在跑圈。

- 包名：`org.localroute.app`　版本：0.1.0（versionCode 1）
- 最低系统：Android 10（API 29），编译 SDK 35
- 无账号、无网络请求（地图瓦片除外）、无服务器、数据只存在手机本地
- 附带一个独立包名的小工具 **定位探针**（`org.localroute.probe`），用来独立验证“系统确实收到了坐标”

> 本仓库按 `E:\农林快跑\路线工具_开发任务书_v1.md` 开发，只做任务书里的 P0 范围。
> 它不修改、不注入、不 hook 任何第三方应用；是否采信模拟位置由系统与第三方应用自己决定。

## 功能一览

| 模块 | 说明 |
| --- | --- |
| 地图选点 | MapLibre + OpenStreetMap 栅格瓦片；单击加点，拖动/双指缩放不会误加点 |
| 路线编辑 | 撤销、清空、手动输入经纬度（WGS-84，纬度在前）、实时显示单圈长度 |
| 运行参数 | 速度 0.5–60 km/h（默认 9）、圈数 1–1000（默认 5）、每 500 ms 提交一次位置 |
| 运行控制 | 开始 / 暂停 / 继续 / 停止；重复点击不会产生第二个任务 |
| 运行展示 | 状态、当前位置、速度、累计距离、已完成圈数、有效运行时间（暂停不计时） |
| 模拟位置 | `LocationManager.addTestProvider` + `setTestProviderEnabled` + `setTestProviderLocation`，GPS 必测，NETWORK/FUSED 支持则一并提交 |
| 前台服务 | `foregroundServiceType="location"`，常驻通知上直接暂停/继续/停止；锁屏继续输出 |
| 本地保存 | 一条路线一个 JSON（文件名用路线 ID），写入走临时文件 + fsync + 原子替换；损坏文件隔离到 `corrupt/` |
| 设置检查 | 逐个检查“是否选为模拟位置应用、定位权限、系统定位开关、通知权限、电池优化” |
| 诊断信息 | 包名/版本/API/机型/当前状态/provider 支持矩阵；坐标日志默认关闭 |

## 快速开始

1. 安装 `delivery/路线工坊-0.1.0-release.apk`（另附 `定位探针` APK）。
2. 手机 **开发者选项 → 选择模拟位置信息应用 → 路线工坊**。
3. 打开 App 授予定位权限，打开系统定位；菜单「设置检查」逐项确认全部通过。
4. 地图上点 3–4 个点围出一圈，填速度与圈数，点「开始」。
5. 通知栏出现常驻通知，第三方运动 App 里应当能看到位置在移动。
6. 用「定位探针」独立复核：开始监听后应看到坐标在变、并标注为模拟位置。

完整步骤与验收清单见 `delivery/README_安装与使用.md`。

## 构建

需要 JDK 17+（本项目用 JDK 21 实测）、Android SDK（platform 35、build-tools 35.0.0）。
**Windows 上工程路径必须能通过纯 ASCII 路径访问**，否则 Gradle 的测试 worker 会
`ClassNotFoundException`、跨盘符构建还会报 `different roots`；本机用 `subst R: "E:\农林快跑"` 后用 `R:\route-workshop` 构建。
详见 `docs/BUILD.md`。

```powershell
$env:JAVA_HOME='D:\Java\jdk-21'; $env:ANDROID_HOME='E:\Android\Sdk'
Set-Location R:\route-workshop
.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease :app:lintDebug
```

## 目录

```
app/              主应用（Android）
route-core/       纯 Java：几何、路线编译、按距离取点、状态机、有效计时
simulation-core/  纯 Java：模拟位置 provider 会话（可注入 API，便于单测）
location-probe/   独立包名的定位探针
docs/             需求决策、技术设计、联网调研、构建说明、Issue 清单、测试计划
samples/          合成坐标示例路线（不是真实地点，更不是 B 操场）
delivery/         交付物：APK、源码包、使用说明、测试报告、校验和
```

## 已知限制

- 任务书没有给出 B 操场的确认坐标，所以**没有任何预置地点**；示例路线使用 `(0, 0)` 附近的合成坐标。
- 模拟位置能否被第三方 App 采信，取决于系统版本、厂商 ROM 与对方的风控策略，本工具不做承诺。
- OSM 公共瓦片有使用政策（署名、UA、禁止批量抓取），正式长期使用应换成自建或商业瓦片源。
- 未在真机（真我 GT Neo5）上实测的功能都在 `delivery/TEST_REPORT_测试结果.md` 中显式标注「未实测」。

## 许可与第三方

第三方组件与地图数据来源见 `docs/THIRD_PARTY_NOTICES.md`。
