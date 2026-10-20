# GNSS LOCK 发布版安装

应用包名：`com.yuliwen.gnsslock`。安装包需要 Windows PowerShell 与已加入 `PATH` 的 ADB；仅连接一台已授权手机，或为脚本传入 `-Serial`。

## 安装

解压完整的 `windows-adb.zip`，在解压目录执行：

```powershell
.\install.ps1
```

该脚本安装 `build/GNSSLock.apk`，并授予精确定位及系统设置写入权限。单独安装 APK 的命令为：

```powershell
adb install -r -t GNSSLock-v0.5.0-release.apk
adb shell pm grant com.yuliwen.gnsslock android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.yuliwen.gnsslock android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.yuliwen.gnsslock android.permission.ACCESS_COARSE_LOCATION
```

APK 未开启调试，但为使用 Android 隐藏设置读取能力保留 `testOnly`，所以必须通过 ADB `-t` 安装。

## 北斗追加锁定

查看定位不需要 Root；标准名单需要系统设置写入权限及设备 HAL 支持。使用本机固件专用的北斗追加方式时，先授权 ADB shell 的 Root，再执行：

```powershell
.\install-bypass.ps1
```

随后为 GNSS LOCK 单独授予 Root，在高级页检测并选择北斗追加锁定。组件安装不会自动更改当前名单。

该 Root 方案仅验证小米 17 Pro Max／SM8850／Android 17／OS4.0.0.44.XPBCNXM，运行前会核对 HAL 库指纹；其他固件或 OTA 后不匹配时会拒绝修改。目前支持手机主用户。手机或 HAL 重启后，需要重新应用并核验。

## 签名与更新

发布版使用独立的持久签名，与本项目之前的调试签名不同，不能直接覆盖调试版。切换前需迁移应用数据及原配置备份；安装脚本不会自动卸载现有应用。卸载也不会自动恢复系统名单。

同一发布签名的后续版本可覆盖更新。发布签名密钥与密码仅保存在开发机 `.local` 目录，未包含在发行包或 Git 仓库中。

`SHA256SUMS.txt` 用于校验发行文件。源码、完整研究记录和构建说明见仓库 README。
