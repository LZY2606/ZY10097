# 因果事件调试台 (Causal Event Debugger)

面向多服务事件日志的**因果偏序 + 聚合不变量**调试应用。它把墙钟时间戳严格降级为
展示字段，只依据两类事实建立偏序：

1. **显式因果边** —— 每条事件的 `parent` 指向其父项 uid；
2. **服务内顺序** —— 同一 `service` 内单调的本地序号 `seq`。

在此偏序上重放每个聚合（按 `key` 分组）的状态机，报告最早的不变量失败、最小可复现
切片、哪些并发事件可安全交换、哪些交错会改变结论，并对交错数量做**有界枚举**（越界即
标注 UNKNOWN，不伪装穷举）。

## 核心语义（务必先读）

- **原始证据不可变**：摄取只做 append。重复摄取相同内容按日志指纹去重；新增日志只能
  通过「派生」产生新会话，旧会话不被原地改绑。
- **派生可追溯**：会话绑定 `(日志指纹, 规则版本)`；假设、导出都携带规则版本与来源
  指纹（SHA-256）。
- **四类摄取异常分别处理**：
  - `DUPLICATE_DELIVERY` 同 uid 且内容一致的重复投递（折叠为一条有效事件）；
  - `SEQ_CONTENT_CONFLICT` 同服务/序号身份但内容/类型不同（折叠，保留首条并告警）；
  - `LATE_DELIVERY` 按接收顺序晚到（seq 小于该服务已见最大 seq）；
  - `MISSING_PARENT` 因果父项不在日志中。
- **三种修复假设（只影响候选重放，绝不回写证据）**：
  - 忽略重复投递；
  - 补入缺失父项**占位**；
  - 调整某事件的关联键（重新分组）。
- **占位永远不是业务事件**：它参与排序、渲染为紫色占位节点，但不驱动状态转换，结论
  标记为暂定。当真实父项随后随**新日志**到达时，基于旧占位的派生切片被标记失效
  （`settledPlaceholderParents` / `derivedSlicesInvalidated`），必须在派生会话中重算。
- **乐观并发**：会话带 `revision`。两个浏览器基于同一旧版本提交时，后到者收到
  `409 REVISION_CONFLICT`，可查看对方内容并在最新 revision 上重新合并后再提交。

## 技术栈

- Java 17 字节码、Spring Boot 3.5（spring-web + spring-jdbc，不使用 Hibernate/CGLIB
  实体增强，避免新 JDK 字节码兼容问题）。
- 嵌入式 H2 文件库，本地持久化到 `./data/causaldbg`，无外部数据库、无外部网络依赖。
- 原生 HTML/CSS/JS 单页（`src/main/resources/static`），无 CDN、无构建步骤。
- JUnit 5 + Spring MVC 测试，全部离线运行。

## 构建、测试、启动

```bash
./mvnw -q -DskipTests package
./mvnw -q test
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5221
```

打开 <http://127.0.0.1:5221>。

## 一次故障恢复演练（新维护者按此操作）

1. **复现**：点右上角「载入样例故障」，或
   `POST /api/logs/sample`。样例 `order:42` 含：一次重复投递（e2）、一次乱序晚到
   （e3 在 e5 之后收到）、一个缺失父项（e4 指向 e9），以及跨服务并发。
2. **读结论**：页面「最早失败 & 最小切片」显示失败事件（样例为 `Delivered` 从
   `SHIPPED` 无合法转换以外的非法路径），最小切片给出到失败事件为止的逐聚合前缀；
   「有界交错枚举」展示不同合法拓扑序下失败可能落在不同事件上，到边界显示 UNKNOWN。
3. **提假设**：在「修复假设」里
   - 把重复的 `e2` 加入忽略；
   - 为缺失父项 `e9` 增加占位（关联键 `order:42`）；
   点「按假设重放」。占位以紫色节点出现，状态轨迹中标 `non-business`，结论标暂定。
4. **保存会话**：点「保存假设」。会话绑定规则版本 `rules-v1` 与日志指纹。
5. **模拟双浏览器冲突**：用两个会话视图分别保存（或用两条 curl，都带
   `expectedRevision: 0`）。后到者得到 409；点「在最新修订上重新合并」，前端做并集
   合并，再在新 revision 上保存。
6. **真实父项到达（关键恢复步骤）**：摄取包含真实 `e9` 的新日志（见下方 API），在旧
   会话上点「派生到新日志」。派生会话重放时，旧占位对应的父项已被真实证据满足，
   页面顶部提示「真实父项已到达，旧占位派生切片已失效，必须重算」，导出中
   `settledPlaceholderParents` 非空、`derivedSlicesInvalidated=true`。
7. **导出交接**：点「导出」，得到最小切片、假设、规则/假设/日志指纹与确定性
   `topologySeed`（Kahn 可执行节点中取 uid 最小者，平局按 service、seq）。

## HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/logs/sample` | 载入内置样例（按指纹去重） |
| POST | `/api/logs` | 追加摄取新日志 `{name, events[]}` |
| GET | `/api/logs` / `/api/logs/{id}` | 日志列表 / 日志与原始事件 |
| POST | `/api/sessions` | 基于日志创建会话（绑定规则版本+日志指纹） |
| GET | `/api/sessions` / `/api/sessions/{id}` | 会话列表 / 详情 |
| PUT | `/api/sessions/{id}` | 乐观更新（body 带 `expectedRevision`，冲突 409） |
| POST | `/api/sessions/{id}/derive` | 派生到新日志（旧会话不改绑） |
| POST | `/api/replay` | 重放：`{sessionId|logId, hypothesis, fixedOrder?, bound?}` |
| GET | `/api/sessions/{id}/replay` | 用已保存假设重放 |
| GET | `/api/sessions/{id}/export` | 导出最小切片+假设+拓扑种子 |
| GET | `/api/rules` | 当前默认规则文档 |

事件字段：`uid,service,seq,key,parent,type,payloadHash,ts`（`ts` 为 ISO-8601，仅展示）。

### 真实父项到达示例

```bash
curl -s -X POST localhost:5221/api/logs -H 'Content-Type: application/json' -d '{
  "name":"v2-real-parent","events":[
    {"uid":"e1","service":"order-svc","seq":1,"key":"order:42","type":"OrderCreated","payloadHash":"h1","ts":"2026-09-21T10:00:00Z"},
    {"uid":"e2","service":"payment-svc","seq":1,"key":"order:42","parent":"e1","type":"PaymentAccepted","payloadHash":"h2","ts":"2026-09-21T10:00:02Z"},
    {"uid":"e9","service":"payment-svc","seq":2,"key":"order:42","parent":"e2","type":"PaymentAccepted","payloadHash":"h9","ts":"2026-09-21T10:00:03Z"},
    {"uid":"e4","service":"shipping-svc","seq":1,"key":"order:42","parent":"e9","type":"Shipped","payloadHash":"h4","ts":"2026-09-21T10:00:09Z"}
  ]}'
```

## 规则文档

默认规则 `rules-v1` 见 `DefaultRules`，用最长前缀匹配聚合键（`order:`、`inv:`），
每条 `(from,event)->to` 转换；不在表内的应用即不变量失败。会话保存规则 JSON 快照与
版本；也可在创建会话/重放时提交自定义 `ruleJson`（版本随之固定）。

## 数据与重置

- H2 文件位于工作目录 `./data/`；需要回到干净状态时，停止应用后把该目录改名备份再
  重启（schema 会自动 `CREATE TABLE IF NOT EXISTS`）。
- 不要手工编辑 `raw_event`：原始证据一经接收不可原地改写。

## 测试

```bash
./mvnw -q test
```

- `AnalysisEngineTest`：偏序边、墙钟偏斜不可重排、重复/冲突/缺父项/乱序分类、占位非
  业务、最小切片、并发交换、有界枚举越界 UNKNOWN、环检测、忽略与改键假设、固定拓扑序
  合法性。
- `ApiIntegrationTest`：摄取/重放/导出、异常分类、409 冲突与重新合并、新日志派生与
  占位失效、样例失败。测试使用内存 H2，不依赖网络。
