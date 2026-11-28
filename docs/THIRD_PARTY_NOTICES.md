# 第三方组件与数据来源声明

本项目仅本地自用，仍按许可证要求列明第三方来源。

## 视觉设计参考

- Finmori UI Kit：采用其色彩令牌与列表、导航组件的视觉模式；原始设计 Zoey Do，代码及令牌参考 [Finmori](https://github.com/KhoaSuperman/Finmori)（MIT）。许可全文见 [Finmori-MIT.txt](licenses/Finmori-MIT.txt)。

## 代码依赖

| 组件 | 版本 | 许可证 | 用途 | 说明 |
| --- | --- | --- | --- | --- |
| MapLibre Native Android SDK（`org.maplibre.gl:android-sdk`） | 13.7.1 | BSD-2-Clause | 地图渲染 | 保留其版权与许可声明；未修改其源码 |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 | 基础 UI 兼容 | Google Maven |
| jsoup（`org.jsoup:jsoup`） | 1.17.2 | MIT 或 Apache-2.0（双许可，本项目按 Apache-2.0 使用） | 解析用户另存的教务课表页 HTML | Maven Central；未修改其源码，许可全文见其仓库 [jhy/jsoup](https://github.com/jhy/jsoup) |
| AndroidX Lifecycle（ViewModel / LiveData） | 2.8.7 | Apache-2.0 | 状态订阅 | Google Maven |
| Material Components for Android | 1.12.0 | Apache-2.0 | Material 3 控件 | Google Maven |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 | 单元测试（不随 APK 分发） | Maven Central |
| org.json（参考实现） | 20240303 | JSON License | 仅 JVM 单元测试，**不进入 APK** | Android 平台自带同名 API |
| AndroidX Test（core / ext-junit / runner / rules）、Espresso | 1.6.1 / 1.2.1 / 1.6.2 / 1.6.1 / 3.6.1 | Apache-2.0 | 仪器测试依赖（本次未在真机执行） | Google Maven |

AGP、Gradle、Kotlin 标准库（MapLibre 的传递依赖）均为 Apache-2.0。

## 课表导入的结构参考

- 参考项目：**WakeUp课程表**（原作者 YZune，原始仓库 `YZune/WakeupSchedule_Kotlin`，
  本项目参照的是其 Apache-2.0 公开镜像 [Matey-ace/wakeup-course-schedule](https://github.com/Matey-ace/wakeup-course-schedule)）。
- 参照内容：`app/src/main/java/com/suda/yzune/wakeupschedule/schedule_import/parser/` 下
  `NewZFParser.kt`（正方 `#table1` + `.festival` + `td[id]` 首字符 + `p[title]`）、
  `qz/QzParser.kt`（强智 `#kbtable` + `.kbcontent` + `title="周次(节次)/老师/教室/分组"` +
  「-----」分段）与 `CSVParser.kt`（七列顺序与「1-8单」周次写法、Excel 误转日期的回退）。
- 使用方式：**只参照其识别的页面结构与字段约定，代码为本项目独立用 Java 重写**
  （`TimetableHtmlParser`、`TimetableImportRow`、`TimetableOfflineImport`），
  未复制其 Kotlin 源文件；按 Apache-2.0 要求在此列明来源与修改说明。
- 许可：Apache License 2.0，全文见 <https://www.apache.org/licenses/LICENSE-2.0>。

## 课表导入的边界

- 教务页面导入只处理**用户自己导出或当前已登录页面里可见**的内容：
  文件导入走系统文件选择器，在线导入的脚本只在教务 WebView 内读取课表表格本身。
- 不代理登录、不保存账号密码、不伪造 User-Agent / Referer、不尝试绕过学校的访问限制或 IP 白名单。
  学校把教务限制在校内网时，本页会说明是访问限制，请用户自行用校园网或学校 VPN。

## 地图数据

- 底图瓦片：`https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- 数据版权：© OpenStreetMap contributors，数据以 **ODbL 1.0** 授权。
- 使用约束（OSMF Tile Usage Policy）：必须署名、使用可识别的 User-Agent、禁止批量抓取与离线预取、
  不得把公共瓦片服务当作产品级 CDN。本项目仅在样式 JSON 的 `attribution` 字段署名，
  **未**自定义 UA；正式长期使用应换成自建瓦片或商业瓦片服务。
- 本项目**不缓存**瓦片到磁盘，也不做离线地图。

## 地点搜索数据

- 搜索接口：`https://photon.komoot.io/api/`（Photon，基于 OpenStreetMap 数据）
- 数据版权：© OpenStreetMap contributors，数据以 **ODbL 1.0** 授权。
- 使用约束：公共示例服务有频率限制。本应用只在用户主动输入关键词时发起单次查询，
  每次最多显示 4 条结果，不做预取、批量抓取或后台轮询；
  正式长期使用应换成自建 Photon 实例或商业地点搜索服务。

## 未被使用的第三方内容

- 未使用任何商业地图 SDK（高德/百度/腾讯）与其密钥。
- 未内置任何真实地点坐标；`samples/` 中的坐标是 `(0, 0)` 附近的合成数据。

## 商标

「OpenStreetMap」是 OpenStreetMap Foundation 的商标；本项目与其无隶属关系。
其他产品名仅用于说明兼容性，版权归各自所有者。
