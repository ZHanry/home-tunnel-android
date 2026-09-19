# Android 7.0.0

Android 是远程管理端，不在手机上运行 FRP 或保持隧道。需要服务端 7.0.0 和一台
持续在线的电脑/NAS。支持 Android 8.0+；正式 APK 面向 arm64-v8a。

- 保存并切换最多 20 个自托管服务器/账号，凭据由 AndroidKeyStore 保护。切换清空
  旧视图缓存；后台完成的旧请求不能写进当前账号。移除本机保存项不会宣称已撤销服务器会话。
- 登录和改密支持动态码/恢复码。账号页面管理 TOTP、恢复码、登录会话和接入码。
  MFA 动态码一次消费，验证失败不会偷偷刷新并自动重放请求。
- 根据服务端 capabilities 创建 HTTP、TCP、UDP、SSH、RDP、RTSP 连接；公网端口
  由服务端分配。管理员可查看端口池容量并带版本保存设置。
- 设备标签、收藏、分页列表，批量选择最多 50 条连接，确认后逐项报告暂停/恢复结果。
- 管理端诊断检查 HTTPS、授权和设备状态，可复制白名单脱敏报告。不收集原始日志、
  令牌或家庭拓扑；不能替代家庭主机上的本地目标诊断。

初次安装核对 Release 中的 SHA256SUMS 和签名证据。现有用户直接升级保留包名和签名，
旧单账号加密状态自动迁移。不要卸载来“修复”升级：卸载可能丢失本地密钥与保存账号。

English: Android is a management client, not an FRP host. Use server 7.0.0. Save up
to 20 encrypted server/account profiles, switch without cross-account caches,
manage MFA/sessions/enrollment, create capability-authorized transports and edit
versioned port policies. Tags, favorites and per-item batch results simplify
management. Reports are redacted and limited to what the phone can observe.

[Account security](https://github.com/ZHanry/home-tunnel-server/blob/main/docs/ACCOUNT_SECURITY.md) ·
[API contract](../contracts/README.md) · [Signed release](https://github.com/ZHanry/home-tunnel-android/releases/latest)
