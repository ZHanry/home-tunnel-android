# 参与开发

本仓库负责Android 界面、会话与远程连接管理。整体架构与任务归属见[项目总览](https://github.com/ZHanry/home-tunnel)。

## 提交方式

1. 从 `main` 建立聚焦单一问题的分支。
2. 在 PR 中说明问题、修改后的行为与验证结果。
3. 行为变化更新相关测试；文档、接口或配置变化更新对应说明。
4. 跨组件改动列出相关仓库与提交，完成所需联调后再合入。

## 开发环境和检查

使用 JDK 17、Android SDK Platform 35 和 Build Tools 35。

```sh
./gradlew --no-daemon test lint assembleDebug
```

Windows 使用 `gradlew.bat`。普通贡献不需要发布密钥。
UI 或会话改动同时检查旋转屏幕、切换后台、断网恢复与登录失效场景，注明设备和系统版本。

## 约定

- 内部测试期间可以调整接口和配置，但需说明影响和重新验证方式。
- 安全相关改动说明身份、租约、Agent 配置校验或公开入口的影响。
- 不提交密钥、设备状态、测试机私有配置或生成的安装包。
- 使用代码仓库自己的 CI 与发布流程，不要求相邻检出另一个源码仓库。

疑似漏洞使用 [SECURITY.md](SECURITY.md) 中的私密入口。提交内容按 [Apache-2.0](LICENSE) 分发。
