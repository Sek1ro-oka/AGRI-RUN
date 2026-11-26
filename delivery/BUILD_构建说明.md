# 构建说明（可复现）

本文件面向拿到源码后自行构建的人。环境版本基线见 [docs/environment.md](../docs/environment.md)，完整命令与签名说明见 [docs/BUILD.md](../docs/BUILD.md)。

## 1. 前置

| 需要 | 版本 | 备注 |
| --- | --- | --- |
| JDK | 17+（实测 21.0.3） | Gradle 8.11.1 **不支持** JDK 25 |
| Android SDK | platform 35、build-tools 35.0.0、platform-tools | `local.properties` 的 `sdk.dir` 指向 SDK 根目录 |
| Gradle | 8.11.1（wrapper 自带） | 首次构建会下载发行包 |
| 网络 | 能访问 `dl.google.com`、Maven Central | 首次构建需要下载依赖 |

`local.properties` 与 `keystore.properties` 都不入库，不要把自己的机器路径提交到仓库。

## 2. 路径

工程与 SDK 路径请使用纯 ASCII。非 ASCII 路径在 Windows 上可能导致 Gradle 测试 worker `ClassNotFoundException`；同一工程混用映射盘与真实盘还可能触发 `this and base files have different roots`。必要时临时使用盘符映射，但不要把映射写进仓库；出现路径混用错误时先 `clean`，之后固定使用一个路径。

## 3. 构建命令

macOS / Linux：

```bash
export JAVA_HOME=/path/to/jdk

# 单元测试
./gradlew :route-core:test :simulation-core:test :app:testDebugUnitTest --console=plain

# Lint（有 error 会失败）
./gradlew :app:lintDebug :location-probe:lintDebug --console=plain

# 正式包 + 定位探针
./gradlew :app:assembleRelease :location-probe:assembleDebug --console=plain
```

Windows PowerShell 用 `.\gradlew.bat` 替换 `./gradlew`。

产物：

- `app/build/outputs/apk/release/app-release.apk`
- `location-probe/build/outputs/apk/debug/location-probe-debug.apk`

## 4. 签名

`app/build.gradle` 会读取工程根目录的 `keystore.properties`（**不入库**）：

```properties
storeFile=keystore/release.jks
storePassword=********
keyAlias=<别名>
keyPassword=********
```

文件不存在时产出未签名 APK 且构建不失败。首次生成证书：

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/release.jks -alias <别名> \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <口令> -keypass <口令> \
  -dname "CN=UNLOOP Release, O=Local Dev, C=CN"
```

`keytool` 在 `JAVA_HOME/bin` 下；不在 PATH 时用 `$JAVA_HOME/bin/keytool`（Windows 为 `$env:JAVA_HOME\bin\keytool.exe`）。

校验签名与包信息：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe" dump badging `
  app\build\outputs\apk\release\app-release.apk
```

> 正式发布必须保持同一把签名密钥，并离线备份 keystore 与口令。换机器重新生成证书后无法覆盖安装旧包，需要先卸载，应用私有数据会丢失。

## 5. 版本与依赖

依赖版本以 `gradle/libs.versions.toml` 为唯一来源（禁止 `+`、动态版本与 SNAPSHOT）。

## 6. 备份与恢复应用数据

```bash
adb shell run-as org.localroute.app.debug ls files/routes
adb exec-out run-as org.localroute.app.debug tar c files/routes > routes-backup.tar
# 恢复
adb push routes-backup.tar /data/local/tmp/
adb shell "run-as org.localroute.app.debug tar x -f /data/local/tmp/routes-backup.tar"
```

`run-as` 仅对 debuggable 包有效，正式包不可用。

## 7. 常见构建噪声（可忽略）

- `[Incubating] Problems report ...`
- `SDK XML version 4` 警告（AGP 8.9.2 只认 3，build-tools 35 写 4）