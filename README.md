# 局域网闪传（LanFlash）

**手机之间、手机和电脑之间，不碰云端的文件互传。**

同一 Wi-Fi 下自动发现对方，直接传文本和文件，全程不经过任何服务器。

> Android 4.4+ ｜ 免费开源 ｜ 无需注册 ｜ 无广告 ｜ 无遥测 ｜ 数据不出局域网

## 功能

- 打开自动发现附近设备（NSD + 组播双通道，5 秒内互相出现）
- 互发文本和文件，**128KB 分块 + 断点续传 + SHA-256 校验**
- 首次连接弹确认卡片，拒绝后对方不再打扰——**信任关系只存在两台设备上**
- 大文件传输内存占用恒定，切后台由前台服务保活
- 手动输入 `IP:端口` 添加设备
- 深色 / 浅色跟随系统

## 构建

### 本地构建

需要 JDK 17 与 Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 云端构建

推送任意分支到 `main`/`master`，或在 Actions 页面手动触发 `Build APK`，
构建完成后在 workflow 的 Artifacts 里下载 `LanFlash-debug-apk`。

## 协议概览

自定义二进制帧协议（大端）：

```
int32 payloadLength | int8 frameType | payload
```

- `HELLO`/`HELLO_ACK`：握手与身份交换
- `TEXT`/`TEXT_ACK`：文本消息
- `FILE_OFFER`/`FILE_ACCEPT`/`FILE_REJECT`：文件协商（ACCEPT 携带已收块位图实现断点续传）
- `CHUNK`：文件数据块（int32 chunkIndex + 原始字节）
- `FILE_DONE`/`FILE_RESULT`：完成与 SHA-256 校验结果
- `TRUST_REQUEST`/`TRUST_DENIED`：首次连接信任确认
- `PING`/`PONG`、`CANCEL`、`BYE`

## 目录结构

```
app/src/main/java/com/lanflash/app/
├── core/   常量、事件总线、偏好、工具
├── model/  设备、消息、消息存储
├── net/    协议、发现（NSD/组播）、会话、传输、前台服务
└── ui/     主界面、聊天页、适配器
```

## License

MIT
