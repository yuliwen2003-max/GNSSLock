# GPSLock 0.3.0：北斗分批屏蔽

> 本文件保留 0.3.0 旧包 `cn.gpslock` 的研究过程与当时状态。当前版本的品牌、包名、界面及部署方式见 [README.md](README.md)。

2026-10-20，小米 17 Pro Max / SM8850 / OS4.0.0.44.XPBCNXM，内核 6.12.69，SELinux Enforcing。此策略仅适用于下述精确匹配的 HAL 版本。

## 已验证与待验证

**已通过分两次追加卫星黑名单，让接收机读回完整北斗 1–63 屏蔽掩码 `0x7fffffffffffffff`。** 两次 QMI 请求均返回客户端成功、modem 成功。13:14 恢复用户所选 GPS＋Galileo 组合后，完整北斗掩码仍在；`GnssStatus` 中北斗可见数从 17 降为 0，GPS 可见 13，Galileo 可见约 9–10。

当前环境所有星座的 `usedInFix` 仍为 0，尚无有效 GNSS 定位。因此已确认的是接收机名单及卫星状态变化，**尚未验证有效定位时北斗确实不参与解算**。SBAS 屏蔽限制也未解决，不能据此宣称严格“仅 GPS”已验证。

## 使用与部署

构建 APK 和配套 Root 组件：

```powershell
.\build.ps1
.\install.ps1
.\build-native.ps1
.\build-probes.ps1
.\install-bypass.ps1
```

手机已在 SukiSU 中为 `cn.gpslock` 授予 Root。打开“Root 北斗分批策略”，点击“启用北斗分批策略”，再选择星座并应用；查询已启用状态也可确认本次会话。保持“使用 Root 分批策略应用配置”选中。启动 App 和安装组件不会自动应用策略。

Root 模式的“应用配置”将本次选择写入 App 私有 `files/pending_policy.txt`，交由 Root 组件执行完整事务，App 不再普通写设置后重发。请求排除北斗时，只有接收机 B7 读回完整 63 位掩码才报告成功；诊断应包含：

```json
{"ok":true,"state":"enabled","strategy":"bds_sv_append","bds_mask":"0x7fffffffffffffff","modem_verified":true,"bds_excluded":true}
```

允许北斗时掩码按本次名单验证，不能要求它仍为全 63 位。“关闭策略并恢复逻辑”清除接收机临时北斗屏蔽、恢复原 HAL 指令，并保留当前逻辑选择；“恢复原配置”使用 App 首次修改前保存的备份，两者用途不同。只取消 Root 模式复选框不会清除已有接收机名单。

电脑端 `gpslock.ps1` 仍是普通设置入口。选择完成后需运行 Root 事务：

```powershell
.\gpslock.ps1 allow -Constellations GPS,GALILEO
.\hal-bypass.ps1 enable
.\hal-bypass.ps1 status
# 结束实验，清除临时屏蔽并恢复 HAL 逻辑
.\hal-bypass.ps1 disable
```

组件位于 `/data/adb/gpslock/`：`gpslock-hal`、`gpslock-qmi-sv-append`、`gpslock-qmi-sv-split` 和 `gpslock-root.sh`。补丁仅在当前 HAL 进程有效；HAL 或手机重启后需重新启用。设置保留不代表接收机仍保留屏蔽。每次应用都重新检查 PID 和 HAL 版本；OTA 更换库后校验不符会拒绝修改。

## 分批策略为何有效

本机 modem 拒绝 B8 整星座排除北斗，也拒绝一次 B6 请求屏蔽全部 63 颗；部分名单可以成功。0.3.0 保留整星座层的北斗启用状态，通过原 HAL 的授权客户端完成：

1. 正常 B6 请求屏蔽北斗 1–32，读回 `0x00000000ffffffff`。
2. 对北斗 33–63 的 B6 请求，仅把其 `bds_clear_persist_blacklist_sv` 从 `0x80000000ffffffff` 临时改为 0，使本次请求追加高 31 位，保留先前低 32 位。
3. 请求返回后恢复栈字段和硬件断点，B7 读回 `0x7fffffffffffffff`。最后系统设置保存完整北斗 1–63 名单，并再次读回确认接收机仍保留全掩码。

最后一次完整名单请求本身仍会被 modem 拒绝；实测拒绝不会清除已累积名单，事务必须再次回读，不能只凭设置成功判断。后续普通设置写入、HAL 重启或其他客户端改配置都可能改变名单，应重新执行 Root 事务并检查结果。

独立新建 raw QMI 客户端可以读 B7，但写 B6 返回 `NO_PERMISSION`（115 / `0x73`）。实现复用现有 HAL 授权客户端，不注册或抢占 master，也不猜密钥。

## 内存改动与保护

目标 `/vendor/lib64/libloc_api_v02.so`，SHA256：

```text
e4d37489f1511535ee611ec67ccc3b7592a8c2f640b4978645c0356743471828
```

| 位置 | 改动 |
|---|---|
| ELF VA / 文件偏移 `0x265fc` | 4 字节 `0x54000141` → `0x1400000a`，将北斗逐星拒绝分支改为跳至 `0x26624`；邻接 `0x26600=0xf9400c29` 必须保持 |
| B6 调用前的当前栈 `sp+0xa8` | 仅匹配固定高 31 位请求时，把 8 字节 clear 字段改为 0；调用返回后恢复原值 |
| 历史补丁 `0x4c728` | 0.3.0 恢复原指令 `0x52800429`，不再尝试让 B8 整星座禁用北斗 |

`gpslock-hal` 保留服务名、PID starttime、文件 SHA256 / inode、ELF 装载段与 maps、目标及邻接指令校验；暂停线程后写 4 字节、读回 8 字节，失败尝试恢复。追加工具在固定调用前后使用两个临时硬件断点，核对映射、指令、B6 参数、栈和预期掩码后才修改字段；不改执行 PC 或通用寄存器。完成或取消时尝试恢复栈字段及硬件断点并脱离，日志单独报告恢复结果。

Root 脚本串行执行事务，校验每阶段接收机名单。失败时尝试清理临时名单并恢复先前逻辑配置，按 `rollback` 字段报告结果；这不保证先前的接收机聚合掩码已精确重建。失败后应查询实际状态。

以上仅修改当前进程内存，不写 HAL 磁盘文件、NV、modem 固件或系统分区。服务重启会清除代码补丁，但不能据此假定接收机配置也已恢复。此功能控制 GNSS 名单，不控制 Wi-Fi、基站融合或保证射频关闭。

## 可复核的真机证据

| 请求 | modem 结果 |
|---|---|
| B8 允许北斗 / 排除北斗 | `SUCCESS(0)` / `UNSUPPORTED(2)`，传输均成功 |
| B6 单颗北斗 1、37、38、63 | 均 `SUCCESS(0)` |
| B6 北斗 1–62 / 2–63 | 均 `SUCCESS(0)` |
| B6 一次北斗 1–63 | `UNSUPPORTED(2)` |
| 低 32 位正常请求＋高 31 位追加 | 两次均 `SUCCESS(0)`；B7 回读完整 63 位 |

原始记录仅保留在研究设备的本地 `.local/` 中，未随公开仓库发布：`qmi-b6-hal-append.jsonl`（追加调用与清理）、`qmi-b7-after-hal-append.jsonl`（B7 完整读回）、`qmi-root-append-baseline-policy.jsonl`（恢复组合的追加过程）。

0.3.0 集成验收已在本机完成：

| 操作 | 实际结果 |
|---|---|
| 后台关闭策略 | `state=stock`，B7 北斗 mask=0 |
| 后台重新启用 | `state=enabled`，B7 北斗 mask=`7fffffffffffffff` |
| App 选择仅 GPS 并应用 | 接收机完整名单确认；连续回调 GPS 约 11，其余星座均为 0 |
| App 切换 GPS＋北斗 | B7 北斗 mask=0；回调北斗恢复约 18，GPS 约 11 |
| App 切回 GPS＋Galileo | B7 北斗完整 mask；回调 GPS 约 11、Galileo 约 9，北斗为 0 |
| 策略与构建检查 | 完整/不完整北斗集合、混合逐星条目等测试通过；APK 签名验证及覆盖安装通过 |

最终保留 GPS＋Galileo、Root 分批策略与原有前台监测。此环境下本轮新策略的 `usedInFix` 仍均为 0，未据此声称有效定位精度或首次定位耗时已经验证。所有探针均已脱离，GNSS 服务保持原 PID，SELinux 仍为 Enforcing。

## 0.2.0 历史方法：未突破 modem 限制

0.2.0 仅修改 `0x4c728`，确实让 HAL 的仅 GPS enable 掩码保持 `0x20`，不再自动加回北斗而变为 `0x22`。后续捕获业务返回确认，排除北斗请求在 modem 层仍返回 `UNSUPPORTED(2)`；因此“HAL 原样下传”不能视为排除成功。0.3.0 已弃用该方法，采用上述分批追加及接收机读回。

协议来源：[Qualcomm LocApiV02](https://raw.githubusercontent.com/qualcomm-linux/location-apis-qcom/location.lnx.0.0/loc_api/loc_api_v02/LocApiV02.cpp)、[QMI 字段定义](https://raw.githubusercontent.com/qualcomm-linux/location-apis-qcom/location.lnx.0.0/loc_api/loc_api_v02/location_service_v02.h)、[TLV 定义](https://raw.githubusercontent.com/qualcomm-linux/location-apis-qcom/location.lnx.0.0/loc_api/loc_api_v02/location_service_v02.c)。[AOSP GNSS 契约](https://android.googlesource.com/platform/hardware/interfaces/+/0ed9be7fbf619c665ca7c039d93dce4a4de39480/gnss/aidl/android/hardware/gnss/IGnssConfiguration.aidl)要求屏蔽卫星不得参与定位，没有要求全部信号丢失或强制重搜。
