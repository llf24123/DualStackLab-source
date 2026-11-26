# DualStackLab 0.6.64 · 源码版

DualStackLab 是 Android 双窗口文件管理器，支持本地与归档文件操作、书签、媒体预览、文本与十六进制编辑、终端，以及用户配置的远程存储。最低支持 Android 8.0（API 26），目标 API 36。

[版本记录](docs/releases) · [本地签名说明](docs/SIGNING.md)

## 从源码构建

准备 JDK 17，用 Android Studio 的 SDK Manager 安装 Android SDK Platform 36、项目同步时提示的 SDK Build Tools，以及 NDK 26.3.11579264。将 `ANDROID_HOME` 指向 SDK，或在本机的 `local.properties` 中设置 `sdk.dir`。仓库自带 Gradle Wrapper；首次运行需要联网获取 Gradle 和依赖。

在项目根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

在 macOS 或 Linux 上使用 `./gradlew` 替代 `.\gradlew.bat`。主应用输出到 `app/build/outputs/apk/debug/`，仪器测试包位于 `app/build/outputs/apk/androidTest/debug/`。测试所用的示例 APK、终端原生库和 ELF 工具均从源码在本机生成。仓库不分发安装包。

## 使用自己的安装 ID

默认安装 ID 为 `org.example.filemanager`。要使用自己的 ID，把 [application-id.properties.example](application-id.properties.example) 复制为根目录下的 `application-id.properties`，修改其中的 `applicationId` 即可。该文件已由 Git 忽略。也可在构建时指定 `-PfileManagerApplicationId=org.example.myfiles`，命令行值优先。

无需修改 Java 包路径。文件分享、文档提供器和测试包的标识会随安装 ID 改变。自行构建的应用需要保留同一安装 ID 和签名密钥才能覆盖升级。

## 主要操作

- 在双窗中浏览目录，长按文件可复制、移动或查看属性；长按同步按钮可按关键字过滤当前列表。
- 直接浏览归档或预览本地图片、音频和视频；APK 与分包安装均保留系统确认入口。
- 需要远程文件时，可自行添加 FTP、SSH（SFTP）、HTTPS WebDAV 或 SMB 服务器。SSH（SFTP）支持密码和私钥登录。本地浏览不需要远程账户。

## 许可证

项目许可证见 [LICENSE](LICENSE)。终端模块的上游出处和许可见 [Termux 声明](third_party/termux/NOTICE.md)；Junrar 的源码修改见 [补丁说明](third_party/junrar/README.md) 和 [许可文本](third_party/licenses/JUNRAR-LICENSE.txt)。其余依赖和版本在 [应用构建配置](app/build.gradle) 中列出。
