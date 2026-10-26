# 构建与签名说明

## 1. 环境（本机实测）

| 项 | 值 |
| --- | --- |
| OS | Windows 10/11（中文系统，控制台默认 GBK） |
| JDK（构建） | `D:\Java\jdk-21`（21.0.3）；`JAVA_HOME` 必须指向它 |
| Gradle | 8.11.1（`gradle/wrapper/gradle-wrapper.properties` 用腾讯镜像 `mirrors.cloud.tencent.com`） |
| Android SDK | `E:\Android\Sdk`（platform 35、build-tools 35.0.0、platform-tools 37.0.1） |
| AGP | 8.9.2 |
| 网络 | 直连 `dl.google.com` / `repo1.maven.org`；`services.gradle.org` 在本机不可达，故用镜像 |

## 2. Windows 路径坑（已通过迁移目录解决，保留记录）

**现状：工程位于纯 ASCII 路径 `E:\agri-run\route-workshop`，直接构建即可，不再需要 `subst`，
`gradle.properties` 里的 `android.overridePathCheck` 也已移除（2026-10-26 迁移）。**

迁移前工程在 `E:\农林快跑\route-workshop`（含中文），踩过的三个坑，供以后换机器时参考：

1. **AGP 默认拒绝非 ASCII 路径** → 当时靠 `android.overridePathCheck=true` 绕过。
2. **Gradle 测试 worker 在非 ASCII 路径下会 `ClassNotFoundException`**（已用最小工程复现：ASCII 路径通过，
   中文路径必挂）。表现为 `4 tests completed, 4 failed`，每条失败都是
   `java.lang.ClassNotFoundException: org.localroute.core.GeoMathTest`。调过 `-Dfile.encoding`、
   `JAVA_TOOL_OPTIONS`、`--no-daemon`，都无效。
3. **同一个工程用两个不同路径构建会再挂一次**：`this and base files have different roots`
   （`E:\...` 与 `R:\...` 的产物混在一起时，dex 转换无法处理），必须先 `clean`。

当时的临时解法是 `subst R: "E:\农林快跑"`，但它有两个后遗症：重启后映射消失、两条路径混用会触发第 3 个坑。
**最终解法是把目录改名成 ASCII（`E:\agri-run`）**，一劳永逸；`subst` 已删除。

## 3. 常用命令

```powershell
Set-Location E:\agri-run\route-workshop
$env:JAVA_HOME='D:\Java\jdk-21'
$env:ANDROID_HOME='E:\Android\Sdk'
$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''          # 本机代理已失效，直连更快

# 全部 JVM 单元测试（route-core 51 + simulation-core 16 + app 28 = 95）
.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest --console=plain

# Lint（abortOnError=true，有 error 会直接失败）
.\gradlew.bat :app:lintDebug :location-probe:lintDebug --console=plain

# 可安装的正式包（需要第 4 节的签名配置）
.\gradlew.bat :app:assembleRelease

# 调试包（包名会加 .debug 后缀，可与正式包共存）
.\gradlew.bat :app:assembleDebug

# 定位探针
.\gradlew.bat :location-probe:assembleDebug
```

产物位置：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`
- `location-probe/build/outputs/apk/debug/location-probe-debug.apk`
- Lint 报告：`app/build/reports/lint-results-debug.html` / `.txt`
- 测试报告：`<module>/build/reports/tests/...`

## 4. 签名

本仓库**不包含**任何私钥。首次在本机构建 release 时这样生成（一次即可）：

```powershell
New-Item -ItemType Directory -Force keystore | Out-Null
& "D:\Java\jdk-21\bin\keytool.exe" -genkeypair -v `
  -keystore keystore\route-workshop-release.jks -alias routeworkshop `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <你的口令> -keypass <你的口令> `
  -dname "CN=Route Workshop Local, O=Local Dev, C=CN"
```

再写 `keystore.properties`（位于工程根目录，已被 `.gitignore` 忽略）：

```properties
storeFile=keystore/route-workshop-release.jks
storePassword=<你的口令>
keyAlias=routeworkshop
keyPassword=<你的口令>
```

`app/build.gradle` 会**条件式**读取它：文件存在就签名，不存在就产出未签名包，构建不会因此失败。
交付用的 APK 用的就是本机自签名证书（本地测试用途，不是公开发布的正式证书）。

校验：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging `
  app\build\outputs\apk\release\app-release.apk
```

## 5. 安装与卸载

```powershell
adb devices -l
adb install -r app\build\outputs\apk\release\app-release.apk
adb install -r location-probe\build\outputs\apk\debug\location-probe-debug.apk
# 覆盖升级验证（T24）：同包名、同签名、更高 versionCode
```

卸载会删掉路线数据（存在 `files/routes/`）。备份方式（debug 包可直接 `run-as`）：

```powershell
adb shell run-as org.localroute.app.debug ls files/routes
adb exec-out run-as org.localroute.app.debug tar c files/routes > routes-backup.tar
```

## 6. 本机已知构建现象（不是错误）

- `WARNING: The option setting 'android.overridePathCheck=true' is experimental.` —— 预期警告。
- `SDK XML version 4` 相关警告 —— build-tools 35 写入的元数据版本，AGP 8.9.2 只读 3，属已知噪声。
- `[Incubating] Problems report is available at ...` —— Gradle 的常规提示。
- PowerShell 里 `Get-Content` 显示中文乱码 —— 控制台是 GBK，文件本身是 UTF-8（用
  `[System.IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)` 核对即可）。
