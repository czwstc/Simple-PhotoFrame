# Simple-PhotoFrame

**把你的安卓平板变成老年人喜爱的电子相框。** 无需网络，也不用注册账号：相框会开一个自己的 Wi‑Fi 热点，家人用手机扫码就能把照片传进去。

![相框轮播](docs/screenshots/frame-slideshow.jpg)

## 为什么做它

- **老人不用学**：开机直接进入相框，没有相册、文件夹之类需要设置的东西，按 Home 键也会回到相框。
- **家里没网也能用**：完全离线，不依赖云服务和任何账号。
- **子女传照片很简单**：手机扫两个二维码就能上传、浏览、删除照片，手机上不用装 App。

## 功能

- **开机即相框**：注册为桌面（HOME）。默认效果是「柔和推拉」，照片比例和屏幕不一致时，空白处用模糊背景填满。
- **轮播设置**（点右下角的半透明 ⚙ 按钮打开）
  - 效果：柔和推拉、淡入淡出、左右滑动、无动画、随机
  - 间隔：5 秒到 15 分钟
  - 顺序：随机、最新优先、最早优先
  - 显示：完整显示（模糊背景）或铺满屏幕
  - 左右滑动屏幕可以手动翻页
- **时钟和日期**：显示在左下角，可以关闭。
- **定时息屏 / 亮屏**：例如 23:00 关屏、07:00 自动亮屏。夜里有人按电源键看照片，5 分钟没有操作后会再次息屏。
- **自动回到相框**：每 5 分钟检查一次，如果相框被别的界面（比如系统设置）挡住了，就自动切回来。屏幕关着时不会唤醒，刚离开的人也至少有 3 分钟的时间。
- **手机传照片**：相框开启**无密码热点**，屏幕上显示 Wi‑Fi 二维码和网址二维码。
  - 网页支持多选上传（带进度）、浏览大图、批量删除
  - HEIC 照片自动转成 JPEG，保留拍摄时间，方向正确
  - 同一张照片重复上传会自动跳过
  - 20 分钟没有操作自动结束，也可以在网页或相框上点「完成」结束
- **不需要任何权限**：照片存放在应用自己的目录里。

## 截图

| 设置面板 | 定时息屏 |
|---|---|
| ![设置面板](docs/screenshots/frame-settings.jpg) | ![定时息屏](docs/screenshots/frame-settings-sleep.jpg) |

| 第一次打开 | 相框上的上传二维码 |
|---|---|
| ![空相框](docs/screenshots/frame-empty.jpg) | ![上传二维码](docs/screenshots/frame-transfer-qr.jpg) |

手机上的管理页面（上传、选择删除、查看大图）：

![手机页面](docs/screenshots/phone-pages.jpg)

## 怎么传照片

1. 点相框右下角的 ⚙，再点「**传输照片**」。
2. 用手机相机扫左边的二维码，连接 Wi‑Fi `PhotoFrame-XXXX`（无密码）。
3. 再扫右边的二维码打开上传页面，点「上传照片」并选择照片。
4. 传完后点网页底部的「完成传输」或相框上的「完成」。相框会关闭热点，回到照片播放。

> - iPhone 的照片如果存在 iCloud 里（开启了「优化 iPhone 储存空间」），选择后手机要先下载原图，请保持移动数据开启。
> - 少数手机连上没有互联网的 Wi‑Fi 后会优先走移动数据。如果页面打不开，可以试试暂时关闭移动数据。

## 安装

### 编译

需要 JDK 17 和 Android SDK（platform 34、build-tools 34）：

```bash
./gradlew :app:assembleDebug
```

APK 在 `app/build/outputs/apk/debug/Simple-PhotoFrame-1.0.0-debug.apk`。release 签名见 `keystore.properties_sample`。

### 安装并设为桌面

```bash
adb install -r app/build/outputs/apk/debug/Simple-PhotoFrame-1.0.0-debug.apk
```

```bash
adb shell cmd package set-home-activity com.zweichen.photoframe/.frame.FrameActivity
```

也可以在系统设置 → 应用 → 默认应用 → 桌面里选择「相框」。

### 设备要求

- Android 8.0（API 26）及以上，横屏平板效果最好。
- **推荐有 root**（很多平板、开发板的工厂固件自带 `su`）。有 root 时可以开无密码热点、真正关闭屏幕、更可靠地切回相框。
- 没有 root 也能用：热点改用系统的「本地热点」（SSID 和密码随机，Wi‑Fi 二维码里自带密码）；定时息屏改为显示黑屏并把亮度调到最低。
- 已在 RK3566 / Android 11 / 1280×800 的平板上实测。

## 实现说明

- **照片目录**：`Android/data/com.zweichen.photoframe/files/photos`。
- **热点**：有 root 时，通过 `app_process` 以 root 身份调用系统 Wi‑Fi 服务开启开放热点。这样 SSID 准确、关闭了系统 10 分钟自动关热点，并使用出厂 MAC。失败时退回 `cmd wifi start-softap`，二维码里使用实际广播的名称。
- **Wi‑Fi 恢复**：不支持 STA+AP 并发的芯片，传输期间会暂时断开原来的 Wi‑Fi，结束后自动恢复。
- **上传服务**：基于 NanoHTTPD，端口 8080，网页是单个 HTML 文件，不依赖任何 CDN。
  - `PUT /api/upload?name=` 上传，请求体是原始文件字节
  - `GET /api/photos` 照片列表，`GET /api/thumb/<文件名>` 缩略图，`GET /api/photo/<文件名>` 原图
  - `POST /api/delete` 删除，请求体 `{"names": [...]}`
  - `POST /api/finish` 结束传输
- **诊断日志**：每次传输的请求、上传结果和网页端事件都记录在 `files/transfer.log`，方便排查某台手机传不上的问题。

## 调试

debug 版本支持两个只在调试时生效的参数。不开热点，直接在当前局域网启动上传服务：

```bash
adb shell "su 0 am start -n com.zweichen.photoframe/.transfer.TransferActivity --ez skip_hotspot true"
```

把空闲自动结束的时间缩短到 90 秒：

```bash
adb shell "su 0 am start -n com.zweichen.photoframe/.transfer.TransferActivity --ei idle_timeout_sec 90"
```

查看传输日志：

```bash
adb shell run-as com.zweichen.photoframe cat files/transfer.log
```

## English

Simple-PhotoFrame turns an Android tablet into an offline digital photo frame for elderly users. It boots straight into a slideshow, with nothing to set up. To add photos, the frame opens its own password-less Wi‑Fi hotspot: scan the QR codes on screen with a phone, then upload, browse or delete photos in the browser. Other features are transition effects, a clock overlay, scheduled screen off/on and an automatic return to the frame. Root is recommended, for the open hotspot and real screen off; without root it falls back to the system local-only hotspot.

## 许可

本项目由 [Simple Gallery](https://github.com/SimpleMobileTools/Simple-Gallery) 改造而来，沿用 [GPL-3.0](LICENSE) 许可。
