# 构建说明（可复现）

本文件面向“拿到源码后想自己构建”的人。开发机的完整环境见 `docs/environment.md`，
更详细的踩坑记录见 `docs/BUILD.md`。

## 1. 前置

| 需要 | 版本 | 备注 |
| --- | --- | --- |
| JDK | 21（实测 21.0.3） | Gradle 8.11.1 **不支持** JDK 25 |
| Android SDK | platform 35、build-tools 35.0.0、platform-tools | `ANDROID_HOME` 指向 SDK 根目录 |
| Gradle | 8.11.1（wrapper 自带） | 首次会从腾讯镜像下载发行包 |
| 网络 | 能访问 `dl.google.com`、`repo1.maven.org` | 首次构建需要下载依赖 |

## 2. 关键：路径必须是 ASCII

工程现已放在纯 ASCII 路径 `E:\agri-run\route-workshop`，直接构建即可，不需要任何盘符映射。

历史：工程最初位于含中文的路径（`E:\农林快跑\route-workshop`），Windows 上会导致
Gradle 测试 worker `ClassNotFoundException`（无法加载测试类）、混用盘符时 dex 报
`this and base files have different roots`。当时用 `subst R:` 兜底；2026-10-26 已把目录改名成
ASCII，`subst` 映射与 `android.overridePathCheck` 开关都已删除。若你把源码解压到含中文的路径，
请先移动到纯 ASCII 目录再构建，否则测试会整体加载失败。

```powershell
Set-Location E:\agri-run\route-workshop
$env:JAVA_HOME='D:\Java\jdk-21'
$env:ANDROID_HOME='E:\Android\Sdk'
$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''
.\gradlew.bat clean
```

## 3. 构建命令

```powershell
# 单元测试（95 个用例）
.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest --console=plain

# Lint（有 error 会失败）
.\gradlew.bat :app:lintDebug :location-probe:lintDebug --console=plain

# 正式包 + 定位探针
.\gradlew.bat :app:assembleRelease :location-probe:assembleDebug --console=plain
```

产物：

- `app/build/outputs/apk/release/app-release.apk`
- `location-probe/build/outputs/apk/debug/location-probe-debug.apk`

## 4. 签名

`app/build.gradle` 会读取工程根目录的 `keystore.properties`（**不入库**）：

```properties
storeFile=keystore/route-workshop-release.jks
storePassword=********
keyAlias=routeworkshop
keyPassword=********
```

文件不存在时产出未签名 APK 且构建不失败。首次生成证书：

```powershell
New-Item -ItemType Directory -Force keystore | Out-Null
& "D:\Java\jdk-21\bin\keytool.exe" -genkeypair -v `
  -keystore keystore\route-workshop-release.jks -alias routeworkshop `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <口令> -keypass <口令> `
  -dname "CN=Route Workshop Local, O=Local Dev, C=CN"
```

校验签名与包信息：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging `
  app\build\outputs\apk\release\app-release.apk
```

> 交付的 APK 用的是本机自签名证书（本地测试用途）。换机器重新生成证书后，
> 无法覆盖安装旧包，需要先卸载。

## 5. 版本与依赖

版本目录：`gradle/libs.versions.toml`（禁止 `+` / 动态版本 / SNAPSHOT）。当前：

| 组件 | 版本 |
| --- | --- |
| AGP | 8.9.2 |
| Gradle | 8.11.1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 |
| MapLibre Android SDK | 13.7.1 |
| appcompat / material / lifecycle | 1.7.0 / 1.12.0 / 2.8.7 |
| JUnit / org.json（仅测试） | 4.13.2 / 20240303 |

## 6. 备份与恢复应用数据

```powershell
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
& $adb shell run-as org.localroute.app.debug ls files/routes
& $adb exec-out run-as org.localroute.app.debug tar c files/routes > routes-backup.tar
# 恢复
& $adb push routes-backup.tar /data/local/tmp/
& $adb shell "run-as org.localroute.app.debug tar x -f /data/local/tmp/routes-backup.tar"
```

（`run-as` 仅对 debuggable 包有效，正式包不可用。）

## 7. 常见构建噪声（可忽略）

- `WARNING: The option setting 'android.overridePathCheck=true' is experimental.`
- `SDK XML version 4` 警告（AGP 只认 3，build-tools 35 写 4）
- `[Incubating] Problems report ...`
- PowerShell 控制台把 UTF-8 显示成 GBK 乱码（文件本身没坏）
