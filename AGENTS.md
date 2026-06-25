# you-tv 项目导览

这份文档给 AI 和后续维护者快速定位功能用。遇到修改需求时，先看这里，再读对应源码。默认不要改构建产物、签名文件和本地配置。

## 项目定位

- `you-tv` 是 Android 10+ 的 IPTV 播放应用。
- UI 使用 Kotlin + Compose，播放器核心是 Media3。
- 包名和 namespace 是 `com.youtv.app`。
- 最终 APK 文件名固定为 `you-tv.apk`。

## 架构入口

- 应用入口：`app/src/main/java/com/youtv/app/ComposeMainActivity.kt`
  - 创建播放器、启动 Compose UI、处理本地文件导入、控制远程配置服务生命周期。
- Application：`app/src/main/java/com/youtv/app/YouTvApplication.kt`
  - 初始化全局容器。
- 依赖容器：`app/src/main/java/com/youtv/app/AppContainer.kt`
  - 统一创建数据库、Repository、DataStore、EPG 等核心对象。
- 开机启动：`app/src/main/java/com/youtv/app/BootReceiver.kt`
  - 读取设置后决定是否启动应用。

## 功能在哪改

| 想改的功能 | 主要文件 | 注意事项 |
| --- | --- | --- |
| 播放主界面、频道侧栏、设置面板、节目单、遥控器按键 | `app/src/main/java/com/youtv/app/ui/TvApp.kt` | 保持遥控器和手机触控都可用；浮层打开时不要误触播放页全局手势。 |
| 手机滑动、单击、双击、长按 | `app/src/main/java/com/youtv/app/ui/TouchGestureLayer.kt` | 左侧双击是频道列表，右侧双击是设置；上下滑动调媒体音量。 |
| UI 状态、导入、设置更新、频道选择 | `app/src/main/java/com/youtv/app/ui/MainViewModel.kt` | UI 通过状态读取数据；不要让 UI 直接写数据库或设置。 |
| 播放、多源切换、IPv6、失败自动切源 | `app/src/main/java/com/youtv/app/player/PlayerController.kt` | 不要手工按冒号拆 URL；IPv6 地址必须保留方括号；单源失败后再尝试同频道下一源。 |
| TXT/M3U/JSON 频道解析 | `app/src/main/java/com/youtv/app/domain/playlist/PlaylistParser.kt` | 同组同名频道合并为多源；`更新时间` 分组只保存时间；`无结果频道` 分组不展示。 |
| 中文源文本编码兼容 | `app/src/main/java/com/youtv/app/data/PlaylistTextDecoder.kt` | 文件和订阅内容先按 UTF-8 严格解码，失败再按 GB18030/GBK 解码。 |
| 频道库、收藏、最近成功来源 | `app/src/main/java/com/youtv/app/data/repository/ChannelRepository.kt` | 导入成功后整体替换频道库；收藏和成功来源要尽量迁移保留。 |
| Room 表结构和 DAO | `app/src/main/java/com/youtv/app/data/db/Entities.kt`、`app/src/main/java/com/youtv/app/data/db/ChannelDao.kt` | 改表结构时同步处理迁移或清晰说明兼容策略。 |
| 设置和 DataStore | `app/src/main/java/com/youtv/app/data/repository/SettingsRepository.kt` | 新设置项要有默认值；远程配置页面和设置面板需要同步。 |
| EPG 解析与缓存 | `app/src/main/java/com/youtv/app/domain/epg/EpgParser.kt`、`app/src/main/java/com/youtv/app/data/repository/EpgRepository.kt` | 保持频道名匹配逻辑稳定，避免已有节目单失效。 |
| 远程配置 API | `app/src/main/java/com/youtv/app/server/RemoteConfigServer.kt` | 服务只应在设置页打开时运行；注意请求体大小、方法和 Content-Type 限制。 |
| 远程配置网页 | `app/src/main/res/raw/index.html` | 页面内文件上传也要兼容 GB18030；地址源和文本源只生效一个。 |
| 默认频道源样例 | `app/src/main/res/raw/channels.txt` | 示例可包含 IPv6，但不要放真实敏感 Token。 |
| 网络、代理、TLS | `app/src/main/java/com/youtv/app/requests/HttpClient.kt` | 不要恢复信任所有证书；用户视频源允许明文 HTTP。 |
| 数据模型 | `app/src/main/java/com/youtv/app/domain/model/Models.kt` | 频道、来源、节目单、导入报告等公共模型在这里。 |
| 构建、包名、版本、APK 名 | `app/build.gradle.kts`、`version.json` | 包名保持 `com.youtv.app`；版本当前为 `1.0.0`；APK 输出名保持 `you-tv.apk`。 |

## 常见修改路线

- 改频道列表样式：先看 `TvApp.kt` 的 `ChannelDrawer` 和 `FocusableRow`。
- 改设置页：先看 `TvApp.kt` 的 `SettingsPanel`，再看 `SettingsRepository.kt` 是否需要新字段。
- 改节目单或来源按钮：先看 `TvApp.kt` 的 `ProgramPanel` 和 `SourceChip`，切源最终调用 `PlayerController.selectSource()`。
- 改导入规则：先看 `PlaylistParser.kt`，如果涉及编码再看 `PlaylistTextDecoder.kt`。
- 改订阅地址自动更新：看 `MainViewModel.updateFromUrl()` 和 `SettingsRepository.sourceMode`。
- 改远程网页字段：同时改 `RemoteConfigServer.kt` 的接口和 `res/raw/index.html`。
- 改播放失败策略：看 `PlayerController.tryNextSource()`，不要改变 IPv4/IPv6/域名按来源顺序播放的原则。

## 不要随便碰

- 不要提交 `keystore.properties`、`*.keystore`、`*.jks`、`local.properties`。
- 不要提交 `app/build/`、`build/`、`.gradle/`、`.workbuddy/`。
- 不要把真实视频源 Token、证书密码或本机私有路径写入文档或源码。
- 不要把 IPv6 URL 转成非标准格式。
- 不要重新引入旧包名或旧应用名。

## 验证

常用 release 构建命令：

```shell
./gradlew assembleRelease -x test -x lint
```

Windows PowerShell 下也可以用：

```powershell
.\gradlew.bat assembleRelease -x test -x lint
```

APK 输出位置：

```text
app/build/outputs/apk/release/you-tv.apk
```

关键验收点：

- 包名是 `com.youtv.app`。
- 版本是 `1.0.0`。
- 最低系统是 Android 10 / SDK 29。
- 应用名是 `you-tv`。
- 本地导入、地址源自动更新、播放、多源切换、EPG、远程配置页面都能打开。
