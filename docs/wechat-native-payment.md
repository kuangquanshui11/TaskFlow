# 微信支付 Native 与订单超时补偿

我在独立订单服务内接入微信支付普通商户 API v3。订单服务维护订单、库存预占、支付状态和补偿任务。TaskFlow 继续按 Cron 调用订单超时接口，Scheduler 与 Worker 不访问订单库或支付渠道。

## 下单与回调

1. 下单时以 MySQL 预占库存，并从商品目录保存成交快照。发起支付时锁定订单，从已保存的快照读取商品描述与人民币分金额。没有快照的旧订单和无定价的 SKU 不进入真实支付。
2. 支付入口写入支付记录及后台任务后返回 HTTP 202。由订单服务查询并发起微信 Native 下单。商户订单号固定为订单 UUID 去掉连字符后的 32 位字符串。客户端轮询支付状态，取得 `codeUrl` 后在业务页面生成二维码。过期、关闭或支付完成后不再返回二维码链接。
3. 微信通知使用原始请求体和 `Wechatpay-*` 头，通过官方 SDK 验签和 AES-GCM 解密。检查通知类型、时间戳窗口、商户、AppID、商户订单号、交易类型、微信交易号、币种和成交快照金额。事务提交后返回 HTTP 204。失败返回非 2xx，由微信按其通知规则处理。
4. 独立扫描器主动查单，用同一状态处理逻辑补偿遗漏回调。订单到期且微信确认未付时发起渠道关单。若微信返回已付款，后续查单或签名通知确认实际结果。发起 Native 请求时至少预留一分钟与请求时间，避免微信将临近结束时间的支付单延长；本地订单付款窗口配置为 2 分钟至 2 小时。下单响应不明确时按原单号查单。若微信保留了交易但无法重新返回二维码，继续查单和关单，客户可在原订单关闭后新建订单。

[微信 Native 下单](https://pay.wechatpay.cn/doc/v3/merchant/4012791877) · [支付成功回调](https://pay.wechatpay.cn/doc/v3/merchant/4012791861) · [Native 开发指引](https://pay.wechatpay.cn/doc/v3/merchant/4012791891)

## 支付与关单竞争

| 锁内观察 | 订单和库存 | 支付和退款 |
| --- | --- | --- |
| 订单仍为 PENDING 且微信付款时间早于截止时间 | 同一事务标记 PAID，消费预占并写入一次 CONSUMED 流水 | 保存交易号及微信付款时间，结束支付查单 |
| 订单仍为 PENDING 且微信付款时间达到或超过截止时间 | 同一事务关闭订单，释放预占并写入一次 RELEASED 流水 | 保存迟到付款，排队全额退款 |
| 订单已 CLOSED，随后收到已核验的支付结果 | 保留已释放库存及关闭终态 | 保存迟到付款，排队全额退款，即使实际付款发生在截止时间前 |
| 同一交易重复通知或重复查单 | 在同一订单锁内检查已保存交易号和付款时间 | 相同结果幂等确认，冲突结果拒绝 |

关单与付款使用同一订单行锁。库存流水、订单状态、支付结果、退款及外部库存释放意图在需要时一起提交。模拟付款在启用真实支付时禁用，已有真实支付记录的订单在关闭开关后也不能模拟付款。Redis 继续只用于查询缓存。

## 迟到付款退款

每笔迟到款使用稳定退款单号 `late-<商户订单号>`，以微信交易号申请一次业务意义上的全额原路退款。同一任务重试复用这个退款单号。扫描器在申请前查询该退款单，申请响应丢失后先查单。申请返回成功只进入 `PROCESSING`，查询结果校验退款单号、原交易、退款金额、原金额和币种后才能进入 `SUCCESS`。

处理中的退款起初按一分钟查询，五分钟后降低到十分钟。微信返回 `ABNORMAL` 或 `CLOSED` 时保留异常状态供人工对账，当前接口不会给同一交易自动换退款号。超出重试或对账窗口的任务进入 `REVIEW`。适用范围为迟到款全额退款，已履约订单的售后、部分退款和人工异常退款按商户业务另行处理。

[微信退款指引](https://pay.wechatpay.cn/doc/v3/merchant/4013071031) · [查询单笔退款](https://pay.wechatpay.cn/doc/v3/merchant/4012791884)

## 跨系统库存补偿协议

配置仓储接收端后，订单服务在释放本地预占的事务内写入一条 `RELEASE` 任务。普通超时关闭和迟到付款导致的关闭走同一规则，已关闭订单的迟到通知不重复创建释放任务。扫描器在事务外向配置的 HTTPS 地址发送以下请求，独立于退款进度。

```http
POST /api/v1/reservation-releases/insert
Authorization: Bearer <仓储补偿专用凭据>
Idempotency-Key: order-release-<orderId>
Content-Type: application/json

{"eventId":"order-release-<orderId>","orderId":"<orderId>","skuId":1001,"quantity":2,"reason":"PAYMENT_TIMEOUT"}
```

接收端按 `eventId` 去重，校验订单、SKU 和数量，按订单预占键释放一次。重复请求返回原结果，只有提交或确认原提交后返回 HTTP 200 或 204。接收端需定义订单尚未建立外部预占时的处理，避免先收到释放、后创建预占。订单服务只发送已关闭订单的释放事实，外部预占的建立与实际仓库状态归仓储系统。

超时、408、429 和 5xx 按持久化任务重试。其他响应进入 `REVIEW`。响应丢失可能产生重复投递，因此接收端的持久化去重属于接入条件。关闭该开关期间发生的本地释放不会自动补发，部署切换前应核对存量订单和外部预占。

## 启用与排查

先准备开通 Native 支付的微信普通商户、已绑定的 AppID、商户 API 私钥与证书序列号、微信支付公钥及公钥 ID，以及 APIv3 密钥。订单服务独立升级 Flyway V5。先备份订单库，保持支付开关关闭完成迁移，再按下面步骤接入。

1. 使用独立的受控入口，仅把 `POST /api/v1/order-payments/updateWechatNotification` 通过公网 HTTPS 转发到订单服务，保留原始请求体和微信签名头。业务令牌保护下单和查询接口，服务端调用方负责校验用户订单归属。TaskFlow 公共 Gateway 路由保持现有范围。
2. 在忽略的 `config/taskflow-order-local.yml` 或进程环境中配置凭据并重启。配置示例如下，密钥文件放在服务进程可读的私有目录。

   ```yaml
   order:
     wechat-pay:
       enabled: true
       app-id: ${TASKFLOW_WECHAT_PAY_APP_ID}
       merchant-id: ${TASKFLOW_WECHAT_PAY_MERCHANT_ID}
       merchant-serial: ${TASKFLOW_WECHAT_PAY_MERCHANT_SERIAL}
       private-key-path: ${TASKFLOW_WECHAT_PAY_PRIVATE_KEY_PATH}
       public-key-id: ${TASKFLOW_WECHAT_PAY_PUBLIC_KEY_ID}
       public-key-path: ${TASKFLOW_WECHAT_PAY_PUBLIC_KEY_PATH}
       api-v3-key: ${TASKFLOW_WECHAT_PAY_API_V3_KEY}
       notify-url: ${TASKFLOW_WECHAT_PAY_NOTIFY_URL}
     payment-work:
       inventory-release-enabled: false
       inventory-release-url: ${TASKFLOW_INVENTORY_RELEASE_URL:}
       inventory-release-token: ${TASKFLOW_INVENTORY_RELEASE_TOKEN:}
   ```

3. 准备有定价的 SKU 和受控测试订单，调用发起支付接口并轮询，确认二维码、回调、订单与库存流水一致。测试迟到款时核对退款单号、微信查单和最终资金结果。仓储接收端具备幂等协议后，再单独启用库存补偿。
4. 排查任务时读取支付详情中的 `payment`、`refund`、`inventoryRelease` 以及 `lastError`。修复连接或鉴权后，对可重试的 `REVIEW` 任务调用受控重排接口，继续使用原单号及幂等键。退款冲突、关闭或异常按微信商户侧实际结果处理。轮换微信公钥和密钥、停用支付或变更商户前应先对账并排空在途任务。

默认连接超时 2 秒，请求总超时 5 秒，扫描每 2 秒启动一轮。领取任务使用 30 秒租约及随机令牌，多实例下过期持有者不能写回任务结果。故障间隔 15 秒，默认连续失败 8 次或到达对账截止时间后人工介入。支付对账窗口从付款截止起算，退款和库存释放从任务入队起算，默认均为 8 天。HTTP 传输关闭自动 POST 重放和跳转，由持久化任务控制重试。相关策略集中在订单服务 `application.yml`。

[订单支付 API](api.md#微信支付-native-api-v1) · [微信官方 Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java)

## 2026 年 11 月 10 日验证

环境为 Windows、Java 21.0.12.1、H2 2.4.240 和临时回环 MySQL 8.0.34（REPEATABLE READ）。订单服务使用隔离数据，微信与仓储业务响应由测试替身提供。验签测试使用临时 RSA 密钥、AES-GCM 加密报文和官方 SDK，另用回环 HTTP 服务验证 POST 不因服务端提示而被自动重发。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `mvnw.cmd -B -o -pl examples/taskflow-order-service test` | 34 项通过，30 项条件跳过。包含 6 项支付集成测试和 2 项 SDK 与传输专项 | `.tools/wechat-order-tests-final.log` |
| `tools/test-order-mysql.ps1 -IncludePayment` | 14 项通过，含 6 项支付集成测试。临时 MySQL 迁移到 V5，验证重复回调、付款与关单竞争、迟到款重放、主动查单、同时间戳流水顺序及仓储补偿 | `.tools/order-mysql/20261110-120531-946/tests.log` |
| 根目录 `mvnw.cmd -B -o verify` | 七个服务打包成功，378 项通过，38 项条件跳过 | `.tools/wechat-native-verify.log` |

首次根目录回归中有两项立即扫描断言失败。将入队、重排和扫描时间统一到数据库微秒精度后，原用例通过。随后修复了预占与释放共享同一时间戳时的流水排序，并用固定同一时间戳和反序流水 ID 的用例验证。

真实 RabbitMQ 和 Redis 专项跳过。公网回调、真实商户资金和实际仓储接收端的联调待执行。现有业务库的 V4 到 V5 升级需在部署前按备份和迁移步骤复核。
