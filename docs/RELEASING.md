# Android 候选构建与发行

10.0.0 开发先固定源码，再构建候选包、完成安装与联调，最后发布同一批文件。当前版本字段保留 9.0.0 基线；正式冻结时统一更新四仓及自有 Agent，FRP 保留独立版本。历史版本、签名身份与协议标签保留。

## 导入同源 SDK

完整客户端候选通过 `release.yml` 的 `candidate=true` 构建并封装同源 arm64-v8a 和 x86_64 SDK。Android 可直接导入成功 run 的原始 `candidate-assets`：

```sh
python scripts/import-sdk-candidate.py --candidate-format client --run-id RUN_ID --revision CLIENT_COMMIT --import-source
```

导入器验证原始 `client-candidate.json`、全部附件摘要、两个 ABI 各自的签名和构建证明。调用工作流必须是 `release.yml`，签名工作流必须是 `client-candidate.yml`，并且匹配同一源码、分支、run 和 attempt。它仅在内存中整理 ABI 信息；不会生成冒充原始签名记录的 SDK 清单。锁文件保存完整候选的产物 ID、摘要和原始清单摘要。

独立的 SDK 构建仍可使用 `android-webrtc.yml` 的 `candidate=true`；其签名工作流为 `android-sdk-candidate.yml`，导入方式保留：

```sh
python scripts/import-sdk-candidate.py --run-id RUN_ID --revision CLIENT_COMMIT --import-source
```

导入器核对 GitHub 产物摘要、Cosign 签名、固定工作流的构建证明、run/attempt、完整源码、公开头文件、依赖锁、编译器和每个文件的摘要。两个 ABI 必须同源，且满足 API 26 与 16 KiB ELF 对齐。提交真实生成的 `native/controller-sdk-candidate.lock.json` 与源码快照。源码快照只能重新导入，不能手工修补。

CI 使用 `python scripts/import-sdk-candidate.py --restore` 按锁文件中的调用者、签名者和产物名称恢复对应格式，禁止混用两条链路，保持已提交的锁与源码不变。恢复默认写入 `.cache/remote-controller/<ABI>`；现有输出不会覆盖。历史 9.0.0 的稳定版导入仍可使用 `fetch-remote-controller.py` 与其旧 Release 锁。

## 构建候选包

在固定 Android 提交上，通过已有 `ci.yml` 的 `workflow_dispatch` 设置 `release_candidate=true`，调用 `android-candidate.yml`。候选流程验证仓库与 SDK，执行 JVM 测试和 Lint，使用现有 `android-release` 环境中的密钥，构建两个正式 APK 和 arm64 AAB。

`verify-android-candidate.py` 核对每个包的 applicationId、版本、最低 API、非调试属性、长期签名证书、ABI、原生库和许可证。两个 APK 各自携带对应 SDK；arm64 APK 与 AAB 的原生库和许可证必须一致。`seal-android-release-candidate.py` 将安装包、SPDX SBOM、校验和和构建材料绑定到源码及 Actions run，再由工作流签名和生成证明。

`android-release-candidate.json` 是不可变构建记录，始终保留 `acceptance_complete=false`。设备、UI 和升级证据应单独保存并绑定这个记录和安装包摘要，不能通过改写签名记录把构建成功变成验收通过。候选构建使用独立的 Candidate Build Gate，不能代替普通 CI 的 Quality Gate。

候选导入、独立验收和原文件提升已接入 `release.yml`。该入口只允许对现有版本标签手动调度，创建标签本身不会发布。当前链路仍需实际签名候选运行验证；在整包验收和提升链路都通过之前，不创建 10.0.0 标签或正式 Release。

下载候选进行测试时，固定成功 run 的产物 ID 和 GitHub 提供的 ZIP SHA-256：

```sh
python scripts/fetch-android-candidate.py --run-id RUN_ID --artifact-id ARTIFACT_ID \
  --artifact-sha256 ZIP_SHA256 --revision ANDROID_COMMIT --version VERSION --output candidate-import
```

下载器核对 CI 调度、完整源码 SHA、run/attempt、ZIP 摘要、所有文件的 Cosign 签名和工作流身份，以及候选清单和三个安装包的构建证明。测试完成后，将经审阅的验收记录提交到入口仓库 main 的 `validation/android/<ANDROID_COMMIT>/`，保持 Android 构建提交不变。记录格式见 [ANDROID_ACCEPTANCE.md](ANDROID_ACCEPTANCE.md)。实际日志、截图和抓包保留原始位置和摘要；记录不是代替实际测试的声明。

正式调度 `release.yml` 时提供 `candidate_run_id`、`candidate_artifact_id`、`candidate_artifact_sha256` 和入口仓库完整 `acceptance_revision`。流程下载已验证候选及该固定提交的验收记录，重新检查两个 ABI 的原生库、许可证和包身份，原样复制安装包、SBOM 和候选签名，最后只签署发布总清单。正式流程不读取应用签名密钥，不调用 Gradle，也不重新生成安装包或 SBOM。

## 版本与身份

- 包名始终为 `io.github.zhanry.hometunnel`，沿用 `release-signing-cert.sha256` 中的发行证书。
- `versionName` 必须与源码和最终标签一致。`HOME_TUNNEL_RELEASE_VERSION` 不能用来覆盖源码版本。
- `versionCode` 显式提交，必须高于所有已公开稳定版及预发布包。候选测试不占用新的公开版本；相同候选不能在验收后重建再冒充同一文件。
- 调试包、模拟器专用 CA 和临时修改过源码的 x64 库不具备正式发行资格。
- 当前 API1.4 是草案。最终冻结为 `api-v1.4.0` 时更新生成类型和摘要锁，保留 `api-v1.3.0` 等历史不可变标签。正式发行入口要求兼容性记录、API 锁和远控锁都声明同一个已冻结标签与干净服务端提交，并核对 GitHub 上该标签的真实提交；草案仅可用于候选构建。

## 正式发行门槛

在实际本地虚拟机中验证 x64 最终包：API 35 全量功能、API 26 兼容、四种远控授权、撤销、显示器/DPI、输入、剪贴板、文件、系统音频、网络异常、升级与回退。Android 9.0.0 x64 基线必须同源且同证书，并记录其与历史 arm64 包的区别。arm64 单独记录构建、签名和实际运行范围。

全部适用界面需要 Gemini 读取实际截图并审核，保存源码、安装包摘要、截图摘要和复审记录。源码或包变化后，复核受影响范围。30 次连接、两小时活动会话、24 小时在线和性能对比等共同门槛仍适用。

验收通过后合入 main，并要求普通 Quality Gate、CodeQL、Secret scan 等检查通过。按客户端/共享 SDK、Android 与服务端、入口仓库的顺序发布 `v10.0.0`。发布经过验收的原文件以及校验和、SBOM、构建证明、升级说明和验收摘要；复核下载后再清理开发分支。
