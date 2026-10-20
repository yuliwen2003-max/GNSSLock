# 真机研究与验收记录

> 本文件记录 0.1.0 普通模式。后续已开发 0.2.0 临时 Root HAL 绕过，最新进展及当前手机状态见 [ROOT-BYPASS.md](ROOT-BYPASS.md)。下文“尚未实现”和“最终状态”均指第一阶段。

日期：2026-10-20（Asia/Shanghai）。设备：小米 17 Pro Max / 2509FPN0BC / popsicle；SoC QTI SM8850；Android 17 / API 37；系统 OS4.0.0.44.XPBCNXM；安全补丁 2026-09-01。Root 身份已确认，为 KernelSU。

## 结果

GNSS HAL 声明支持 `SATELLITE_BLOCKLIST`，可通过 `Settings.Global.gnss_satellite_blocklist` 热更新。在本固件上，这个能力有星座级例外：**北斗被首选星座策略强制保留；SBAS 被 HAL 明确拒绝屏蔽。严格“仅 GPS”或“排除北斗”尚未实现。**

| 验证项 | 实际观察 | 结论边界 |
|---|---|---|
| 配置链路 | Settings → GnssBlocklistHelper → GnssConfigurationAidl → LocSvc_ApiV02 | 已确认传至高通 HAL |
| 仅北斗 | 连续状态为北斗约 18，其余星座为 0；所有 usedInFix=0 | 已观察到星座状态变化，未验证成功解算 |
| GPS＋北斗 | 状态为 GPS 约 13、北斗约 18，其余为 0；所有 usedInFix=0 | 已观察到星座状态变化，未验证成功解算 |
| 仅 GPS / 排除北斗 | HAL 将北斗位重新加回 enabled、从 blacklist 移除 | 严格请求被厂商策略改变 |
| 排除 SBAS | HAL 明确报 `SBAS SVs can't be blocklisted.` | 不支持；本次可见列表也没有 SBAS |
| App 应用与恢复 | Android 设置写入、回读一致；原始空字符串与键不存在分别完成恢复测试 | 已验证，包括恢复后清除 App 备份 |
| 电脑脚本 | 排除北斗、全部允许、GPS＋北斗、恢复基线均成功回读 | 已验证设置管理，不能替代 HAL 验证 |
| 策略测试 | GnssPolicyTest 全部通过 | 覆盖常用组合、禁止全不选、逐卫星条目、未知/非法值 |
| APK | 0.1.0，testOnly，签名验证通过，已安装授予权限 | 需使用 adb install -t 或 install.ps1 |

用户暂不方便移动到室外，未把“可见数变化”当作“筛选后定位成功”。App 同时要求新鲜的 GPS_PROVIDER 位置结果、允许星座 usedInFix 和新鲜卫星回调，才显示相应的正向观察状态。它不会显示、保存或日志记录经纬度。

## 厂商限制的证据

仅 GPS 请求下发时，关键日志为：

```text
GnssBlocklistHelper: Update GNSS satellite blocklist: 2,0,3,0,4,0,5,0,6,0,7,0
GnssConfigurationAidl: setBlocklistedSource:272] SBAS SVs can't be blocklisted.
LocSvc_ApiV02: input perferred constellation 5, disableSupported 1, enable: 0x20, blacklisted: 0x1f
LocSvc_ApiV02: disableSupported 1, enable: 1 0x22, blacklisted: 1 0x1d
```

高通内部掩码 GPS=0x20、北斗=0x02，与 Android 星座枚举不是同一套编号；参见 [AOSP 收录的高通 LocationDataTypes.h](https://android.googlesource.com/platform/hardware/qcom/sm8150/gps/+/refs/tags/aml_cbr_341610000/location/LocationDataTypes.h)。此处 `0x20 → 0x22` 表示北斗被保留。

对当前设备的 `libloc_api_v02.so` 做了只读反汇编交叉验证：

- `processGnssBandsSupportedInd()` 读取 modem indication 的 `primaryGnssSignalType`，映射为 preferred sv type，保存到对象 `this+0x380`。preferred=5 即北斗。
- `setConstellationControl()` 异步实现 `0x4c2e0` 在 preferred=5 分支 `0x4c720–0x4c730` 从禁用掩码中移除北斗位。
- `setBlacklistSvSync()` 在 `0x265e4–0x26620` 检查首选北斗的逐卫星黑名单，非空则报 `SV Blacklisting NOT SUPPORTED` 并返回错误。因此拆成逐颗卫星不能绕过。
- `libloc_core.so` 的 `gnssConstellationConfig()` 只返回 measurement 能力布尔值，不是修改首选星座的配置入口。公开高通源码也可核对 [ContextBase.cpp](https://raw.githubusercontent.com/qualcomm-linux/location-hal-qcom/refs/heads/location.lnx.0.0/core/ContextBase.cpp)。

二进制 SHA256：

```text
libloc_api_v02.so E4D37489F1511535EE611EC67CCC3B7592A8C2F640B4978645C0356743471828
libloc_core.so    BD3006A562B40C207FC0C49DAE68B9C860A4D800FE5AFB283FA9F13E7D5EC7CF
```

只读分析产物保存在 `.local/libloc_api_v02.disasm.txt`、`.local/gnss-string-xrefs.txt` 和 `.local/analyze_gnss_elf.py`；它们未纳入版本控制。未发现经公开资料或静态分析确认、可解除首选星座保护的配置键。本实现使用标准接口，尚未提供 modem 配置修改、HAL 补丁或注入式绕过。

## 最终手机状态

`gnss_satellite_blocklist` 已精确恢复为研究开始时的“键存在、值为空字符串”，即全部允许。App 原配置备份已完成恢复并重置；前台监测开关已关闭，避免持续请求定位。保留已安装的 GPSLock 供后续选择与室外验证。
