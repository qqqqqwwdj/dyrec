# 直播录制（dyrec）

Android 原生直播录制工具。核心能力：

1. **后台识别剪贴板链接**：无障碍服务在剪贴板变化时读取，正则识别抖音直播链接，自动入列待处理任务。
2. **保活与进度**：前台服务（通知栏常驻）+ 悬浮窗，实时显示录制进度与已录大小。
3. **多档清晰度**：解析层返回原画 / 蓝光 / 高清等直链，**默认优先原画**。
4. **停止即可播**：边下边写，停止时按 FLV tag / TS 188 字节包边界截断，产物是结构完整、可直接播放的视频，而不是半截损坏文件。

## 目录

```
app/                      Android 应用（Kotlin）
  src/main/java/com/dsh/dyrec/
    data/                 任务模型、持久化、内存仓库
    record/StreamRecorder.kt   录制引擎（直链 + HLS，边界截断）
    resolve/              链接解析、解析服务客户端
    service/              前台录制服务、无障碍剪贴板服务
    ui/                   通知、悬浮窗
    MainActivity.kt       界面
server/                   自建解析服务模板（Node）
.github/workflows/        云端构建 APK
```

## 构建

代码推到 GitHub 后，`build-apk` 工作流自动出包：
- Actions -> build-apk -> 运行结束在 Artifacts 下载 `dyrec-apk`
- 解压得到 `app-debug.apk`，安装到手机

本地有 Android SDK/Gradle 时可 `gradle assembleDebug`。

## 使用

1. 安装后依次点①②③④授予：通知、悬浮窗、无障碍、忽略电池优化。
2. 复制抖音直播分享链接，打开 App 会自动出现在任务列表（PENDING）。
3. 点「录制」即可开始（需先在底部配置解析服务地址，或直接粘贴 `.flv/.ts/.m3u8` 直链）。
4. 录制中可在 App 内或通知栏停止；停止后点「打开」播放。

## 关于解析（重要）

- App 内**不包含**抖音签名破解。
- `server/` 是解析服务模板：已实现「分享链接 → 展开短链 → 提取 room_id」，`fetchStreams()` 里的签名部分留给你补。
- 你也可以把「解析服务地址」填成任何符合契约的第三方接口：
  `GET {endpoint}?url={编码后的分享链接}` → `{"streams":[{"quality":"原画","url":"..."}]}`
- 想让整条链路先跑通：给模板服务设置环境变量 `DEMO_STREAM=<任意可访问的 flv/ts 直链>`，即可体验完整录制 + 截断流程。

## 录制格式说明

- 直链 FLV → 产物 `.flv`
- 直链 / HLS 切片 TS → 产物 `.ts`
- 两种都是流式容器，截断在边界处即天然可播；如需 MP4，可后续用 `ffmpeg -c copy` 无损转封装（未内置）。
