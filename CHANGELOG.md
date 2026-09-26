# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.33] - 2026-09-26

- 修复深色主题下登录页「黑屏」：新版 WebView 会把应用的暗色主题透传给页面，小米登录页的暗色样式是纯黑背景加 30% 透明度的控件——页面其实加载成功，用户看到却是一片黑。登录页 WebView 现在固定以浅色（NIGHT_NO）配置创建，表单永远清晰可读
- 诊断探针新增 `bg=` 字段（页面实际渲染的背景色），下次再遇到「黑屏」能直接区分是渲黑了还是没画出来

## [1.2.32] - 2026-09-26

- 修复 Gemini 查询 401：access token 约 1 小时就过期，现在令牌框支持粘贴 `~/.gemini/oauth_creds.json` 的完整内容或其中 `1//` 开头的 refresh_token，查询时自动续期（gemini-cli 公开 OAuth 客户端，常量已对照源码与端点验证）
- Gemini 新增 gemini-cli 现行配额路由 `v1internal:retrieveUserQuota` 作为候选
- 粘贴凭证时额外清洗 `Bearer:` 前缀；401 的报错会明确说明 token 已过期与可持续粘贴的方式

## [1.2.30] - 2026-09-26

- 修复小米 MiMo 登录拉取「黑屏打不开 / 登录成功却抓不到数」：
  - 登录页不再先加载控制台深链（重 SPA，老内核整段脚本不执行、低内存设备可能渲染进程崩溃循环），改为直接等额度接口 401 回包里的 `loginUrl`，接口不可达才回退深链
  - SSO 登录成功后 sts 会 302 到 followup 参数里的 `http://` 明文地址（接口实测如此），被系统明文禁令拦成 `ERR_CLEARTEXT_NOT_PERMITTED` 错误页——查询域的明文跳转统一升级为 https（含不走 `shouldOverrideUrlLoading` 的 POST 重定向兜底）
  - 登录完成（落回平台域 + 会话 Cookie 出现）后自动抓取三个额度接口，成功直接关闭登录页，失败保留页面供手动重试
  - 新页面开始加载时清掉上一轮的「无响应 / 卡住 / 空白」误报

## [1.2.29] - 2026-09-26

- 修复登录页异步查询结果丢失、取消后残留请求的问题
- 备份导入校验账户列表与 ID，避免错误文件清空账户或重复 ID 导致首页崩溃
- 修正 Novita 余额单位；MiMo 查询按账户使用各自保存的登录 Cookie
- 修复字段映射被内置模板忽略、旧查询覆盖新配置结果的问题
- 自动刷新关闭时，自动更新检查开关仍然生效

## [1.2.28] - 2026-09-24

- 修复深色模式下 Moonshot / Copilot 图标看不见：改用浅色底 + 白色图标
- 厂商列表把 Moonshot 排到 Kimi 旁边

## [1.2.27] - 2026-09-24

- 修复拖动排序不跟手、闪动：位移改为按指针位置实时重算，换位按实际行距计算
- 跟手改为对准抓取点，拖到屏幕边缘时卡片限制在视口内
- 千问：GET 被网关回 400 时自动补试 POST

## [1.2.26] - 2026-09-24

- 千问：去掉已确认失效的探测路径，查询失败只列出真正可用的候选地址

## [1.2.25] - 2026-09-24

- 新增厂商：千问 Token Plan（网关与控制台接口自动探测），使用官方图标
- 拖动排序：卡片左侧点阵手柄、拖动中描边，其余卡片平滑让位
- 小米详情页显示登录必需的 Cookie 字段，点按整段复制

## [1.2.24] - 2026-09-24

- 拖动排序加长按震动反馈，拖动中卡片浮起

## [1.2.23] - 2026-09-24

- 自动检测更新默认每小时一次，不再跟启动触发
- 拖动排序改为即时重排 + 延迟写盘，消除卡顿

## [1.2.22] - 2026-09-24

- 新增厂商：GitHub Copilot（`copilot_internal/user`）、月之暗面 Moonshot（余额）
- 两家都用官方矢量图标；厂商目录把新增项排在最后，「通用」固定最后一位
- Novita 改用现行账单接口，旧接口自动兜底（金额单位自动区分）
- 账户卡片支持拖动排序（首页长按卡片拖动）
- 更新检测改为后台定时（每 6 小时），不再跟启动触发

## [1.2.21] - 2026-09-24

- Gemini 默认改用配额接口：粘贴 OAuth token（+ 项目 ID），不再依赖登录页
- MiniMax 改用官方现行路由 `/v1/token_plan/remains`，旧路由自动兜底
- Kimi 支持新账号的 `usages` 比例映射（月度池 `limit_month_total`）

## [1.2.20] - 2026-09-24

- 移除全部弹出提示（查询结果、已是最新版等），导入失败改为字段下方红字
- 移除手机 / 桌面切换，固定手机 UA
- Claude 默认改用 OAuth 用量接口：粘贴 Claude Code 的 access token
- ChatGPT / Codex 默认改用官方用量接口：粘贴 access token + 账号 ID，不再依赖登录页

## [1.2.19] - 2026-09-24

- 登录页顶栏改为图标：适应宽度 / 手机·桌面 / 浏览器 / 刷新 / 全屏
- 默认手机 UA；打开 SSO 站点（小米、Google）时自动切回手机版
- 关闭查询成功 / 失败提示与自动更新弹窗，更新只在「关于」页手动检查
- 修复 Claude、Gemini 登录过程中点击导致闪退：WebView 全部回调加异常保护
- 诊断新增页面正文与 iframe 信息，用于定位小米验证码页

## [1.2.18] - 2026-09-24

- 修复验证码页黑屏 / 显示不全：键盘弹起时收起底部面板（edge-to-edge 下窗口不随键盘缩放）
- 登录页补 `adjustResize`
- 页面横向溢出时自动按宽度缩放（CSS zoom，不重载、不清空已填内容）
- 诊断新增 `ime` / `chrome` / `view_h`

## [1.2.17] - 2026-09-24

- 登录页改用厂商接口返回的 `loginUrl`（未登录回 `401 + loginUrl`），不再猜控制台深链
- 小米 MiMo 接入 `tokenPlan/usage`、`tokenPlan/detail`、`balance`
- HttpOnly 会话改由隐藏 WebView 取数，登录后普通查询无需再开登录页
- 诊断区新增「更新内核」按钮
- 小米模板默认地址改为额度接口，老账户自动兼容

## [1.2.16] - 2026-09-24

- 白屏分三类并分别提示：没连上 / 卡在半路 / 页面自身资源加载失败
- 诊断改为「重新读一次页面状态再复制」，含 `readyState`、正文长度、根节点子元素、脚本与失败资源、页面 JS 错误
- 控制台消息记录任意级别前 3 条

## [1.2.15] - 2026-09-24

- 白屏定性：控制台入口 bundle 需 Chrome 85+，内核过低时主脚本整段不执行
- 显示内核真实版本并与下限比对；命中语法错误时翻译为「主脚本执行失败」
- 火山登录页改为真正的登录页（原为控制台深链），老账户打开时统一归一化
- UA 版本号跟随内核，仅去掉 `wv` 与 `Version/x.y`

## [1.2.14] - 2026-09-24

- 导出 / 导入移到「设置 → 备份与恢复」
- Dock 接入 Haze 真模糊（API 32+，31 及以下退回色纱）
- 修 Dock 底部等亮描边
- 登录失败不再静默：证书 / HTTP / 网络错误写入状态行，新增 10 秒无回调看门狗
- 诊断信息可复制，logcat 统一 tag `QuotaLogin`

## [1.2.13] - 2026-09-24

- 新图标：线条化刻度盘（270° 圆环 + 橙色指针）
- 应用内更新改为镜像兜底链（直连 → 加速镜像 → 解析 `releases/latest`），校验体积与 ZIP 头
- 登录页移出 alpha 动画（透明度图层会把 WebView 画成黑屏）
- 「适应宽度」改用 CSS zoom，不再重载页面
- WebView 显式销毁；渲染进程崩溃后可回到崩溃前页面
- 查询只按 id 合并写回结果字段，不再用旧快照覆盖用户改动
- 账户列表逐条解码，坏数据跳过并备份原文
- Dock 长度改按屏宽比例
- 移除桌面小组件及相关权限 / 依赖

## [1.2.12] - 2026-09-24

- Dock 胶囊长度取中（最小宽度 180dp、内边距 8dp）

## [1.2.11] - 2026-09-24

- Cookie / Key 粘贴自动去掉 `Cookie:`、`Authorization:` 前缀与换行
- 登录页空白提示改为给出可执行下一步

## [1.2.10] - 2026-09-24

- 关闭 WebView 算法暗色 / 强制暗色（会把不支持暗色的站点整页刷黑）
- WebView 显式白色背景，区分「页面没画出来」与「WebView 没渲染」
- 诊断显示内核版本与首条脚本错误；内核缺失时明确提示
- Cookie / API Key 字段新增「粘贴」按钮
- Dock 降低不透明度

## [1.2.9] - 2026-09-24

- 应用内自动更新：应用内下载 APK 并调起系统安装器，含「安装未知应用」授权引导
- 硅基流动国内站接口下线（有效 Key 返回 410）时，一键改用登录取数
- Dock 玻璃更通透，内容从 Dock 下方穿过
- 修复硅基流动控制台返回结构差异导致余额解析失败

## [1.2.8] - 2026-09-24

- 关于页移除更新日志区块
- 底栏改为悬浮玻璃胶囊，四周透明
- 顶栏与 Dock 让开系统状态栏与手势条
- 登录页新增「适应宽度」、全屏模式、空白页检测

## [1.2.7] - 2026-09-24

- 登录页支持弹窗式登录；渲染进程被回收后自动重建
- 桌面版 / 手机版一键切换
- Google 登录被拒时给出提示
- 硅基流动国内站 / 国际站域名自动依次尝试，修正 401 被误判为 410
- 底栏加导航栏内边距
- 新增页面切换、用量条增长、底栏选中态动效

## [1.2.6] - 2026-09-24

- 请求体统一使用空字符串，避免个别接口对 `null` body 报错

## [1.2.5] - 2026-09-24

- 补齐缺失的 `siliconFlowWithFallback` 定义，修复编译失败

## [1.2.4] - 2026-09-24

- 登录 WebView 改用纯 Chrome 手机 UA（去掉 `wv` 标记）
- 「用浏览器打开」改用系统 Chooser
- 底栏仅图标
- 新增液态玻璃 UI（卡片 / 底栏 / 背景）

## [1.2.3] - 2026-09-24

- 修复图标导入冲突、`Glass` 重复声明、`openInBrowser` 缺失导致的编译失败

## [1.2.2] - 2026-09-24

- 去除重复的字符串资源；版本号与 Release 标签对齐

## [1.2.1] - 2026-09-24

- 新增液态玻璃底栏（主页 / 添加 / 设置）
- 新增应用内更新检查
- 调整厂商目录排序
- 登录 WebView 使用移动端 UA

## [1.2.0] - 2026-09-23

- 账户列表、详情与模板目录替换为官网 Logo
- 适配系统返回手势；设置项改为实时生效

## [1.1.1] - 2026-09-23

- Release 与 CI 统一使用 release keystore 签名
- 返回键 / 边缘滑动返回按页面层级回退
- 登录页为状态栏与导航栏留出安全区域
- 设置项修改即生效，去掉「保存」按钮与提示

## [1.1.0] - 2026-09-23

- MiniMax 额度改用官方当前字段
- OpenCode Go 适配新用量接口
- Kimi 5 小时窗口完整列出
- 智谱 GLM 窗口判定修正，支持团队版
- 火山方舟自动识别 Agent / Coding Plan
- 应用内登录取数；自定义 JS 提取器
- 首页仪表盘；厂商字段展示；新增 StepFun、Novita AI

## [1.0.0] - 2026-09-23

首个公开版本。

- 多厂商账户管理，同一厂商可多账号
- 按厂商裁剪的查询方式：官方 API / AK-SK 签名 / 云函数 / 登录拉取
- 按量与订阅同屏展示，5 小时 / 周 / 月窗口
- 桌面小组件、跟随系统主题、中英双语、JSON 导入导出

[1.2.22]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.22
[1.2.21]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.21
[1.2.20]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.20
[1.2.19]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.19
[1.2.18]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.18
[1.2.17]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.17
[1.2.16]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.16
[1.2.15]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.15
[1.2.14]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.14
[1.2.13]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.13
[1.2.12]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.12
[1.2.11]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.11
[1.2.10]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.10
[1.2.9]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.9
[1.2.8]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.8
[1.2.7]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.7
[1.2.6]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.6
[1.2.5]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.5
[1.2.4]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.4
[1.2.3]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.3
[1.2.2]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.2
[1.2.1]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.1
[1.2.0]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.0
[1.1.1]: https://github.com/Eason4869/quota-board/releases/tag/v1.1.1
[1.1.0]: https://github.com/Eason4869/quota-board/releases/tag/v1.1.0
[1.0.0]: https://github.com/Eason4869/quota-board/releases/tag/v1.0.0
