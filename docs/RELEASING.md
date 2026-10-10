# NestLink Android 14.0.0 正式发行

14.0.0 的正式来源是本仓库固定的 `homedesk-core` Rust/Flutter 子模块，构建入口为 [Android CI](../.github/workflows/ci.yml)，发布入口为 [Release Android](../.github/workflows/release.yml)。正式发布复用成功 main CI 的签名 universal APK，不重新构建安装包，也不再导入独立的控制器 SDK 候选。

本版 APK 与真实联调验收状态见 [HOMEDESK_RELEASE.md](HOMEDESK_RELEASE.md)。历史 9/10 版本的根目录 `app/`、SDK 导入、`android-candidate` 和候选提升记录只适用于对应历史源码，不是当前操作步骤。

## 冻结源码与升级身份

- 统一 `compatibility.json`、`gradle.properties` 和共享 `pubspec.yaml` 为 `14.0.0`，版本代码为 `14000000`，正式标签为 `v14.0.0`。
- 固定 `homedesk-core` gitlink，并同步 `homedesk-core.lock.json`。两个 ABI、桥文件、界面与依赖必须来自同一共享源码提交；共享契约必须逐字节一致。
- 保留 applicationId `io.github.zhanry.hometunnel` 和 [`release-signing-cert.sha256`](../release-signing-cert.sha256) 的原发行证书；不更换安装身份，不以 debug 签名替代 release 签名。
- 最低 API 26、目标 API 35；一个 APK 必须且仅含 `arm64-v8a` 与 `x86_64`。本版不发布 AAB、单 ABI APK 或独立 Android SDK。
- 冻结认证契约 `api-v2.0.0` 保持不变，浏览器远控及设备能力使用各自版本化扩展契约。`require_direct`、禁用中继的发行策略必须保持。

提交前运行：

```sh
python3 scripts/check-homedesk-source.py
python3 scripts/check-device-capabilities-contract.py
python3 -m unittest discover -s scripts -p 'test_homedesk_release.py'
git diff --check
```

源码、配置、测试或打包配方发生变化，必须完成相应新的 main 构建和验收；不能复用旧 APK 或把 13.0.0 记录改成 14.0.0。

## 构建、安装与记录验收

在固定 main 提交上运行 CI。Quality Gate 要求桥生成、双 ABI 原生构建、Flutter 分析与 widget 测试、API 26/35 instrumentation、仓库检查及正式签名构建全部成功。`signed` 使用受保护 `android-release` 环境的原密钥；PR 仅允许跳过签名任务。构建说明与包校验见 [BUILDING.md](BUILDING.md)。

从成功 run 下载 `homedesk-android` artifact，保持 `products/NestLink-Android-14.0.0.apk` 的原始字节。`material-input/` 保存 APK 身份和哈希、共享源码提交、原生依赖来源与许可证，以及模拟器原始证据。保留 run ID、run attempt 和构建提交，验收应绑定实际下载 APK 的 SHA-256。

正式验收记录为 `docs/release/acceptance-14.0.0.json`。`scripts/homedesk-release.py` 要求记录满足以下条件：

- `version=14.0.0`、`component=android`、`status=passed_reproducible`，`source_revision` 是实际测试的完整提交 SHA。
- `installed-universal-apk`、`account-login-management`、`native-remote-control` 三项场景均为 `passed`，并提供实际证据位置。
- `deliverables` 精确列出接受过的 APK 文件名和 SHA-256，不能接受重建后的不同字节。
- `unverified` 明确包含 `physical-android-device`、`carrier-network-nat`、`long-duration-media`；已完成的可复现范围不能被表述为这些尚未完成场景的结果。

验收源码必须是最终发布提交的祖先。验收后只允许新增或更新本版验收 JSON、`docs/HOMEDESK_RELEASE.md` 和 `docs/RELEASE_NOTES.md`；其他文件变化会阻止发布并要求重新构建验收。所有产品文档、锁文件、图标和版本字段应在源码验收前完成。

## 发布同一批产物

最终发布提交必须在 main 上，Quality Gate、CodeQL、Secret scan 成功。`v14.0.0` tag 触发 Release Android；创建 tag 已会启动发布，不是只创建待人工提升的旧候选记录。

发布脚本校验 tag 与源码版本一致、精确提交属于 main、安全检查成功，并选择已接受源码对应的成功 main CI。工作流下载其 `homedesk-android` artifact，再次核对验收绑定的 APK 字节，封装已提交来源和材料，签署 `BUILD.json` 并为校验和生成 GitHub 证明。先创建 draft、上传所有附件，最后将它公开为稳定版、设为 latest。

公开附件固定为三个：

| 附件 | 用途 |
| --- | --- |
| `NestLink-Android-14.0.0.apk` | 原证书签名，ARM64/x86_64 共用的可安装包 |
| `NestLink-android-Materials-14.0.0.zip` | 精确对应源码、全部子模块字节、构建材料、许可证、验收与 `BUILD.json` 的 Sigstore 签名 |
| `SHA256SUMS.txt` | APK 与材料 ZIP 的 SHA-256 |

发布入口不读取 Android 应用签名密钥；APK 签名在成功 main 构建中完成。Sigstore 验证身份应指向本仓库 `release.yml` 的 `refs/tags/v14.0.0`，并匹配 GitHub Actions 的 OIDC issuer。对外分发前重新下载三个附件，核对 `SHA256SUMS.txt`、`BUILD.json` 的签名和来源、实际 APK 包名/证书/版本/ABI，以及升级安装行为；入口仓库随后使用真实产物记录更新下载页。

Apache-2.0 与 AGPL-3.0 分别适用于对应源组件。材料中的 `corresponding-source.zip` 由已提交源码和精确子模块递归生成；本地忽略目录、密钥和未提交修补不会成为正式来源。
