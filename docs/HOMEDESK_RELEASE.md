# NestLink 14.0.1 维护版

Android 与桌面继续共享 Flutter/Rust 工程，版本为 14.0.1，`versionCode` 为 `14000100`；保留 `io.github.zhanry.hometunnel`、原发行证书、安装身份和受保护凭据机制。gitlink 与来源锁固定本版 C Client 源码，包含 Linux 菜单、授权同步、GUI 凭据恢复、安全诊断和窄屏适配。

The universal APK keeps the existing application ID, signing certificate and protected installation identity, and contains both ARM64/x86_64 native libraries. Its shared Flutter/Rust core is pinned to the C payload source, separately from any later formal Client documentation commit. Actual overlay, UI and media scope is recorded below.

C 产物源码为 `dcbcf85126af88695a890137ca8ade5fed0e1296`，来源为成功的 [Android CI 38172156970](https://github.com/ZHanry/home-tunnel-android/actions/runs/38172156970)（attempt 1）。本版实际验收见 [acceptance-14.0.1.json](release/acceptance-14.0.1.json)。

| C 固定产物 | SHA-256 |
| --- | --- |
| NestLink-Android-14.0.1.apk | `c06a56a16f69e8432793abea641869e43698c74e8fb38417e6f88cecba38db37` |
| 原发行证书 | `d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702` |

共享 C Client：`7ca0fd7186586965921e48beef2dbb2ae44fc895`。

产物源码提交用于绑定已构建的 C 运行时字节；正式 `v14.0.1` 标签提交还会包含本版验收 JSON 与两份发行文档。本版 owner waiver 仅允许三个发行文档与 `scripts/homedesk-release.py`、`scripts/test_homedesk_release.py` 两项政策文件相对产物源码变化；逐场景绑定这两份政策的实际 SHA-256，运行时字节保持固定。验收 `source_revision/build_run` 保留 C 产物来源，正式 Materials 的 `BUILD.json.revision/build_run` 另记实际正式提交与 release.yml run，`payload_source_revision` 保留 C 来源；正式 Materials、附件签名和校验和在真正发布后独立核验，不宣称文档提交重新构建了产物。

此前 A/B 的故障、安装链和原始尝试单独保留，13.0.0、14.0.0 及 A/B 结论不替代 C。用户取消后续远控回归并要求直接发布。未执行的 C 运行时场景明确记录为 not_run 和 owner waiver，不推断通过；通过场景才绑定实际执行原件。

本次仅更新 GitHub 发行、下载站和自动更新信息；正在运行的生产服务端保持原版本。

The acceptance source and candidate build run identify the exact C payload bytes. Formal documentation commits and release-workflow runs remain separate. Unfinished runtime scenarios remain not_run under the explicit owner waiver. Only GitHub releases, downloads and update information change; the running production server is not upgraded.

## 本版实际验收范围

状态：`accepted_with_waivers`。用户授权原话：`不需要远控回归,直接做后面的`。

Exact C build provenance and payload hashes are verified. The actual APK retains the original Android signing certificate d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702. Formal Materials Sigstore verification is performed separately after publication. Pending installed-product remote, authorization, account and tunnel runtime scenarios are not_run after the owner requested direct publication. The Android C overlay is the sole passed required installed-product scenario. Existing local Linux overlay evidence remains a separate completed check. No production server deployment or Windows reinstallation is performed.

- **account-login-management**：not_run — The owner stopped pending remote-control regression and requested direct publication. This unfinished runtime scenario remains not_run; exact-source CI and payload/signature verification do not constitute installed-product runtime acceptance.
- **installed-universal-apk**：The exact signed C universal APK was overlaid once on the retained API35 x86_64 emulator. Original signing certificate, application ID, first-install time, installation identity, configuration and protected login ciphertext were preserved. Both ARM64/x86_64 libraries are packaged. UI, login, permissions and media were not tested for C; physical ARM64 was not tested.
- **native-remote-control**：not_run — The owner stopped pending remote-control regression and requested direct publication. This unfinished runtime scenario remains not_run; exact-source CI and payload/signature verification do not constitute installed-product runtime acceptance.

签名范围：The actual APK retains the original Android signing certificate d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702. Formal Materials Sigstore verification is performed separately after publication.

准备过程实际记录（不替代运行时回归）：

- **linux-c-payload-overlay**：`passed` — Exact C Linux x64 payload installed with identity, configuration and keyring preserved; GUI and media not run.
- **private-fixture-c-conversion-preflight**：`failed_before_dispatch` — The private application-image transition refused its shim identity preflight before any dispatch. Existing B application containers and FRPS remained unchanged. No C GUI or runtime fixture acceptance is asserted.

## UI 与健康观察

本版 C 产品 UI 与健康回归未完成；保留官网真实 14.0.0 图库、原 capture manifest 和 SHA-256，并明确标注 14.0.0 截图参考。没有 C 真实界面矩阵，不把历史像素改版本冒充新版截图。此前 B 的中文 remote placeholder 和 gatewayUnhealthy 观察保留为历史记录。本版 C 的中文初次打开、EN↔ZH 既有表单切换及健康结果须按实际原件判断，文档不会据源码 CI 或截图 helper exit 0 推断通过。英文管理区“策略队列/备份”的混合语言观察仍保留；未覆盖的管理员项与 FRPS 健康没有本版通过结论。

## 限制与未测范围

Windows 14.0.1 本机清装与 GUI 未实测；安装器由 CI 验证且没有 Authenticode，Materials 的 Sigstore 签名不能替代平台可执行文件签名。Linux ARM64/Wayland 实机媒体、实体 Android、运营商 NAT、长期媒体，以及音频/文件/剪贴板等未测范围继续如实保留。Android 被控方向、PIN/`trusted_devices` 与安装归属不宣称全部通过；具体边界以下方本版 unverified 原列表为准。历史安装链不等同于直接公开 13→C，synthetic 迁移不等同于生产原数据库升级。

既有审查记录的 18 个 open 依赖告警（3 high、7 medium、8 low）及具体未修复依赖继续保留；没有通过版本同步关闭或 dismiss 告警，历史审查不是新的动态可达性或利用测试。Linux `users` 0.10.0/0.11.0、受影响的 `libgit2-sys` 构建输入、其他 glib/users 与 atty 风险没有由本维护修复消除。

Windows local installation/GUI, physical Android/ARM64, Wayland media, carrier NAT and long-duration media remain outside the tested scope unless the original C acceptance explicitly records otherwise. Windows has no Authenticode signature, and the existing dependency advisory scope remains unresolved. Production deployment is not part of this release.

本版原验收 unverified：`account-login-management`, `carrier-network-nat`, `current-c-product-ui-matrix`, `local-windows-14.0.1-reinstallation`, `long-duration-media`, `native-remote-control`, `physical-android-device`, `physical-linux-arm64-gui`。

## 已发布 14.0.0 历史记录

# NestLink 14.0.0

NestLink 14.0.0 Android 与桌面端共用冻结的 Flutter/Rust 源码，统一紫色主题、Logo、设备目录和业务导航。保留 `io.github.zhanry.hometunnel` 与原发行证书；本版 `versionCode` 为 `14000000`。验收 APK 来源为 Android 提交 `dcdfc4299d1f92f46b5f3ca8e675d304f9639982`、共享客户端提交 `feaf1181efe55076dead52931203a8488cbad20d`，构建为 [Android CI 38069852456](https://github.com/ZHanry/home-tunnel-android/actions/runs/38069852456)。APK SHA-256：`0d81e4a2ac6ee64b21fd95bcae00ba3cbc71b9036371475786cc8bf03bcafbab`。

在 API 35 x86_64 隔离模拟器上，已实际从公开 13.0.0 APK 以原证书覆盖升级至上述确切 14.0.0 APK，核验安装身份、批准配置和受保护的记住登录数据保留。已核验 universal APK 的 ARM64/x86_64 原生库与签名；ARM64 硬件运行尚未测试。本次记录绑定 14.0.0 实际产物，没有复用 13.0.0 的验收结论。

UI 验收独立审阅 58 张原图及 58 份侧录，保留 143 次成功操作和 2 次失败操作。已记录页面覆盖中英文、明暗主题，手机 320/360/412dp、平板 760/1024dp、横竖屏、放大字体和软键盘；实际验证登录、账号切换后的目录隔离、Android 系统屏幕共享许可、共享通知及 InputService 授权。非阻断观察和状态覆盖边界保留在原始审阅中；刷新前后稳定布局的证据不代表连续加载过程全部已测。

原生媒体验收通过范围为 **Android 控制端 → Linux x64 被控端**，包含同账号和跨账号两次真实批准、收到画面、键盘输入，以及 Linux/QEMU/Android 三方进程拥有的确切 P2P 套接字和关闭后原媒体对消失。隔离环境使用真实 HBBS LAN 分类及短时、精确端口规则；没有媒体代理或中继，也没有修改产品安全策略。关闭同账号会话的截图处于确认前，真正关闭由确认后的独立套接字证据证明；工具执行前源码封存和首次 SYN 时序的已知证据边界如实保留。

Android 被控媒体在本轮 slirp 模拟器上未通过。三类尝试分别保存：Host 账号完整 ID 请求的 HTTP 404；Controller 请求因测试输入少末位而出现的 HTTP 404；恢复 Controller 并使用完整 ID 后，获得 `10.0.2.15` 内部候选而在批准前发生 P2P 失败。只读设备归属诊断没有实测 HTTP 409，也没有转移原安装身份。该被控方向及跨账号安装身份重新归属列为未验证范围，不宣称双向远控已通过。

原始截图和私有原生日志保留在本地验收目录，不能直接作为公开网站素材。物理 Android、ARM64 硬件、运营商 NAT、长期媒体、音频/文件/剪贴板媒体及完整状态和键盘焦点矩阵仍未验证。发布后的下载与更新发现由发行任务另行记录；此验收记录不包含生产服务端升级，线上服务保持原版本。
