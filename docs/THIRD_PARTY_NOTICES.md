# 第三方组件与数据来源声明

本项目仅本地自用，仍按许可证要求列明第三方来源。

## 代码依赖

| 组件 | 版本 | 许可证 | 用途 | 说明 |
| --- | --- | --- | --- | --- |
| MapLibre Native Android SDK（`org.maplibre.gl:android-sdk`） | 13.7.1 | BSD-2-Clause | 地图渲染 | 保留其版权与许可声明；未修改其源码 |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 | 基础 UI 兼容 | Google Maven |
| AndroidX Lifecycle（ViewModel / LiveData） | 2.8.7 | Apache-2.0 | 状态订阅 | Google Maven |
| Material Components for Android | 1.12.0 | Apache-2.0 | Material 3 控件 | Google Maven |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 | 单元测试（不随 APK 分发） | Maven Central |
| org.json（参考实现） | 20240303 | JSON License | 仅 JVM 单元测试，**不进入 APK** | Android 平台自带同名 API |
| AndroidX Test（core / ext-junit / runner / rules）、Espresso | 1.6.1 / 1.2.1 / 1.6.2 / 1.6.1 / 3.6.1 | Apache-2.0 | 仪器测试依赖（本次未在真机执行） | Google Maven |

AGP、Gradle、Kotlin 标准库（MapLibre 的传递依赖）均为 Apache-2.0。

## 地图数据

- 底图瓦片：`https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- 数据版权：© OpenStreetMap contributors，数据以 **ODbL 1.0** 授权。
- 使用约束（OSMF Tile Usage Policy）：必须署名、使用可识别的 User-Agent、禁止批量抓取与离线预取、
  不得把公共瓦片服务当作产品级 CDN。本项目仅在样式 JSON 的 `attribution` 字段署名，
  **未**自定义 UA；正式长期使用应换成自建瓦片或商业瓦片服务。
- 本项目**不缓存**瓦片到磁盘，也不做离线地图。

## 未被使用的第三方内容

- 未使用任何商业地图 SDK（高德/百度/腾讯）与其密钥。
- 未内置任何真实地点坐标；`samples/` 中的坐标是 `(0, 0)` 附近的合成数据。

## 商标

「OpenStreetMap」是 OpenStreetMap Foundation 的商标；本项目与其无隶属关系。
其他产品名仅用于说明兼容性，版权归各自所有者。
