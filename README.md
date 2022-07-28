# DLNA Android

Android DLNA 音频接收示例，适用于将 Android 设备作为音乐接收、播放和信息显示终端。

## 功能

- 接收 DLNA 发送的音乐并播放；
- 显示歌曲名称、歌手和专辑名称；
- 显示 DLNA 元数据中的专辑封面；
- 显示当前播放进度和音频总时长；
- 支持播放、暂停、停止和拖动定位；
- 同步 DLNA 播放状态（`LastChange`）；

## 示例

`app`启动 DLNA DMR 接收服务，接收投送端发送的音频 URI 和 DIDL-Lite 元数据，使用 Android `MediaPlayer` 播放，并在界面显示封面、歌名、歌手、专辑和播放进度。

接收流程如下：

```text
SetAVTransportURI(uri, metadata)
        -> AVTransportController
        -> IDLNARenderControl.cast()
        -> MainActivity 解析 DIDL-Lite
        -> MediaPlayer 播放
        -> XML 界面更新媒体信息和进度
```

手机和 DLNA 投送端需要处于同一个局域网，媒体地址和封面地址必须能被手机访问。Demo 默认允许 HTTP 明文地址，以兼容常见的局域网 DLNA 资源。

## 技术来源

本项目的 DLNA 源码基于 [devin1014/DLNA-Cast](https://github.com/devin1014/DLNA-Cast) 复制、移植和适配，底层协议栈使用 [4thline/Cling](https://github.com/4thline/cling)。

没有直接依赖 `devin1014/DLNA-Cast` 的二进制库，是因为上游默认的 DMR 播放流程会启动 `DLNARendererActivity`，并将播放绑定到示例 `VideoView`。本项目需要让宿主 App 接管播放，所以保留源码并增加回调接入点。

## 相对来源项目的修改

### 播放入口对齐宿主播放器

上游收到 `SetAVTransportURI` 后启动 `DLNARendererActivity`；本项目改为调用：

```java
void cast(String currentURI, String currentURIMetaData);
```

宿主可以在回调中接入自己的 `MediaPlaybackService`、播放队列或其他播放器。本 Demo 使用 `MediaPlayer` 接管 URI 播放。

### 播放状态和生命周期

- `DLNARendererService` 暴露 AVTransport 的 `LastChangeAwareServiceManager`，方便宿主同步播放状态；
- 增加 `startService()` 和 `stopService()`，方便 App 控制 DMR 服务生命周期；
- Demo 将播放、暂停、停止等状态回传为 `LastChange` 事件。

### 兼容性处理

- `AVTransportController` 的初始状态设置为 `PLAYING`，兼容 QQ 音乐快速调用 `getTransportInfo` 时暂时返回 `NO_MEDIA_PRESENT` 的情况；
- DMS 增加 `ContentResourceServlet.ResourceServlet` 到 Jetty `/*` 的映射，用于提供媒体资源；
- 为当前 Android Gradle Plugin 和 JitPack 增加多模块发布配置、`javax.servlet-api` 依赖和重复资源处理。这些属于构建适配，不改变 DLNA 协议逻辑。

## 模块

| 模块 | 作用 |
| --- | --- |
| `:dlna:dlna-core` | Cling、网络和通用工具 |
| `:dlna:dlna-dmc` | DLNA 控制端 |
| `:dlna:dlna-dmr` | DLNA 接收/渲染端 |
| `:dlna:dlna-dms` | DLNA 媒体服务器 |
| `:dlna` | 聚合 library |
| `:app` | 接收播放 Demo |
