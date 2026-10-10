# NestLink 14.0.0

Android 与桌面客户端共用精确固定的 Flutter/Rust 源码，统一紫色品牌、图标、设备目录、账号与设置名称。手机适配触控导航、设备状态、远控操作与穿透管理；穿透执行仍由桌面后台负责。

启动后登录自建 HTTPS 服务，支持记住登录、自动续期、设备凭据撤销及 Android Keystore 保护。远控采用认证加密 P2P 直连和短期授权；撤销后的冷启动与在线会话断开必须验证。

一个签名 APK 包含 ARM64 与 x86_64。保留 applicationId io.github.zhanry.hometunnel 和原发行证书；versionName 为 14.0.0，versionCode 为 14000000，最低 API 26、目标 API 35。发布提供 APK、对应源码与构建材料 ZIP 和 SHA256SUMS。

最终签名 APK 和精确共享源码版本正在构建与验收。发布需使用本版真实 APK 哈希、CI run、证书和安装联调证据，13.0.0 记录保留但不能代替 14.0.0 结果。模拟器检查不能代替实际 ARM64 手机、运营商 NAT 或长期媒体验收，未运行场景必须披露。
