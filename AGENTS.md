# 项目协作约定

本文件只记录长期有效的项目结构、技术约束与协作方式。具体需求以当前 Issue / PR 为准；构建环境以本机配置为准。**不要把个人机器的绝对路径、账号或密钥写进仓库。**

## 项目结构

- `app/`：Android 主应用（首页、地图、课表、学习通、悬浮窗等）。
- `route-core/`：纯 Java 路线几何、按距离取点与运行状态机。
- `simulation-core/`：纯 Java 模拟位置会话。
- `location-probe/`：独立包名的定位探针，用来验证系统是否真的收到模拟位置。

## 技术约束（已对照代码核对）

- **坐标系统一 WGS-84**：内部几何、存储、GPX 与系统模拟位置都用 WGS-84（见 `route-core` 的 `GeoPoint`）。地图显示坐标只在适配器与输入边界转换（`ChinaShift` 仅用于 `map/` 与 `search/`），不要在业务层混用坐标系。
- **几何闭合、终点策略、速度模式分别表达**：`EndBehavior` 用 `forDraft` 与 `legacyForClosed` 两条路径区分新建与旧文件，迁移必须保留原行为，不能只改默认布尔值。
- **页面、悬浮窗、通知走同一套服务命令与快照**：统一经 `SimulationService` 的静态命令（`start/pause/resume/stop/setSpeedKmh`）与 `ACTION_*`，不要各自直改状态。
- **运行中改速用分段累计距离**：改速时把已走距离冻结进 offset 再开新分段，保证位置连续、不跳变。
- **课表与作业的数据源分开**：课表走教务导入（`JwImportActivity` → `StudyTimetableStore`），作业走学习通缓存（`StudyHomeworkCache`），不要互相替代或另造一套绑定。

## 构建与测试

- JDK 通过 `JAVA_HOME` 指定，Android SDK 通过工程根目录 `local.properties` 的 `sdk.dir` 指定；`local.properties`、`keystore.properties`、keystore 与高德 Key 都不入库。
- 使用仓库自带的 Gradle Wrapper（`./gradlew` 或 `gradlew.bat`），常用任务与产物见 [docs/BUILD.md](docs/BUILD.md)。
- 验证结论以实际执行的测试结果为准：测试数量从 `build/test-results/**/TEST-*.xml` 汇总，不复制旧数字；没有对应证据不写「已验证」。
- 工程与 SDK 路径尽量使用纯 ASCII；非 ASCII 路径可能导致 Gradle 测试 worker 或模拟器启动异常，必要时临时使用盘符映射，但不要把映射或本机路径写进仓库。

## 界面变更

- 页面布局、颜色、字号、间距使用 `res/values` 中的现有令牌与字符串，不在布局里硬编码文案或尺寸。
- 影响界面的 PR 附上代表性截图，至少覆盖改动后的主要状态；空态与深色模式如有变化一并附上。