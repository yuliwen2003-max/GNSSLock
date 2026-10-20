# GNSS LOCK

在 Android 手机上查看 GNSS 定位状态，按星座或逐颗卫星配置锁定名单。支持 GPS、北斗、Galileo、GLONASS、QZSS、SBAS、NavIC。

**0.5.0 使用 GNSS LOCK 品牌与新包名 `com.yuliwen.gnsslock`。** 保留定位概览与全部星座列表的左右滑动子页、统一的定位指标与设置项样式、指南针天空图、全局黑／白名单模式、信号进度条和逐星切换动画，沿用 0.3.0 的本机固件专用 Root 北斗分批策略。新包已完成安装、权限、Root 查询及单星配置提交验证。旧包 `cn.gpslock` 的历史实机测试曾读回完整北斗 1–63 黑名单 `0x7fffffffffffffff`，并观察到 GPS＋Galileo 组合下北斗可见数从 17 降为 0；随后用户确认北斗已不参与解算。该解算结论属于旧包历史，不代表所有星座组合均已验证或严格仅 GPS 已实测成功。

旧包 0.2.0 的单点整星座补丁只绕过 HAL，modem 仍拒绝请求，已降为历史失败方法。旧包策略、证据与恢复边界见 [ROOT-BYPASS.md](ROOT-BYPASS.md)；旧包普通模式研究见 [DEVICE-TEST.md](DEVICE-TEST.md)。

## 手机端

底部四个页面：

- **定位状态**：概览在左、全部星座列表在右，左滑进入列表，右滑返回概览；工具栏显示当前子页，没有额外的分页按钮。概览依次显示定位指标、星座总览、卫星天空图。经纬度、精度、海拔、速度（`km/h`）采用相同的大号指标样式；天空图跟随手机指南针朝向旋转，方向不可用时回退北向朝上。列表页筛选与排序固定，支持星座＋“仅参与”组合筛选及信号／编号排序；总览按卫星去重，列表保留不同频率的信号行。点击信号行进入对应卫星的锁定编辑。
- **星座锁定**：直接显示各星座开关及逐星摘要；黑名单模式下选中表示限制，白名单模式下选中表示允许。未应用状态显示在底部操作区。
- **逐星锁定**：顶部星座栏固定，可点击或在卡片内左右滑动切换，切换带有动画；卫星卡片填满底部操作区以上的可用高度，列表独立纵向滚动。一行显示一颗卫星的编号、信号进度条及 `dB-Hz` 数值、参与状态与名单勾选，各列居中。未观测卫星按 `0 dB-Hz` 显示“无信号”。颜色表示当前勾选，黑名单模式勾选表示限制，白名单模式勾选表示允许。批量操作为“全选、反选、清空、选可见”，作用于当前星座；各星座选择一起应用。整星座名单直接显示对应勾选状态，可直接编辑任意单星。
- **高级**：顶部显示当前锁定状态及北斗接收机核验结果，其后是应用方式、权限和恢复诊断。名单模式与北斗追加锁定采用相同的设置行样式；可恢复原配置、停止追加策略、查看原始配置、复制诊断。

高级页“名单模式”全局适用于星座和逐星两个锁定页面，设置会记忆：黑名单模式限制勾选项，白名单模式仅允许勾选项。切换模式会依据现有配置反转勾选显示，不会直接应用或更改接收机名单。

每次进入应用前台都会启动 GPS 刷新；工具栏可以暂停。定位和指南针仅在前台监听，退出前台后停止；再次进入会自动恢复。

两个编辑页面进入时读取当前名单；没有未应用修改时会同步系统更新。有修改后切换页面或返回定位状态，可选“应用并切换”“放弃修改”“继续编辑”。“重新读取”也会先确认放弃修改。应用失败会保留草稿；系统名单被其他入口更改时，会提示读取最新值或覆盖应用。

新旧包的实机界面、迁移和名单事务验证记录见 [UI-TEST.md](UI-TEST.md)，各节分别标明版本与测试范围。

### 权限与北斗兼容策略

| 功能 | 所需权限 |
| --- | --- |
| 查看定位和卫星状态 | 精确定位权限；无需 Root |
| 标准星座／逐星屏蔽 | `WRITE_SECURE_SETTINGS`，可通过 ADB 授予；无需 Root，但依赖设备 HAL 支持 |
| 本机北斗追加锁定、接收机核验与停止策略 | Root 授权及配套后台组件 |

ADB 写入授权命令为 `adb shell pm grant com.yuliwen.gnsslock android.permission.WRITE_SECURE_SETTINGS`，安装脚本已包含。高级页可查看授权状态及授权方式。

新包 `com.yuliwen.gnsslock` 需要单独获得 Root 授权，本机已完成授权并验证查询可用。“北斗追加锁定”控制两个编辑页后续应用的方式。开启时由后台执行完整事务，并核验本次北斗掩码；全排除北斗必须读回完整 63 位掩码才报告成功。关闭时使用标准设置方式，本机全排除北斗会提示其固件限制，可选择启用追加锁定。

只关闭开关不会清除已有接收机名单；“停止追加策略”会清除北斗追加名单并恢复 HAL 指令。HAL 或手机重启后应重新应用并核验。安装、打开 App 不会自动应用屏蔽；追加开关开启时，首次进入高级页会查询 Root 与接收机状态。OTA 后库校验不符会拒绝修改。

标准方式成功表示 Android 设置写入并回读成功；接收机名单核验表示已确认北斗配置。实时效果仍应结合新的 GNSS 定位检查：被排除星座 `usedInFix=0`，同时允许星座有参与解算。是否全部掉星重搜不是必需条件。

首次修改前 App 保存原配置；高级页“恢复原配置”使用该备份。电脑脚本与 App 的备份各自独立，建议同一阶段使用同一入口管理。卸载 App 会删除其备份，却不会自动清除全局设置；卸载前先停止追加策略并恢复配置。

## 构建与安装

构建使用 Windows PowerShell，无 Gradle、无第三方 App 运行时依赖。先安装 Android SDK Platform 37（目录 `platforms/android-37.0`）、build-tools 36.0.0、Android Studio JBR，以及构建 Root 组件所需的 NDK 29.0.14206865；安装和部署还需要 `adb.exe` 在 `PATH` 中。

脚本默认使用 `%LOCALAPPDATA%/Android/Sdk` 及 `C:/Program Files/Android/Android Studio/jbr`。PowerShell：

```powershell
.\build.ps1
.\install.ps1
.\build-native.ps1
.\build-probes.ps1
.\install-bypass.ps1
```

工具不在默认位置时，可向 `build.ps1` 传入 `-Sdk`、`-JavaHome`，向两个原生构建脚本传入 `-Ndk`。连接多台设备时，为安装脚本传入 `-Serial <ADB序列号>`。Root 组件部署前，需在手机上授权 ADB shell 的 `su` 请求；App 的 Root 授权需要另行授予 `com.yuliwen.gnsslock`。

构建产物为 `build/GNSSLock.apk`，应用包名为 `com.yuliwen.gnsslock`。新包与旧包分别保存应用数据，原包的授权及应用内备份不会自动迁移。

首次构建会自动在 `.local/debug.keystore` 创建调试签名；以后构建会沿用已有密钥。该文件不进入 Git 仓库，请自行私密备份，以便覆盖更新已安装的 APK。重新克隆后生成的密钥与其他机器不同，更新已有安装时应使用原签名密钥。

发布版使用独立持久签名，构建与打包命令如下；原生组件构建参数仍支持 `-Ndk`：

```powershell
.\build.ps1 -Release
.\build-native.ps1
.\build-probes.ps1
.\package-release.ps1
```

产物位于 `build/release/`，包括版本化 APK、Windows ADB 完整安装包和 SHA-256 校验文件。首次发布构建生成 `.local/release-signing.keystore` 与 `.local/release-signing.json`，后续构建会复用；两份文件需要一起私密备份，均不进入 Git。发布版不能覆盖之前使用调试签名的安装，且仍必须使用 ADB `-t`；详见 [发布版安装说明](RELEASE-INSTALL.md)。

Root 后台安装 `gpslock-hal`、`gpslock-qmi-sv-append`、`gpslock-qmi-sv-split` 及控制脚本，位于 `/data/adb/gpslock/`。更新 APK 时也应更新配套组件。

安装脚本授予 `WRITE_SECURE_SETTINGS` 与精确定位权限。APK 标记为 `testOnly`，必须通过 `adb install -t`（脚本已包含）安装，以使用 Android 为测试应用保留的隐藏设置读取能力。普通安装器不适用。普通功能使用 Android 原生接口；其他手机需要 ADB 授权及 HAL 的 `SATELLITE_BLOCKLIST` 支持，Root 分批策略只支持研究中精确匹配的固件。

## 电脑命令

`gpslock.ps1` 是普通设置入口，**不会自行执行 Root 分批事务**。需要排除北斗时，选择后再调用 `hal-bypass.ps1 enable`：

```powershell
.\gpslock.ps1 status

# 选择仅 GPS，然后执行 Root 分批事务
.\gpslock.ps1 allow -Constellations GPS
.\hal-bypass.ps1 enable
.\hal-bypass.ps1 status

# 其他普通名单示例；需要 Root 策略时再执行 enable
.\gpslock.ps1 allow -Constellations BEIDOU
.\gpslock.ps1 allow -Constellations GPS,BEIDOU
.\gpslock.ps1 block -Constellations GALILEO,GLONASS

# 清除临时策略，再恢复电脑脚本首次保存的配置
.\hal-bypass.ps1 disable
.\gpslock.ps1 restore
```

`gpslock.ps1 all` 允许全部星座。多设备时加 `-Serial <ADB序列号>`。电脑备份位于 `.local/`，恢复后归档；下一次修改重新建立基线。`allow`、`block` 和 `all` 都会替换整个系统 GNSS 黑名单。

## 工作原理与边界

全局设置 `gnss_satellite_blocklist` 存储 `星座ID,SVID` 整数对；SVID=0 表示整个星座。星座 ID 为 GPS=1、SBAS=2、GLONASS=3、QZSS=4、北斗=5、Galileo=6、NavIC=7。允许列表通过屏蔽其余星座实现；例如普通仅 GPS 请求是 `2,0,3,0,4,0,5,0,6,0,7,0`。

本机 modem 拒绝直接禁用北斗，也拒绝一次屏蔽全部 63 颗。Root 模式保留星座层北斗启用，通过现有 HAL 授权客户端先屏蔽低 32 颗，再追加高 31 颗。它临时修改一处 4 字节 HAL 分支和一次请求的 8 字节栈字段，恢复调用现场与硬件断点，并回读接收机确认。完整 63 颗明细名单会被识别为逻辑“排除北斗”，不会显示成自定义部分名单。实现不写 NV、固件或系统分区。

- 控制的是 GNSS 卫星名单；不控制 Wi-Fi、基站或地图 App 自己的融合定位。
- 不保证关闭某个星座的射频接收、原始测量输出或降低耗电。
- 单星座模式可能增加首次定位时间，或在当前环境完全无法定位。
- 设置持久保存与接收机状态不同；重启、其他客户端或普通设置写入后应重新执行 Root 事务并检查。
- 失败会报告回滚结果，应查询实际接收机状态；不能只凭设置值判断效果。

官方参考：[AOSP 黑名单观察与解析](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/location/gnss/GnssSatelliteBlocklistHelper.java)、[GNSS HAL 契约](https://android.googlesource.com/platform/hardware/interfaces/+/0ed9be7fbf619c665ca7c039d93dce4a4de39480/gnss/aidl/android/hardware/gnss/IGnssConfiguration.aidl)、[整星座通配定义](https://android.googlesource.com/platform/hardware/interfaces/+/b67c28b2b150ac90a37ed8e55c493872154ba3bd/gnss/aidl/android/hardware/gnss/BlocklistedSource.aidl)、[Android usedInFix API](https://developer.android.com/reference/android/location/GnssStatus#usedInFix(int))。

隐藏设置读取依据：[AOSP SettingsProvider 的 FLAG_TEST_ONLY 豁免](https://android.googlesource.com/platform/frameworks/base/+/main/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java)、[Android testOnly 安装规则](https://developer.android.com/guide/topics/manifest/application-element#testOnly)。

源码、构建脚本与脱敏后的验证记录纳入仓库；`build/`、`.local/`、签名密钥及本地凭据均排除。验证记录中的旧包名与旧版交互描述保留为历史信息。
