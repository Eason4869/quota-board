# 额度台 · QuotaBoard

多厂商 AI 额度看板 · Android App

把各厂商的订阅额度、按量余额与用量窗口集中到一屏，支持跟随系统的中英文界面与深浅色主题。

> 作者：**彧晟Eason** · 许可：MIT

---

## 🧪 测试版说明

**当前是测试版（1.2.x）**，功能与界面都还在快速调整：可能有 bug，数据格式也可能随版本变化，部分厂商接口的解析会跟着官方改动而失效。

- 用之前建议先用「设置 → 备份与恢复 → 导出」存一份 JSON 备份，换版本装不回去时能捞回来。
- 遇到问题、有想加的功能，**欢迎直接提 [Issue](https://github.com/Eason4869/quota-board/issues)**，看到就会跟。
- 提 Issue 时带上 **手机型号 / Android 版本 / 应用版本号**（设置页底部有），再附一张截图或完整错误提示，能省很多来回。
- 想自己动手也欢迎 PR，编译方式见文末。

## 功能

- 多厂商账户，同一厂商可添加多个账号
- 按量余额与订阅额度同屏展示，5 小时 / 周 / 月窗口各自显示已用比例、剩余百分比与重置时间
- 自动刷新：间隔可设置，也可在启动时自动查询
- 数据只保存在本机，支持 JSON 导入导出

## 支持的厂商

| 查询方式 | 厂商 |
|------|------|
| 官方 API | DeepSeek、OpenRouter、SiliconFlow、StepFun、Novita、Kimi、智谱 GLM、MiniMax、OpenCode Go、Moonshot、千问 Token Plan |
| 订阅用量（令牌） | ChatGPT / Codex、Claude、Gemini、GitHub Copilot |
| AK/SK 签名 | 火山方舟 Agent Plan / Coding Plan |
| 云函数 | 任意厂商（自建函数或代理） |
| 登录拉取 | 小米 MiMo（额度只在登录态下可取）；其余厂商也可切换到该方式 |

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

两点提示：

- **云函数**：网络受限、需要固定出口 IP，或希望凭证只放服务端时，把查询放到自己的函数里，地址填函数 URL 即可。
- **登录拉取**：在应用内打开登录页，登录后点「抓取额度」，不需要手动复制 Cookie。

## 安装

到 [Releases](https://github.com/Eason4869/quota-board/releases) 下载最新的 `app-release.apk`，在手机上安装即可（需要允许安装未知来源应用）。要求 Android 8.0 及以上。

## 编译

需要 JDK 17 与 Android SDK。若命令行提示找不到 SDK，请在工程根目录的 `local.properties` 里写一行 `sdk.dir=<你的 Android SDK 路径>`（Android Studio 会自动生成该文件）。

```bash
./gradlew assembleDebug      # 产物 app/build/outputs/apk/debug/
./gradlew assembleRelease    # 产物 app/build/outputs/apk/release/
```

## License

[MIT](LICENSE) © 2026 彧晟Eason
