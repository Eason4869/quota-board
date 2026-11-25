# 厂商查询核对（2026-11-25）

核对对象为项目中全部 18 个具体厂商模板（火山 Agent / Coding 分列），不含用户自定义的通用模板。参考 [cc-switch](https://github.com/farion1231/cc-switch) main 的 `services/balance.rs`、`services/coding_plan.rs`、`services/agy_account/quota.rs` 与官方账户查询实现；cc-switch 没有覆盖的入口继续以厂商公开协议为依据。

参考版本：[cc-switch e6fd4acc](https://github.com/farion1231/cc-switch/tree/e6fd4accd257406bf9141baa402caaa78f975ba1)。

这里的“核对”指源代码、接口文档、响应夹具和本地 HTTP 测试；无真实账户凭证，不代表所有厂商完成了真实额度查询。

| 模板 | 查询入口 / 鉴权 | 本次处理 |
| --- | --- | --- |
| 小米 MiMo | `api.xiaomimimo.com/v1/platform/balance`；`token-plan-cn.xiaomimimo.com/v1/platform/token_plan`；Bearer Key | 默认改为 API；`tp-` 自动选 Token Plan；冻结金额、积分单位和 UTC 周期对齐；保留 Cookie 登录 |
| DeepSeek | `api.deepseek.com/user/balance`；Bearer Key | 地址和鉴权一致；无有效余额字段时报错，避免错误响应显示成零 |
| OpenRouter | `openrouter.ai/api/v1/credits`；Bearer Key | 地址和鉴权一致；保留负余额，不再钳制成零；校验有效金额 |
| SiliconFlow | `api.siliconflow.cn` / `.com` 的 `/v1/user/info`；Bearer Key | 保留国内 / 国际入口与 Cookie 备用；校验回包后才成功；国际站显示 USD |
| StepFun | `api.stepfun.com/v1/accounts`；Bearer Key | 地址和鉴权一致；缺失余额时报错 |
| Novita | `api.novita.ai/openapi/v1/billing/balance/detail`；备用 `/v3/user/balance`；Bearer Key | 保留现行与 cc-switch 入口；校验回包后才成功；支持 data 包裹；金额仍除以 10000 |
| Kimi Coding | `api.kimi.com/coding/v1/usages`；Bearer Key | 与 cc-switch 一致；已有绝对额度及月度比例结构兼容保留 |
| Moonshot | `api.moonshot.cn/v1/users/me/balance`；Bearer Key | 官方按量余额入口保留；缺失余额时报错 |
| 智谱 GLM | `/api/monitor/usage/quota/limit`；Authorization 裸 Key；团队模式 `type=2` 与组织 / 项目头 | 仅个人账户明确“无 Coding Plan”时回退 `/api/biz/account/query-customer-account-report`；其他错误保持失败；优先 availableBalance |
| MiniMax | `api.minimaxi.com` / `api.minimax.io` 的 `/v1/token_plan/remains`；备用 `/v1/api/openplatform/coding_plan/remains`；Bearer Key | 保留新旧接口；修正国内新推理域名的查询路由；业务失败 / 无有效额度时继续备用入口；耗尽周窗口 status=2 仍显示 |
| OpenCode Go | `opencode.ai/zen/go/v1/usage`；Bearer Key | 与 cc-switch 一致；继续保留零用量时不显示占位重置时间 |
| 火山 Agent Plan | `open.volcengineapi.com`，`GetAFPUsage`，版本 `2024-01-01`；AK/SK 签名 | 与 cc-switch 的签名与 Action 一致，保留另一套餐探测及鉴权错误停止机制 |
| 火山 Coding Plan | 同上，`GetCodingPlanUsage` | 同上；继续解析 `QuotaUsage[]` 窗口 |
| Claude | `api.anthropic.com/api/oauth/usage`；Bearer OAuth + `anthropic-beta` | 与官方 OAuth 用量入口一致；不是普通模型 API Key |
| Gemini / Antigravity | `daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary`，备用 `retrieveUserQuota`；Bearer OAuth | 缺省项目先尝试 loadCodeAssist；支持现行 groups[].buckets[]，保留旧 buckets[]；区分模型额度池；无有效额度时继续备用入口 |
| ChatGPT / Codex | `chatgpt.com/backend-api/wham/usage`；Bearer OAuth + 账号 ID | 入口与 cc-switch 一致；现有设备授权、续期、备用 codex/usage 及账户代理保留 |
| GitHub Copilot | `api.github.com/copilot_internal/user`；token 前缀与客户端请求头 | 与 cc-switch 一致；保留 quota_snapshots 解析 |
| 千问 Token Plan | 千问 CLI 设备授权与账单实例接口 | cc-switch 的 Coding Plan 实现未提供此查询；保留既有官方 CLI 流程，不将模型 Key 发送给账单接口 |

MiniMax 和 Novita 的新旧地址均能在无凭证探测中返回鉴权响应；这只能确认路由存在。MiMo 两个官方 API 无 Key 均返回 HTTP 401，符合接口文档。

参考：[MiMo 积分](https://mimo.mi.com/docs/zh-CN/api/asset/credits-get)、[MiMo 余额](https://mimo.mi.com/docs/zh-CN/api/asset/balance-get)、[MiniMax Token Plan](https://platform.minimax.io/subscribe/token-plan)。
