# MiMo 登录与查询

从 v1.4.1 起，新建 MiMo 账户默认使用官方 API Key 查询，方式与 cc-switch 对齐：

| 凭证 | GET 地址 | 展示内容 |
| --- | --- | --- |
| `tp-` 开头的 Token Plan 专属 Key | `https://token-plan-cn.xiaomimimo.com/v1/platform/token_plan` | `quota.total_credits`、`used_credits`、`remaining_credits`，单位 Credits；`period.end_at` 是 UTC 周期结束时间 |
| 按量 API Key | `https://api.xiaomimimo.com/v1/platform/balance` | `balance_infos[].total_available - frozen_balance`，保留实际币种与负余额 |

鉴权为 `Authorization: Bearer <API Key>`。应用根据 Key 类型选择固定官方接口，不向控制台发送模型 Key，也不会在 Key 失败后偷偷切换 Cookie 账户。Token Plan 可能为月付或年付，显示为套餐额度，不把积分当 tokens 或强行标成月度窗口。两个 Key 属于不同查询范围；如需同时查看积分和余额，请分别添加两个 MiMo 账户。

已有 Cookie 账户保持原配置。要切换 Key，在配置页选择「官方 API」，粘贴对应 Key 并保存；无需重新登录。Key 失败时检查是否误用了另一类 Key，或到官方平台重新创建。

依据：[小米套餐余量 API](https://mimo.mi.com/docs/zh-CN/api/asset/credits-get)、[余额 API](https://mimo.mi.com/docs/zh-CN/api/asset/balance-get)，以及 cc-switch 的 `services/coding_plan.rs` / `services/balance.rs`。

以下是保留的 Cookie 登录方式。控制台接口使用平台会话，模型调用 Key 不能替代这些控制台接口的 Cookie。
原来的内置 WebView 在部分设备打开即白屏；当前设备没有连接到开发环境，不能确定其渲染故障的底层原因。

MiMo 入口改为原生界面，使用小米账号官网当前的二维码授权流程：

1. 从额度接口的 401 响应取得官方登录地址，以 JSON 模式读取签名和 callback。
2. 将官方登录参数传给 `/longPolling/loginUrl`，保留 `_json` 等参数，与小米官网二维码模块一致。
3. 显示官方二维码；同一手机可在系统浏览器打开同一张票据的 `loginUrl`。只从浏览器展示地址移除 `_json`，使页面返回可交互登录页；应用不预先访问该地址，并通过 `lp` 等待授权。
4. 成功后访问返回的 `location`，由 STS 设置平台 Cookie，再用原生 HTTP 查询额度。

流程依据[小米官网二维码模块](https://cdn.web-global.fds.api.mi-img.com/mcfe--mi-account/static/static/js/QRC.ae6ac79a.chunk.js)，不是公开承诺稳定的 API。过期或协议变化须明确报错并允许刷新；备用方式是导入平台 Cookie。

每次登录使用独立 CookieJar；账号 Cookie 不写入 WebView，不保存小米账号密码或 passToken。
仅持久化平台查询 Cookie。所有跳转仅允许 HTTPS 的 MiMo 平台和小米账号域，已知官方 HTTP 回跳先升级 HTTPS。
退出页面或重新连接时断开长轮询，避免旧请求保存到新账号。

Token Plan 用量、套餐详情和余额独立请求。至少用量或余额有效才能成功；缺失接口以结果附加信息展示，不能把鉴权错误、业务错误或空响应显示成零额度。

验证：协议夹具覆盖跨域跳转、会话隔离、过期、恶意地址及 JSON 参数；查询回归覆盖部分成功、错误与取消。真实官网无凭证验证仅能证明二维码签发和浏览器登录表单可用，完整授权仍需用户设备与账号验收。

手机验收：添加 MiMo 账号 → 登录取数 → 在浏览器中确认登录 → 返回应用。检查月度套餐用量与按量余额，再点刷新确认会话可复用。套餐详情或余额暂时不可用时，应显示“未取到”，而不是零。
