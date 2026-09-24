# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.4] - 2026-09-24

### 修复

- **登录 WebView**：改用纯 Chrome 手机 UA（去掉系统 `wv` 标记），站点不再拒绝加载登录页；开启 DOM / 混合内容 / 宽视口 / 缩放
- **用浏览器打开**：改用系统 Chooser，并增加底部主按钮；非 http(s) 链接也会转交系统
- **硅基流动**：`/v1/user/info` 返回 410（endpoint deprecated）时自动回退到控制台余额接口，并提示改用「登录取数」
- **版本号**：安装包 versionName 与 Release 标签对齐

### 新增

- **液态玻璃 UI**：卡片 / 底栏 / 背景采用半透明玻璃 + 高光描边 + 顶部微亮渐变
- **底栏仅图标**：去掉「主页 / 添加 / 设置」文字

## [1.2.0] - 2026-09-23

### 新增

- **真实厂商 Logo**：账户列表、详情与模板目录全部替换为官网官方图标
  - 覆盖小米、火山方舟、Kimi、智谱、MiniMax、OpenCode Go、DeepSeek、硅基流动、阶跃 StepFun、Novita AI、Claude、Gemini、OpenAI、OpenRouter
  - generic 模板使用中性图标

### 修复

- **系统返回手势**：适配边缘返回，设置项改为实时生效

## [1.1.0] - 2026-09-23

### 修复

- **MiniMax**：额度读取改用官方当前字段，修复部分账号额度长期显示为空的问题
- **OpenCode Go**：适配新的用量接口结构；Key 有效但未订阅时给出明确提示
- **Kimi**：5 小时窗口完整列出，不再只显示第一条
- **智谱 GLM**：5 小时 / 每周窗口判定更准确；支持团队版（填写组织 ID 与项目 ID 即可）
- **火山方舟**：自动识别 Agent Plan / Coding Plan，无需再手动选择；AK/SK 支持直接粘贴控制台复制的值
- **设置生效**：自动刷新间隔、启动自动查询、默认超时现在都会真正生效
- **浅色主题**：状态栏与导航栏图标对比度修正
- **云函数**：修复填写本机地址（如 `http://127.0.0.1:8787`）无法访问的问题
- **隐私**：云端备份不再包含 API Key / Cookie / AK-SK

### 新增

- **应用内登录取数**：在应用内登录后直接读取控制台接口，无需手动粘贴 Cookie
- **自定义提取器**：支持为厂商配置 JS 提取规则，解析失败自动回退内置解析
- **首页仪表盘**：账户数、成功数、最近同步时间、待排查数量
- **厂商字段**：展示赠送额度、充值余额、账户状态等厂商特有信息
- **新增厂商**：StepFun、Novita AI
- **桌面小组件**：可显示多个账户、点击直接进入应用，刷新间隔跟随设置
- **多语言**：界面文案全部跟随系统语言（简体中文 / English）

### 已知限制

- 「登录取数」类账户需要在应用内登录，后台定时刷新不会更新这类账户
- 各厂商接口字段可能随官方调整而变化，如遇数据异常欢迎在 Issues 反馈

## [1.0.0] - 2026-09-23

首个公开版本。

### 新增

- **多厂商账户管理** — 支持重复添加同一厂商（多账号），首页仅展示已添加账户
- **按厂商裁剪的查询方式**，不套统一模板：
  - 官方 API：DeepSeek、OpenRouter、SiliconFlow、Kimi、智谱 GLM、MiniMax
  - AK/SK 签名：火山方舟 Agent Plan / Coding Plan（本地 HMAC-SHA256 签名）
  - 云函数 JSON：可配置可选鉴权（无 / Bearer / 自定义 Header / Query 参数）
  - 登录拉取：Claude、Gemini、OpenAI、小米 MiMo
- **同屏展示** — 按量余额与订阅额度合并展示；无数据的字段不显示占位
- **多周期额度** — 5 小时 / 周 / 月窗口，各自显示已用比例、剩余百分比与重置时间
- **桌面小组件** — Glance 实现，2×2 起，后台定时刷新
- **主题** — 跟随系统 / 深色 / 浅色
- **多语言** — 简体中文、English，语言跟随系统
- **数据管理** — JSON 导入导出、清空账户

### 说明

- 查询由设备直连厂商接口发起，不经过任何第三方服务器

### 已知限制

- 部分厂商（如小米 MiMo）控制台接口未公开，登录拉取需填写真实的 JSON 接口地址，或改用云函数
- 火山 Agent / Coding Plan 需使用账号级 AK/SK（非推理 API Key）
- 智谱团队版需额外填写组织 ID / 项目 ID

[1.2.4]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.4
[1.2.0]: https://github.com/Eason4869/quota-board/releases/tag/v1.2.0
[1.1.0]: https://github.com/Eason4869/quota-board/releases/tag/v1.1.0
[1.0.0]: https://github.com/Eason4869/quota-board/releases/tag/v1.0.0
