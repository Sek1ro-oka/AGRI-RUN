# 示例路线（合成坐标，不是真实地点）

这两个文件只是**格式示例**，坐标都在 `(0, 0)` 附近的几内亚湾洋面上，跟任何学校、操场、跑道都没有关系。

> 任务书明确要求：没有确认 B 操场的真实坐标之前，不得预置坐标、也不许把地图中心当成 B 操场。
> 所以这里刻意用肉眼一看就知道是假的数值。想跑真实的圈，请在地图上自己点，或者用「手动输入」填真实经纬度。

## 文件

| 文件 | 形状 | 单圈长度（约） | 用途 |
| --- | --- | --- | --- |
| `route-sample-square.json` | 边长约 22 m 的方形 | ≈ 89 m | 演示最小闭环、T03/T04 手工验证 |
| `route-sample-triangle.json` | 底边约 33 m 的三角形 | ≈ 78 m | 演示非直角形状与闭合边 |

9 km/h 跑完一圈约 30 秒，适合在真机上快速看到“完成 1 圈后停下”。

## 格式

```json
{
  "schemaVersion": 1,
  "id": "sample-square",
  "name": "示例方形路线（合成坐标）",
  "coordinateSystem": "WGS84",
  "closed": true,
  "createdAt": "2026-10-26T12:00:00Z",
  "updatedAt": "2026-10-26T12:00:00Z",
  "points": [
    { "latitude": 0.0, "longitude": 0.0 },
    { "latitude": 0.0, "longitude": 0.0002 }
  ]
}
```

- `latitude` 在前、`longitude` 在后；WGS-84；`closed` 必须为 `true`（本项目路线恒闭合）。
- 单点草稿可以保存，但只有 ≥ 2 个点、单圈 ≥ 1 米才能开始运行。
- 文件名建议与 `id` 一致，放在应用的 `files/routes/` 目录里。

## 放到设备上（可选）

正式包的数据目录不可直接写入；debug 包可以用 `run-as`：

```powershell
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
& $adb push samples\route-sample-square.json /data/local/tmp/
& $adb shell run-as org.localroute.app.debug mkdir -p files/routes
& $adb shell "run-as org.localroute.app.debug cp /data/local/tmp/route-sample-square.json files/routes/"
```

更简单的办法：打开 App →「手动输入」→ 按上面 4 个点的经纬度填进去 → 保存。
