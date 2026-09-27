# Android 候选验收记录

此文定义记录格式，不代表任何候选已经通过验收。当前没有可用于 10.0.0 正式发布的完整验收记录。

完成本地实际测试和人工核对后，在 `ZHanry/home-tunnel` 的 main 提交 `validation/android/<完整 Android 源码 SHA>/`。记录在入口仓库维护，避免给已经冻结的 Android 提交增加文件而改变构建 SHA。发行输入必须是入口仓库完整提交 SHA；分支名、未合入 main 的记录和不同候选的记录都会被拒绝。

目录包含 `android-release-acceptance.json` 和九个 `android-acceptance-<gate>.json`。总记录字段：

| 字段 | 内容 |
| --- | --- |
| `schema_version` | `1` |
| `repository` | `ZHanry/home-tunnel-android` |
| `status` / `acceptance_complete` | 所有门槛确实完成后才填写 `passed` / `true` |
| `app_revision` / `sdk_revision` | 候选清单中的两个完整源码 SHA |
| `candidate_sha256` | 原始 `android-release-candidate.json` 的 SHA-256 |
| `packages` | 原样复制候选的三个包条目，包括名称、SHA-256、字节数 |
| `coverage` | 九个门槛分别映射到 `status` 和对应 `evidence` 文件名 |
| `files` | 九个记录文件分别映射到 `sha256` 和 `bytes` |

每个记录重复 `repository`、`status`、`app_revision`、`sdk_revision`、`candidate_sha256` 和 `packages`；增加 `gate`、`environment`、`reviewed_by`、`cases`、`raw_evidence` 和 `metrics`。`cases` 是完整适用验收用例 ID 到结果的映射；必需用例只能在实际通过后记为 `passed`。环境需说明镜像摘要、API、设备架构、网络拓扑和参与端安装包摘要。`raw_evidence` 至少包含原始证据的 `location` 和 `sha256`；原始日志、截图、抓包和结果文件不得被汇总覆盖。审阅者必须核对用例集合与功能/UI 矩阵，脚本不能单独证明测试实际发生或矩阵完整。

| 门槛 | 范围及附加字段 |
| --- | --- |
| `x64_api35` | 正式签名 x64 最终包的全功能验收 |
| `x64_api26` | 同一最终包的最低版本兼容回归 |
| `arm64_build_signature` | 构建、持续签名和包内容；`metrics.runtime_scope` 必须是 `not_run`、`smoke` 或 `full`，据实区别运行覆盖 |
| `gemini_screenshots` | 全部适用实际截图；`applicable`、`reviewed`、`approved` 相等且大于零，`blocking=0`、`actual_images_read=true`；附原始 Gemini 读取/结论及矩阵证据 |
| `v9_x64_migration` | 同源、同证书 9.0.0 x64 升级、数据保留和备份回退；说明与历史 arm64 包的差别 |
| `vm_tests` | 与真实 Windows 被控端、服务器的全部远控授权、输入、文件、剪贴板、音频和隧道/管理回归 |
| `udp_network` | IPv4/IPv6、NAT、UDP 阻断和故障网络；抓包/候选路径验证，`server_relay_payload_bytes=0` |
| `stability` | `consecutive_connections>=30`、`connection_failures=0`、`active_seconds>=7200`、`online_seconds>=86400`、`input_release_ms<=2000`、`recovery_or_retry_ms<=30000`；对应持续观测原始证据 |
| `performance` | 固定工况 9/10 首帧、延迟、帧率、资源及吞吐比较，保留测量方式和数据 |

各门槛不能用 SDK 编译通过、JVM 测试或单独音频探针替代。开发调试包的测试和跳过真实远端的 instrumentation 只能作为开发证据。所有摘要均绑定正式候选；候选改变后须重新验证受影响范围并形成新的完整记录。

发布保留原始构建清单的 `acceptance_complete=false`，附加独立验收清单和入口仓库来源记录。发布总清单覆盖二者；不改写已签名构建记录，也不把 arm64 构建通过描述为全功能运行通过。
