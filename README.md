# you-tv

一个面向 Android TV 和手机横屏测试的 IPTV 播放应用。

## 特性

- Android 10+，Compose UI。
- 支持 TXT、M3U、JSON 频道源。
- 支持频道分组、收藏、多源播放、EPG、代理、开机启动和远程配置。
- 支持 IPv4、IPv6、HTTPS 和域名视频地址。
- 支持 GB18030/GBK 频道源文本导入，兼容常见中文 IPTV 列表。
- 地址源模式下，应用启动会自动更新订阅；文本源模式下使用本地保存内容。

## 频道源示例

```text
🕘️更新时间,#genre#
2026-06-22 10:53:07,http://example.com/live/1.m3u8

📺央视频道,#genre#
CCTV-1,http://127.0.0.1/live/1.m3u8
CCTV-5+,http://[2409:8087:1a01:df::7005]:80/live/index.m3u8
```

`更新时间` 分组会写入设置页，不作为频道展示。`无结果频道` 分组会被忽略。

## 操作

- 遥控器上/频道+：上一频道。
- 遥控器下/频道-：下一频道。
- 确认/Enter：打开频道列表或执行选项。
- 左键：节目单。
- 右键、菜单、设置、书签、帮助：设置。
- 返回/Escape：关闭浮层或退出。
- 节目单内左/右：切换当前频道的播放源。
- 手机左滑/右滑：切换频道。
- 手机上滑/下滑：调整媒体音量。
- 手机单击：显示频道信息。
- 手机长按：打开节目单。
- 手机左侧双击：频道列表。
- 手机右侧双击：设置。

## 构建

构建环境：

- JDK 21
- Android SDK 35
- Gradle Wrapper

```shell
./gradlew assembleRelease -x test -x lint
```

本地 release 签名使用根目录的 `keystore.properties`，该文件不会提交到 Git。没有本地签名配置时，release 产物可由 CI 或外部签名流程处理。

最终 APK 文件名：

```text
app/build/outputs/apk/release/you-tv.apk
```

## 下载与发布

最新版 APK 请到 [GitHub Releases](../../releases) 下载。

在 Release 页面的 Assets 区域下载 `you-tv.apk`。`Source code (zip)` 和 `Source code (tar.gz)` 是 GitHub 自动生成的源码压缩包，不是 Android 安装包。

第一次使用自动发布前，需要在 GitHub 仓库的 `Settings -> Secrets and variables -> Actions` 里添加这些 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE` | 发布签名文件的 Base64 内容 |
| `STORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |

Windows PowerShell 生成 `KEYSTORE` 内容：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("youtv-release.keystore")) | Set-Clipboard
```

发布新版本时，更新 `CHANGELOG.md` 和 `version.json`，然后推送版本标签：

```shell
git tag v1.0.1
git push origin v1.0.1
```

GitHub Actions 会自动构建、签名并发布 `you-tv.apk`。
