# 发布流程

当前源码版本为 `8.0.0`，`compatibility.json` 使用 `public-release`，标签必须严格为 `v8.0.0`；APK/AAB 版本显示、文件名与源码完全一致。`HOME_TUNNEL_RELEASE_VERSION` 不允许覆盖为另一个版本。正式版保留已知限制：真实 Android 解码与输入尚未验收，未实现能力明确不可用；版本标签不能代替功能或实机证据。

Android `versionCode` 从 `8000001` 开始独立递增，当前 8.0.0 使用 `8000002`，高于开发候选版的 `8000001`；每次新版本都必须高于所有已发布 APK/AAB（包含预发布）的编号。编号在 `gradle.properties` 中显式提交，不根据 Actions 次数、时间或语义版本公式重用。发布任务读取所有历史 Release 的证据并阻止降号、重复编号或覆盖已公开产物；同一个尚未公开标签的失败重试保持编号。

发行流水线必须导入桌面仓库已发布、已验证且带签名的 Android Controller SDK，再构建 arm64 JNI；默认 CI 的双 ABI 安全核心不再作为远控发行包。SDK 尚未发布或缺少审核后的 `native/controller-sdk.lock.json` 时，发行在接触签名密钥前失败。签名证书、applicationId 必须保持不变，RC→正式版需要重新构建与验证。

客户端先从最终 tag 构建 `HomeTunnel-Remote-SDK-<完整版本>-android-arm64.zip`，与原始桌面候选安装包一起经过原字节验收、封存并发布。Android 的只读 native snapshot 必须由该 SDK 的 `source/remote-artifact.json` 和源码 tar 导入，不能用开发 CI artifact 替代最终 Release。之后提交 `native/controller-sdk.lock.json`：`schema_version=1`、`repository=ZHanry/home-tunnel-client`、实际 `tag` / `source_revision` / `asset`，以及经审核的 SDK `sha256`、`android-sdk-provenance.json` 的 `provenance_sha256`、SDK 内 `android-webrtc-build.json` 的 `controller_manifest_sha256`。这些摘要只能取自真实产物，仓库当前不填占位摘要。

`scripts/fetch-remote-controller.py` 验证 GitHub 已发布 Release、annotated tag 解引用后的 commit、固定 tag 的 Cosign 身份、客户端 `verification_stage=verified`、签名 SHA256 清单和上述固定摘要，再安全解包并调用严格同源导入器。发行附件保留 SDK 锁、SDK provenance、controller build manifest、native library 摘要和源码锁。`device_media_accepted=false` 保留在构建证据中；APK 构建通过不代表 Surface 解码、触控或实机验收通过。

APK/AAB 的 `assets/licenses` 同时保留项目许可证、SDK 中与链接目标对应的原始许可证、NDK 27.2.12479018 的 `NOTICE` 与 `NOTICE.toolchain`，以及绑定源码和库摘要的清单。发布校验核对两种安装包中的通知字节与 SDK 一致，Release 的 `android-native-*` 通知附件必须与包内文件相同。

正式版本使用 `vX.Y.Z` 标签。7.0.0 将四个仓库与自有 Agent 统一版本，各组件独立构建，FRP 保留其第三方版本。源码版本与标签必须一致，`compatibility.json` 的阶段设为 `public-release`。

1. 提交代码到 `main`，等待 Quality Gate（包括 Android API 26 / 35 的密钥库与界面检查）、CodeQL 和 Secret scan 成功。
2. 在已通过检查的提交上创建版本标签。
3. 工作流构建完整安装包，运行组件检查，验证签名与产物身份。
4. 完整构建证明、SBOM、扫描/安装报告及签名材料与安装包一起保存为 Release 附件；Actions 附件提供额外副本。保持封存的 `SHA256SUMS.txt` 与 Sigstore bundle 原样，不能在签名后重写或删减清单。
5. 下载正式发布的安装文件，检查版本、签名与启动情况。

普通用户下载入口指向 Android `.apk`，桌面 `.exe` / `.zip` 或 Linux / macOS `.tar.gz`，服务端部署 `.tar.gz` 与 `compose.release.yaml`。Android `.aab` 和所有验证证据也在 Release 中持久保留。校验清单覆盖交付物和证据；APK 的 SHA-256 另外写入发布说明。项目入口仓库发布版本说明和发布清单并链接三个组件。

Android 沿用已有发行签名。Windows/macOS 暂无平台证书，7.0.0 明确标注未签名；签名和公证流程已接入，半配置会阻止发布。API 1.1 契约固定于不可改写的 `api-v1.1.0`；保留历史 `api-v1.0.0`。跨组件改动必须验证权限、设备隔离及兼容性。

先发布客户端并取得实际 Linux 7.0.0 包的 SHA-256，再更新服务端 `tests/client-baseline.json`，通过 amd64/arm64 发行联调后发布服务端。最后更新项目入口的 `releases.json`、网站副本和下载说明，使所有链接对应实际正式产物。
