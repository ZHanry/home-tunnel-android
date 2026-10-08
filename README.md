# HomeDesk Android / Home Tunnel

当前主线 **11.0.0-rc.1 候选版**，使用固定的 Client Rust/Flutter 共用源码。暖居移动界面包含家庭设备、家庭服务、共享屏幕、设置；完整穿透服务管理保留，远控强制认证加密的 P2P 直连，失败停止，没有中继回退。

[English](README.en.md) · [候选下载](https://github.com/ZHanry/home-tunnel-android/releases/tag/v11.0.0-rc.1) · [最后稳定版 10.0.0](https://github.com/ZHanry/home-tunnel-android/releases/tag/v10.0.0)

Release 只有通用 APK（arm64-v8a/x86_64）、材料包和 SHA256SUMS 三个附件。Android 8.0/API 26 以上，目标 API 35；application ID 与发行证书保持不变，versionCode 11000001。升级前备份必要信息；旧 Compose/DTLS 远控不作为兼容目标。Android 14/15 共享屏幕必须从可见应用重新取得系统授权。

通用包不包含服务器、公钥或账户。配置自己的 hbbs 与公钥、允许的来源；登录 Server 11.x HTTPS 管理台。接入账号不等于共享屏幕授权。门户刷新凭据使用 AndroidKeyStore AES-GCM256；无明文回退、不启用系统备份。

构建和模拟器测试不代表真机及跨网媒体可用。跨网 NAT、长期媒体、真机权限/音频/输入/文件与 16 KiB 页设备仍待验收，详见 [候选说明](docs/HOMEDESK_RELEASE.md)。旧验证记录仅属于各自历史版本。

## 源码与构建

```sh
git submodule update --init --recursive
python3 scripts/check-homedesk-source.py
```

`homedesk-core` 是精确固定的 Client Git 子模块，包含同一 Rust/Flutter 源码，不维护复制分叉。`.github/workflows/ci.yml` 固定 Rust 1.96.0、Flutter 3.24.5、FRB 1.80.1、vcpkg、JDK17 和 NDK27.2，编译两个原生 ABI、运行 API26/35 加密存储与启动测试，使用原发行证书构建通用 APK。发布复用同一个 main 构建的原始字节。

`scripts/setup-homedesk-sdk.sh` 将本仓 `gradle/homedesk-abis.gradle` 安装到 Gradle 的 `init.d`。调试与发行 APK 都只打包 arm64-v8a/x86_64，避免 Flutter 自动附加的调试架构缺少对应 Rust 引擎；测试包构建同时沿用这两个目标架构。

构建准备还通过 `scripts/prepare-homedesk-signing.py` 将固定共用项目的 `storeFile` 改为显式赋值，修正提供签名路径时 Groovy 条件表达式的解析。补丁严格匹配原行，遇到其他源码会停止；发行仍强制原证书，不回退调试签名。

历史 `app/`、`native/` 和旧发行脚本供 10.x 追溯，不由当前 CI 打包。共用 HomeDesk 运行时按 AGPL-3.0 分发，材料包含对应源码、依赖和许可证；原 Android/Go 项目代码保留 Apache-2.0。

[Client 共用源码](https://github.com/ZHanry/home-tunnel-client) · [Server](https://github.com/ZHanry/home-tunnel-server) · [项目入口](https://github.com/ZHanry/home-tunnel)
