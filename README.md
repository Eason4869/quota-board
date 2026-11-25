# 额度台 · QuotaBoard

多厂商 AI 额度看板 · Android App

把各厂商的订阅额度、按量余额与用量窗口集中到一屏，支持跟随系统的中英文界面与深浅色主题。

> 作者：**彧晟Eason** · 许可：MIT

---

## 当前版本

**[v1.4.1](https://github.com/Eason4869/quota-board/releases/tag/v1.4.1)**：小米 MiMo 默认使用 API Key 查询 Token Plan 积分或按量余额；核对全部厂商入口，修复 Gemini 用量结构、MiniMax 耗尽周额度、智谱按量回退及余额错误显示。厂商接口可能随官方改动而失效，真实额度以官方页面为准。版本变更见 [CHANGELOG](CHANGELOG.md)，逐厂商核对见 [查询入口说明](docs/provider-query-audit.md)。

- 用之前建议先用「设置 → 备份与恢复 → 导出」存一份 JSON 备份，换版本装不回去时能捞回来。
- 遇到问题、有想加的功能，**欢迎直接提 [Issue](https://github.com/Eason4869/quota-board/issues)**，看到就会跟。
- 提 Issue 时带上 **手机型号 / Android 版本 / 应用版本号**（设置页底部有），再附一张截图或完整错误提示，能省很多来回。
- 想自己动手也欢迎 PR，编译方式见文末。

## 功能

- 多厂商账户，同一厂商可添加多个账号
- 按量余额与订阅额度同屏展示，5 小时 / 周 / 月窗口各自显示已用比例、剩余百分比与重置时间
- 自动刷新：间隔可设置，也可在启动时自动查询
- 数据只保存在本机，支持 JSON 导入导出
- 安卓桌面小组件：固定 2×2 / 4×2，可编辑选择已有 AI 源；余额类展示大字余额，额度类展示剩余百分比、进度条及重置时间；支持手动与后台刷新，失败保留上次结果并用小字提示
- Codex 账户级代理：支持 HTTP / SOCKS5，留空沿用系统网络（需要自备可用出口）
- Codex OAuth：系统浏览器设备授权、自动续期；账户详情与 4×2 小组件显示接口返回的剩余重置卡数量

## 支持的厂商

| 查询方式 | 厂商 |
|------|------|
| 官方 API | 小米 MiMo、DeepSeek、OpenRouter、SiliconFlow、StepFun、Novita、Kimi、智谱 GLM、MiniMax、OpenCode Go、Moonshot |
| 订阅用量（令牌） | ChatGPT / Codex、Claude、Gemini、GitHub Copilot |
| AK/SK 签名 | 火山方舟 Agent Plan / Coding Plan |
| 云函数 | 任意厂商（自建函数或代理） |
| 浏览器授权 / 登录拉取 | 小米 MiMo、千问 Token Plan；其余厂商可在系统浏览器登录后导入额度接口的 Cookie |

## 界面预览

<!--
  把截图放入 docs/screenshots/（文件名见该目录下的说明），
  然后删掉这行与下面这段的注释标记，README 就会显示预览图。
-->
<!--
<p align="center">
  <img src="docs/screenshots/home.png" width="23%" alt="首页">
  <img src="docs/screenshots/detail.png" width="23%" alt="账户详情">
  <img src="docs/screenshots/catalog.png" width="23%" alt="厂商目录">
</p>
-->

## 使用

1. **首页 +** → 选择厂商 → 按提示填写 Key / AK-SK / 地址
2. 保存后自动查询一次，之后点右上角刷新即可更新
3. 详情页可查看各窗口用量、厂商附加信息与上次查询时间

桌面长按 → 小组件 → 额度台，选择 2×2 或 4×2 并绑定已有账户；点击小组件编辑按钮可更换 AI 源。2×2 最多显示两个额度窗口，4×2 最多三个；未返回重置时间时显示「—」。查询失败保留上次成功数据与时间，底部用小字提示。后台刷新跟随设置中的自动刷新间隔，最短 15 分钟，设置 0 时关闭；系统省电可能延迟任务。圆角跟随 Android 12+ 系统参数，可见卡片顶部略收低，适配小米桌面的视觉间距。不同桌面网格的实际占位由 Launcher 决定。

Codex 的「官方 API」配置可填写 `http://主机:端口` 或 `socks5://主机:端口` 代理地址，暂不支持用户名密码。`127.0.0.1` 指手机本身，电脑代理需要填写手机可访问的电脑局域网地址。不含公用代理或第三方镜像，避免账号令牌泄露。无需 Docker。也可沿用「云函数」模式，在自己控制的服务端查询。代理连接已通过本地 HTTP / SOCKS5 测试，手机实际出口连通性仍需结合网络环境确认。完整行为与验收事项见 [小组件与网络说明](docs/widgets-and-network.md)。

查询方式提示：

- **ChatGPT / Codex**：新账户默认选择「登录」。点击「通过 OAuth 连接 Codex」，复制验证码并在系统浏览器登录、授权，返回应用后保存账户。若提示设备代码授权未开启，请先在 ChatGPT 安全设置中启用。应用会自动续期；授权撤销或 refresh token 失效时，请重新授权。已有「官方 API」账户可直接点击 OAuth 连接按钮迁移；仅手动粘贴的 access token 无法续期。登录模式中的账户代理同时用于设备授权、令牌交换、续期和额度查询。浏览器使用手机自己的网络设置，应用内代理不会修改浏览器代理。
- **Codex 重置卡**：详情页与 4×2 小组件显示官方额度响应的 `rate_limit_reset_credits.available_count`，明确返回 0 时显示 0；未提供时详情显示「接口未提供」。只查询数量，不会使用重置卡。OAuth 凭据也包含在手动 JSON 导出中，请勿分享备份。
- **云函数**：网络受限、需要固定出口 IP，或希望凭证只放服务端时，把查询放到自己的函数里，地址填函数 URL 即可。
- **MiMo（推荐 API Key）**：新账户选择「官方 API」，粘贴 `tp-` 开头的 Token Plan 专属 Key 查询 Credits；按量 Key 查询扣除冻结金额后的可用余额。应用自动选用对应的官方接口。需要两种数据时分别添加两个账户。已有 Cookie 账户仍可使用；切换时只需改为「官方 API」并填写 Key。套餐周期按官方返回显示，月付和年付均不强制当成月度 tokens。详见 [MiMo 查询与登录](docs/mimo-login.md)。
- **Gemini / Antigravity**：使用 OAuth access token 或可续期的 `oauth_creds.json`；未填项目 ID 时尝试自动取得托管项目。兼容现行分组额度与旧模型桶，分别保留不同模型额度池。
- **智谱 / MiniMax**：智谱个人账户明确没有 Coding Plan 时自动查询按量余额，团队版仍需组织与项目 ID；国际站请填写对应的 `api.z.ai` / `api.minimax.io` 查询地址。MiniMax 同时兼容现行 Token Plan 与旧 Coding Plan 入口。
- **千问 Token Plan**：选择「登录」并在系统浏览器完成设备授权。`platform.qianwenai.com` 的模型 API Key 不能查询套餐额度；旧账号若仍为「官方 API」模式，请切换为「登录」。
- **其他登录拉取**：系统浏览器 Cookie 不会自动共享给本应用。登录后需导入额度接口请求中的 Cookie，请勿粘贴账号密码。

## 安装

到 [Releases](https://github.com/Eason4869/quota-board/releases) 下载最新的 `app-release.apk`，在手机上安装即可（需要允许安装未知来源应用）。要求 Android 8.0 及以上。正式发布使用原有发布密钥签名，可覆盖升级此前的正式版；Release 同时提供 `app-release.apk.sha256` 校验文件。

若此前安装的是本地 Debug 测试包，签名与正式版不同：先在「设置 → 备份与恢复 → 导出」保存账户，再卸载测试包、安装正式版并导入备份。备份包含账户凭证，请妥善保管。

## 编译

需要 JDK 17 与 Android SDK。若命令行提示找不到 SDK，请在工程根目录的 `local.properties` 里写一行 `sdk.dir=<你的 Android SDK 路径>`（Android Studio 会自动生成该文件）。

```bash
./gradlew assembleDebug      # 产物 app/build/outputs/apk/debug/
./gradlew assembleRelease    # 产物 app/build/outputs/apk/release/
```

正式签名需要配置 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 环境变量，或根目录的 `keystore.properties`（键名为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。未配置时本地 Release 构建会警告并使用 Debug 签名，不能作为正式发布包。

GitHub Actions 在推送 `v*` tag 时先校验 tag 与 `versionName` 一致，并要求四项签名 Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）齐全；完整测试与 Lint 通过后构建正式 APK，校验证书与此前正式版一致、生成 SHA-256，并以对应 CHANGELOG 章节创建 Release。签名文件和密码不可提交到仓库。

若 tag 构建因发布脚本或环境问题失败，可在最新 `main` 的「Build APK → Run workflow」填写 `release_tag` 重试；工作流会检出该 tag 的源代码并使用最新发布脚本，无需移动既有 tag。留空仅构建 Debug。

## License

[MIT](LICENSE) © 2026 彧晟Eason
