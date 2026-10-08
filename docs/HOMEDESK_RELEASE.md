# HomeDesk Android 11.0.0-rc.1 候选发行

移动暖居使用固定的 Client Rust/Flutter 源码，包含家庭设备、家庭服务、共享屏幕和设置。保留完整账号下的内网穿透服务管理；HTTP/HTTPS、受控 TCP/UDP、权限、ACL 与流量策略仍由 Server/Agent 实施。

远控必须认证加密并通过 P2P 直连。只有 hbbs 信令，无 hbbr/TURN/FRP/HTTP/WSS 或供应商中继回退；打洞失败终止。Android 14/15 共享屏幕要求用户在可见界面重新授权，不能从开机广播自动启动屏幕采集。

三个附件：arm64-v8a + x86_64 通用 APK、源码/依赖/构建材料包、SHA256SUMS。application ID 保持 `io.github.zhanry.hometunnel`，使用原发行证书和递增 versionCode 11000001，可在原应用上升级；旧 Compose/DTLS 远控不继续兼容。系统要求 Android 8.0/API 26 以上，目标 API 35。

门户凭据使用不可导出的 AndroidKeyStore AES-GCM256 密钥、随机 IV 和附加认证数据，禁止明文回退，关闭系统应用备份。通用包没有内嵌账号、服务器、公钥或厂商回退。

模拟器凭据/启动测试、构建和签名检查不代表真机及跨网媒体验收。真实设备的采集、声音、输入、文件及 NAT 组合尚未验收；未验证 16 KiB 页设备兼容性。本次发布为候选版，保留旧稳定 Release 与回退材料。
