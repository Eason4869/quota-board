# 额度台 · QuotaBoard

多厂商 AI 额度看板 · Android App

一个把「Token Plan 订阅额度 + 按量余额 + 5 小时/周/月用量窗口」集中到一屏的安卓应用，支持桌面小组件、多语言（跟随系统）、深浅色主题。

> 作者：**彧晟Eason** · 许可：MIT

---

## 功能

- **多厂商账户** — 同一厂商可重复添加（多账号），列表只显示已添加的账户
- **首页仪表盘** — 账户数 / 成功数 / 最近同步时间 / 待排查数一眼可见
- **按厂商裁剪的查询方式** — 不同厂商能力差异大，不套统一模板：
  | 方式 | 说明 | 适用 |
  |------|------|------|
  | 官方 API | 直接用厂商 Key 查余额/额度 | DeepSeek、OpenRouter、SiliconFlow、StepFun、Novita、Kimi、智谱 GLM、MiniMax、OpenCode Go |
  | AK/SK 签名 | 本地 HMAC-SHA256 签名（火山 Sign V4），**自动探测** Agent / Coding Plan | 火山方舟 Agent Plan / Coding Plan |
  | 云函数 JSON | 调你自己的函数/代理，可带任意鉴权 | 任意厂商、需要登录态的场景 |
  | 登录拉取 | **应用内 WebView 登录**：抓 Cookie，并在页面里直接 fetch 额度接口 | Claude、Gemini、OpenAI、小米 MiMo |
- **同屏展示** — 按量与订阅合页；有什么数据画什么，不填占位
- **厂商字段** — `granted` / `cash` / `status` 这类厂商特有字段在详情页直接列出
- **自定义提取器** — 配置里的 JS 提取器在沙箱 WebView 中执行，失败自动回退内置解析
- **桌面小组件** — Glance 实现，长按桌面 → 小组件 → 额度台（刷新间隔跟随设置，0 = 关）
- **多语言** — 中文 / English，跟随系统（模式名、窗口名、提示全部走资源）
- **主题** — 跟随系统 / 深色 / 浅色
- **隐私** — 数据不出设备；Key 与 Cookie 只存本机，支持 JSON 导入导出（云端备份已排除凭证文件）

---

## 什么时候用「云函数」

应用默认由设备直接请求各厂商接口。如果遇到这些情况，可以把查询放到你自己的函数或代理里：

- 网络环境不允许设备直连厂商接口
- 希望用固定的出口 IP 发起请求
- 希望凭证只保存在服务端，设备上只留一个地址

使用方式：

1. 该账户的查询方式选 **云函数 JSON**
2. 地址填你的函数 URL
3. 按需配置鉴权（无 / Bearer / 自定义 Header / Query 参数）

函数返回 JSON 即可；返回结构不标准时，可以用账户配置里的「余额字段路径 / 订阅剩余路径」手动指定字段位置。
本机地址 `http://127.0.0.1` 与模拟器宿主地址 `10.0.2.2` 已在
`res/xml/network_security_config.xml` 中放行明文访问。

---

## 登录拉取怎么用

控制台接口的会话多带 `HttpOnly`，直接抓 Cookie 是拿不到的。所以 App 走两条路：

1. 应用内 WebView 打开登录页，登录完成后从 Cookie Jar 抓 Cookie 存进配置；
2. **点「抓取额度」**：在**该页面上下文里**请求额度接口（同源、自动带登录态），
   拿回的 JSON 交给同一套解析器入库 —— 这是 HttpOnly 会话唯一可行的取数方式。

后台定时刷新（WorkManager）无法弹 WebView，这类账户只在 App 内刷新。

---

## 编译

### 环境
- JDK 17
- Android SDK（compileSdk 35，AGP 8.7.3 需 Gradle 8.9+）

### 命令行

```bash
git clone https://github.com/Eason4869/quota-board.git
cd quota-board

# 构建
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew assembleRelease      # 产物 app/build/outputs/apk/release/
```

若命令行提示找不到 SDK，请在工程根目录的 `local.properties` 里写一行
`sdk.dir=<你的 Android SDK 路径>`（Android Studio 会自动生成该文件）。

### Android Studio
直接 `Open` 本目录，等待 Gradle Sync 完成后运行。

### 签名发布
`app/build.gradle.kts` 的 release 默认使用 debug 签名，便于开源分发。
正式上架请替换为自己的 keystore：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("your.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
```

---

## 自动构建 APK

仓库已配置 GitHub Actions（`.github/workflows/build.yml`）：

- push 到 `main` / 提 PR → 构建 debug APK 并作为 artifact 上传
- 打 `v*` tag → 构建 release APK 并创建 GitHub Release

因此**不需要本地 Android SDK**，推代码即可拿到 APK。

---

## 接入新厂商

1. 在 `data/Templates.kt` 增加模板（id、名称资源、默认查询方式、默认 URL）
2. 在 `net/Parsers.kt` 增加该厂商的响应解析（窗口标签统一用 `5h` / `weekly` / `monthly` / `daily`）
3. 补 `strings.xml` 与 `values-zh-rCN/strings.xml` 中的名称/描述（中英键必须一一对应）

---

## 目录结构

```
app/src/main/java/com/yusheng/quota/
├── MainActivity.kt              入口（含状态栏对比度）
├── QuotaApp.kt                  Application（持有 Store）
├── data/
│   ├── Model.kt                 Account / QueryConfig / QueryResult
│   ├── Templates.kt             各厂商模板（能力裁剪）
│   ├── Store.kt                 本地存储
│   └── ImportCodec.kt           导入导出
├── net/
│   ├── QueryEngine.kt           原生 HTTP + 各查询方式 + HTML 内嵌 JSON 兜底
│   ├── VolcSigner.kt            火山引擎签名 V4
│   ├── ScriptExtractor.kt       自定义 JS 提取器（沙箱 WebView）
│   └── Parsers.kt               各厂商响应解析（对齐 cc-switch）
├── ui/
│   ├── QuotaAppScreen.kt        首页 / 详情 / 厂商目录
│   ├── ConfigAndSettings.kt     查询配置 / 设置 / 关于
│   ├── LoginCaptureScreen.kt    应用内登录 + 页面内取数
│   ├── Components.kt            额度条、卡片等组件
│   └── theme/Theme.kt           主题
└── widget/
    ├── QuotaWidget.kt           Glance 桌面小组件
    └── QuotaWidgetReceiver.kt   接收器 + 定时刷新 Worker
```

---

## 隐私与合规

- 本应用**不上传**任何数据，所有查询由设备直接发起
- API Key / Cookie / AK-SK 保存在本机 SharedPreferences；云端备份已排除该文件，导出 JSON 时请自行妥善保管
- 请遵守各厂商服务条款，勿将订阅额度用于非许可场景

---

## License

[MIT](LICENSE) © 2026 彧晟Eason

---

## 致谢

多厂商额度接口的字段与判定方式参考了开源项目
[cc-switch](https://github.com/farion1231/cc-switch)（MIT）的实现，
包括火山引擎签名 V4 的请求头顺序、MiniMax / Kimi / 智谱 / OpenCode Go 的用量字段解析。
