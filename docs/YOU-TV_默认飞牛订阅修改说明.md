# You-TV 默认使用飞牛 IPTV 订阅的修改说明

## 结论

现场联调使用的订阅地址为：

```text
http://192.168.71.37:8080/m3u
```

该地址由飞牛 `guovern/iptv-api` 提供，返回经过完整测速和黑名单过滤的直接播放地址。现场测试确认 You-TV 单源起播超时约 5 秒，而 NAS 本地 HLS 转推冷启动需要先生成 3 个分片，常常超过这个时间，因此本机不要把 `/hls/m3u` 设为默认订阅。

只有在播放器允许更长起播时间，或 NAS 已经提前把流预热时，才可调试 HLS 转推列表：

```text
http://192.168.71.37:8080/hls/m3u
```

## APK 中确认到的现状

反编译 `you-tv.apk` 后确认：

- URL 订阅保存到内部文件 `playlist-url.txt`，文本源保存到 `playlist-text.txt`。
- Manifest 已包含 `INTERNET` 权限、`ACCESS_NETWORK_STATE` 权限，并设置了 `usesCleartextTraffic="true"`，所以局域网 HTTP 无需再额外放行。
- 现有下载、解析、保存和“下载失败继续使用上次频道”的逻辑可以保留。

## 推荐改法

以下修改项按当前源码中的播放器、频道仓库、EPG 仓库和远程配置服务实施。

### 1. 改善多源自动切换，避免反复卡顿

当前 APK 已有 `SourceQualityEntity`、`BlockedSourceEntity` 和错误/波动计数，这是正确方向。建议切源策略满足：

- 起播超时：5 秒。
- 连续 8～10 秒未收到可渲染视频帧：判定该源失败。
- 60 秒内发生 2 次重新缓冲：该源本次会话降权并自动切到下一源。
- 用户手动长按标记错误源：永久封禁，直到用户在设置里解除。

示意代码：

```kotlin
private const val STARTUP_TIMEOUT_MS = 5_000L
private const val STALL_TIMEOUT_MS = 10_000L
private const val MAX_REBUFFERS_PER_MINUTE = 2

fun onPlaybackStalled(source: StreamSource) {
    qualityRepository.recordFailure(source.id, reason = "stall")
    playNextUsableSource()
}
```

如果使用 Media3/ExoPlayer，可同时监听：

```kotlin
Player.Listener.onPlayerError
Player.Listener.onPlaybackStateChanged
Player.Listener.onIsPlayingChanged
AnalyticsListener.onLoadError
AnalyticsListener.onPlaybackStateChanged
```

不要把一次 HTTP 连接成功、读取到 M3U8 文本或拿到首个 TS 包当成“稳定可播”。

### 2. 增加播放日志与网页导出

- 记录来源尝试、真实出画、首帧超时、持续缓冲、重复缓冲切源、播放器错误和手动屏蔽/恢复。
- 日志保留最近 7 天且最多 2000 条，包含发生时间、频道、来源、结果和具体原因。
- 远程配置网页顶部显示最近日志，并可导出包含完整播放地址的 JSON 文件。

### 3. 支持手动配置 gzip EPG

EPG 地址继续由用户在远程配置网页填写，不内置飞牛默认地址。下载 `.gz` EPG 时应自动解压，并且只有解析成功后才替换已有缓存。

## 构建后验证清单

1. 清除 App 数据或在全新模拟器安装新版 APK。
2. 断开飞牛网络后重启 App，应继续保留最后一次成功频道，而不是清空列表。
3. 恢复网络后点击“立即更新订阅”，应成功更新。
4. CCTV-1 每条候选源至少连续测试 10 秒；卡住的源应自动降权/切换。
5. 使用 `adb logcat` 确认没有 cleartext、解析、MediaCodec 或重复重缓冲错误。

## 飞牛端对应配置

本次已在飞牛上调整：

```ini
urls_limit = 3
open_history = False
open_full_speed_test = True
min_speed = 0.4
resolution_speed_map = 1280x720:0.3,1920x1080:0.6,3840x2160:1.2
speed_test_limit = 3
speed_test_timeout = 8
local_num = 3
subscribe_num = 3
open_realtime_write = False
update_startup = False
```

`open_realtime_write = False` 表示完整测速期间继续提供上一次完整列表，避免电视拿到只生成了一部分的频道。`update_startup = False` 表示容器重启时不重复执行约 11 分钟的完整测速；当前配置仍会按 `update_interval = 12` 每 12 小时自动更新。

黑名单新增：

```text
/rtp/
123.154.111.132
```

## 本次现场验证结果

- `guovern/iptv-api` 完整检查了 191 个待测速接口，首次完整更新耗时约 11 分钟。
- CCTV-1 从 7 条候选源中筛出 5 条有效源，并按上限只输出速度、分辨率达标的前 3 条。
- 最终订阅中 `/rtp/` 地址和超时主机 `123.154.111.132` 的数量均为 0。
- MuMu 中现有 You-TV 已切换到 `http://192.168.71.37:8080/m3u`；现场确认 `/hls/m3u` 的冷启动会触发 You-TV 约 5 秒的加载超时，所以最终采用严格筛选后的直接源。
- MuMu 实际播放 CCTV-1 超过 125 秒后仍正常出画面；飞牛侧 FFmpeg 对同一 HLS 地址连续拉流约 2 分钟并以退出码 0 正常结束。
- Docker 容器已重新启动，使上述筛选参数对后续定时更新持续生效。

修改前备份位于：

```text
/vol1/1000/Docker/iptv-api/config/backup-20260804-225014
```
