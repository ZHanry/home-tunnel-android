# nestlink 13.0.0

Android 正式版使用 nestlink 名称、统一图标和同源 Flutter 工作台，按远控、穿透、设备、设置组织导航。一个 APK 包含 ARM64 与 x86_64，提供管理和远控能力；不分发手机穿透执行器或独立 CLI。内容可触控滚动，隐藏可见滚动条。

启动后必须登录自建 HTTPS 服务，自动获取连接配置。提供账号密码、记住登录、自动续期和可撤销设备凭据，凭据由 Android Keystore 保护。修复登录恢复和续期时的安全存储缓冲区问题。MFA、恢复码与设备接入码入口移除；原生核心校验短期远控许可，未登录或会话撤销后无法建立远控。

同一自建服务下，可按设备 ID 连接同账号设备或跨账号协助，被控端必须批准或验证密码。画面与输入走认证加密的 P2P 直连，失败明确结束。设备标签和收藏可在手机管理，穿透和远控的生命周期分别管理。

发布复用 [构建 37960996185](https://github.com/ZHanry/home-tunnel-android/actions/runs/37960996185) 的最终签名 APK，Client 核心精确固定为 4cf3d1603a7c6d22b37746f3f02fee707a9338d9。保留 applicationId io.github.zhanry.hometunnel 与原发行证书；versionName 为 13.0.0、versionCode 为 13000000，最低 Android API 26、目标 API 35。

实际安装 APK 的字节、两种 ABI、ELF 架构与原证书均已核验。API 26/35 的真实 Keystore、篡改拒绝和启动检查通过。最终 APK 在 API 35 x86_64 模拟器中通过登录、强制停止后的冷恢复、真实自动续期、设备元数据管理，以及与最终 Linux x64 安装版的同账号和跨账号批准、真实画面、键盘输入、直连、横竖屏和撤销后断开；撤销后冷启动回到登录页。

完整记录见 [13.0.0 验收记录](release/acceptance-13.0.0.json)。发行包含通用 APK、材料 ZIP 和 SHA256SUMS；材料提供对应源码、许可证、构建与 Sigstore 证据。历史标签和升级身份保留，本次不升级生产部署。

Android 真机、实际 ARM64 手机运行、Android 到 Windows 的媒体、运营商网络 NAT 和长期媒体尚未验收。音频、文件与剪贴板未纳入本次验收。模拟器和同机隔离网络的结果不代表上述范围。
