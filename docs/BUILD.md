# 构建与签名说明

## 1. 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 17+（AGP 8.9.2 要求；仓库实测使用 JDK 21） |
| Android SDK | platform 35、build-tools 35.0.0、platform-tools |
| Gradle | 8.11.1（仓库自带 wrapper） |
| 网络 | 首次构建需能访问 `dl.google.com` 与 Maven Central |

本机路径不写入仓库：JDK 用 `JAVA_HOME` 指定，Android SDK 在工程根目录的 `local.properties` 中用 `sdk.dir` 指定（该文件不入库）。工程与 SDK 路径建议使用纯 ASCII；非 ASCII 路径可能导致 Gradle 测试 worker 或模拟器启动异常，必要时临时使用盘符映射，但不要把映射或本机路径写进仓库。

## 2. 常用命令

macOS / Linux：

```bash
export JAVA_HOME=/path/to/jdk

# 全部 JVM 单元测试
./gradlew :route-core:test :simulation-core:test :app:testDebugUnitTest --console=plain

# Lint（abortOnError=true，有 error 会直接失败）
./gradlew :app:lintDebug :location-probe:lintDebug --console=plain

# 调试包（包名会加 .debug 后缀，可与正式包共存）
./gradlew :app:assembleDebug

# 可安装的正式包（需要第 3 节的签名配置）
./gradlew :app:assembleRelease

# 定位探针
./gradlew :location-probe:assembleDebug
```

Windows PowerShell：

```powershell
$env:JAVA_HOME='<JDK 路径>'

.\gradlew.bat :route-core:test :simulation-core:test :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug :location-probe:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :location-probe:assembleDebug
```

需要代理时按本机环境设置 `HTTP_PROXY` / `HTTPS_PROXY`，不要提交个人代理配置。

产物与报告：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`
- `location-probe/build/outputs/apk/debug/location-probe-debug.apk`
- Lint 报告：`app/build/reports/lint-results-debug.html` / `.txt`
- 测试报告：`<module>/build/reports/tests/...`；测试数量从 `build/test-results/**/TEST-*.xml` 汇总

## 3. 签名

本仓库**不包含**任何私钥。首次在本机构建 release 时生成（一次即可）：

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/release.jks -alias <别名> \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <你的口令> -keypass <你的口令> \
  -dname "CN=UNLOOP Release, O=Local Dev, C=CN"
```

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force keystore | Out-Null
keytool -genkeypair -v `
  -keystore keystore\release.jks -alias <别名> `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <你的口令> -keypass <你的口令> `
  -dname "CN=UNLOOP Release, O=Local Dev, C=CN"
```

`keytool` 在 `JAVA_HOME/bin` 下；不在 PATH 时用 `$env:JAVA_HOME\bin\keytool.exe`。

再写 `keystore.properties`（位于工程根目录，已被 `.gitignore` 忽略）：

```properties
storeFile=keystore/release.jks
storePassword=<你的口令>
keyAlias=<别名>
keyPassword=<你的口令>
```

`app/build.gradle` 会**条件式**读取它：文件存在就签名，不存在就产出未签名包，构建不会因此失败。公开包不内置高德 Key，可用 `-PamapKey=<值>` 覆盖 `local.properties` 里的 Key。

keystore 与 `keystore.properties` 必须离线备份，不要提交到仓库。**发布后不要更换签名 key**：换 key 后旧版本无法覆盖升级，用户只能卸载重装，应用私有数据会丢失。历史上 v0.9.4 及更早版本使用过另一把证书，已经无法覆盖升级到 v0.9.5+；后续版本一律沿用当前 key。

校验：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging `
  app\build\outputs\apk\release\app-release.apk
```

## 4. 安装与卸载

```bash
adb devices -l
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r location-probe/build/outputs/apk/debug/location-probe-debug.apk
# 覆盖升级验证：同包名、同签名、更高 versionCode
```

卸载会删掉应用私有数据（路线、课表、自建作业等）。debug 包可直接用 `run-as` 备份：

```bash
adb shell run-as org.localroute.app.debug ls files/routes
adb exec-out run-as org.localroute.app.debug tar c files/routes > routes-backup.tar
```

## 5. 常见构建现象（不是错误）

- `[Incubating] Problems report is available at ...` —— Gradle 的常规提示。
- `SDK XML version 4` 相关警告 —— build-tools 35 写入的元数据版本，AGP 8.9.2 只读 3，属已知噪声。