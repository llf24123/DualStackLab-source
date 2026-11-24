# 在本机签名

调试版使用本机 Android 调试密钥。要制作自己的可升级版本，请固定安装 ID，并单独保管正式签名密钥。此仓库只保存源码。

1. 在仓库外建立自己的密钥库，按提示输入密码：

   ```text
   keytool -genkeypair -keystore filemanager-release.p12 -storetype PKCS12 -alias filemanager -keyalg RSA -keysize 4096 -validity 10000
   ```

2. 将仓库根目录的 `keystore.properties.example` 复制为 `keystore.properties`，填入密钥库的绝对路径、别名和密码。Windows 路径可以用 `/` 分隔。这份配置和密钥均不要提交。

3. 在项目根目录执行 `./gradlew :app:assembleRelease`（Windows 使用 `.\gradlew.bat :app:assembleRelease`）。已配置密钥时，产物为 `app/build/outputs/apk/release/app-release.apk`；未配置密钥时生成未签名的 release 包。

4. 用 Android SDK Build Tools 中的 `apksigner` 验证本机产物：

   ```text
   apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
   ```

在本机备份好密钥和配置，后续升级沿用同一安装 ID 和密钥。反馈问题时只需说明版本和复现步骤，无需附上签名材料。
