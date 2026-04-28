# 📡 Android 局域网文件服务器

一个轻量级 Android App，可将手机上的任意文件夹通过 HTTP 协议共享给局域网内的其他设备，在浏览器中即可浏览文件和下载。

## ✨ 功能特性

- **一键启动** HTTP 文件服务器，无需 root
- **任意文件夹共享**，支持快速选择常用目录或手动输入路径
- **美观的 Web 界面**，支持目录树浏览、文件类型图标、文件大小/时间显示
- **面包屑导航**，轻松跳转父目录
- **文件下载**，浏览器直接下载
- **媒体预览**，图片、视频、音频、PDF 可直接在浏览器预览
- **前台服务**，切换到后台不会被杀死
- **安全防护**，防止路径穿越攻击
- **实时修改**，服务运行中可随时更换共享文件夹

## 🔧 技术栈

| 组件 | 说明 |
|------|------|
| NanoHTTPD 2.3.1 | 嵌入式 HTTP 服务器 |
| Android Foreground Service | 后台持续运行 |
| Material Design 3 | UI 组件 |
| minSdk 24 (Android 7.0) | 支持绝大多数设备 |

## 📁 项目结构

```
FileServer/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/fileserver/
│   │   │   ├── FileHttpServer.java      # 核心HTTP服务器（NanoHTTPD）
│   │   │   ├── HttpServerService.java   # 前台服务
│   │   │   └── MainActivity.java        # 主界面
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── drawable/ (bg_gradient, card_bg)
│   │   │   └── values/ (strings, themes)
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 🚀 编译方式

### 方法一：Android Studio（推荐）

1. 安装 [Android Studio](https://developer.android.com/studio)（Hedgehog 或更新版本）
2. 打开 `FileServer` 目录
3. 等待 Gradle 同步完成
4. 连接安卓手机（开启 USB 调试），点击 ▶ Run
5. 或 Build → Generate Signed APK 生成 APK 文件

### 方法二：命令行构建

```bash
cd FileServer
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`

> **注意**：首次构建需要下载 Gradle 和依赖，请确保网络畅通（或配置国内镜像）

## 📱 使用方法

1. **安装 APK** 到 Android 手机
2. **授予权限**：
   - Android 11+：打开「所有文件访问权限」（设置 → 隐私 → 文件和媒体）
   - Android 10 及以下：弹框授权存储权限
3. **选择文件夹**：点击「选择」按钮，选择要共享的目录
4. **设置端口**：默认 `8080`，可自定义（1024-65535）
5. **启动服务**：点击「▶ 启动服务」
6. **访问**：在同一 WiFi 下的其他设备浏览器输入显示的 IP:端口 地址

## ⚠️ 注意事项

- 手机和访问设备必须在**同一局域网（WiFi）**
- 该服务器**没有密码保护**，不建议在公共 WiFi 使用
- 如需在公网使用，请自行添加 Basic Auth 认证
- 部分安全软件可能拦截本地端口监听，需要添加白名单

## 🔮 可扩展功能（TODO）

- [ ] 基础身份验证（用户名/密码）
- [ ] 文件上传功能
- [ ] 二维码扫码访问
- [ ] HTTPS 支持
- [ ] 文件搜索

---

Made with ❤️ using NanoHTTPD
