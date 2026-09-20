# Causal Workbench

面向多服务事件日志的离线因果调试台。它把不可变原始收据、规则版本、日志指纹、修复假设、候选重放、聚合状态、最小失败切片和有界拓扑交错绑定到可持久化的调试会话中。

## 快速开始

要求本机 JDK 17+。Maven Wrapper 使用本机 Maven 3.9.16 缓存，不要求外部网络。

```bash
./mvnw -q -DskipTests package
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5221
```

打开：

```text
http://127.0.0.1:5221
```

H2 文件库默认位于 `./data/causal-workbench*`，运行时自动创建；`data/` 已被忽略，不进入交付快照。

## 因果语义

- 原始日志只追加：每次接收生成独立 `receiptId`，保存原始 JSON、内容哈希、接收顺序、批次和分类。
- 偏序事实只来自两类边：显式 `parentId` 因果边、同一 `service` 内按 `seq` 升序的服务内顺序。
- `timestamp` 是可能偏斜的墙钟，只用于页面和 API 展示；拓扑和状态验证绝不按它排序。
- 字节完全一致的重复投递归为 `DUPLICATE_DELIVERY`，默认候选只保留一份业务事件，重复收据仍可查询。
- 同一事件身份但内容不同归为 `SAME_ID_CONTENT_CONFLICT`，不会覆盖原收据；同服务同序号不同内容产生 `SEQ_CONFLICT` 警告。
- 缺父项在不补占位时产生 `MISSING_PARENT` 结构失败；选择补入后生成 `placeholder:<parentId>`。
- 占位节点明确标记为非业务事件，不执行状态转换。真实父项后续到达时，旧候选标记 `INVALIDATED_REAL_PARENT_ARRIVED`，只能从新日志指纹派生新会话和新候选。
- 迟到事件仍按服务序号和因果边参与偏序，不按接收时间或墙钟丢弃。

默认状态规则在 `src/main/resources/default-rules.json`：

- 初始状态：`CREATED`
- `CREATE: CREATED -> ACTIVE`
- `COMPLETE: ACTIVE -> COMPLETED`
- `SUSPEND: ACTIVE -> SUSPENDED`
- `ACTIVATE: CREATED/PENDING/SUSPENDED -> ACTIVE`
- `CANCEL: CREATED/ACTIVE/PENDING/SUSPENDED -> CANCELLED`

可以通过 `POST /api/rules` 提交完整 JSON 创建新版本。已保存会话继续绑定旧规则指纹；不能用新规则静默重放旧会话。

## 失败诊断

重放结果包含：

- `deterministicOrder` / `deterministicSeed`：可复现的确定性拓扑序和逗号分隔种子。
- `earliestFailureCode`、`earliestFailureEventId`、`earliestFailureRank`、`earliestFailureMessage`。
- `minimalSlice`：仍能复现失败的最小因果/聚合前缀，每个节点说明它为何必要。
- `aggregateStates`：每个关联键的最终状态、最后事件和事件列表。
- `concurrentPairs`：没有因果先后的事件对；不同聚合可交换，同聚合会实际比较相邻交换是否改变结论。
- `linearizations`：有界交错枚举结果；达到边界时 `boundReached=true`，未探索空间为未知，不会声称“全部顺序通过”。

候选支持两种使用方式：

- 普通候选：用确定性拓扑重放。
- 固定拓扑：调用固定重放会生成一个子候选，父候选不被覆盖。

## 主要 API

### 接收和查询原始证据

```http
POST /api/events
GET  /api/events
```

请求示例：

```json
{
  "clientBatchId": "demo-batch-001",
  "events": [
    {"eventId":"e2","service":"billing","seq":1,"key":"order-1","type":"COMPLETE","payload":{},"parentId":"e1","timestamp":900},
    {"eventId":"e1","service":"orders","seq":1,"key":"order-1","type":"CREATE","payload":{},"timestamp":1000}
  ]
}
```

上面的 `timestamp` 是逆序的，但确定性拓扑必须是 `e1,e2`，因为存在显式因果边。

### 预演、不保存

```http
POST /api/replay-preview
```

```json
{
  "hypothesis": {
    "ignoreDuplicates": true,
    "fillMissingParents": false,
    "keyAdjustments": {"wrong-event-id":"corrected-key"},
    "seedOrder": [],
    "interleavingBound": 100
  }
}
```

### 会话和候选

```http
POST /api/sessions
GET  /api/sessions
GET  /api/sessions/{sessionId}
POST /api/sessions/{sessionId}/derive
POST /api/sessions/{sessionId}/candidates
POST /api/sessions/{sessionId}/candidates/{candidateId}/fixed-replay
POST /api/sessions/{sessionId}/merge
POST /api/sessions/{sessionId}/workspace
GET  /api/sessions/{sessionId}/export
```

会话响应同时返回 `logFingerprint`、`ruleFingerprint`、工作区 `revision` 和候选 `revision`。

### 规则

```http
GET  /api/rules/active
POST /api/rules
```

`POST /api/rules` 的请求体是 `{"definitionJson":"{...escaped json...}"}`。

## 新维护者：复现一次故障恢复

1. 启动应用并接收一个缺父项事件：

```bash
curl -sS -X POST http://127.0.0.1:5221/api/events \
  -H 'Content-Type: application/json' \
  -d '{"clientBatchId":"incident-1","events":[{"eventId":"child","service":"orders","seq":2,"key":"order-1","type":"COMPLETE","payload":{},"parentId":"parent"}]}'
```

2. 固定日志为调试会话：

```bash
SID=$(curl -sS -X POST http://127.0.0.1:5221/api/sessions | sed 's/.*"id":"\([^"]*\)".*/\1/')
```

3. 保存“补入缺失父项占位”的候选。结果通过，但拓扑包含 `placeholder:parent`，且诊断明确说明它不是业务事件。

```bash
curl -sS -X POST "http://127.0.0.1:5221/api/sessions/$SID/candidates" \
  -H 'Content-Type: application/json' \
  -d '{"name":"placeholder repair","hypothesis":{"ignoreDuplicates":true,"fillMissingParents":true,"keyAdjustments":{},"seedOrder":[],"interleavingBound":10},"expectedWorkspaceRevision":0}'
```

4. 真实父项作为新证据到达：

```bash
curl -sS -X POST http://127.0.0.1:5221/api/events \
  -H 'Content-Type: application/json' \
  -d '{"clientBatchId":"incident-1-real-parent","events":[{"eventId":"parent","service":"orders","seq":1,"key":"order-1","type":"CREATE","payload":{}}]}'
```

5. 重新打开旧会话，会看到旧候选为 `INVALIDATED_REAL_PARENT_ARRIVED`。它的结果仍绑定旧日志指纹，但不再代表当前日志。

```bash
curl -sS "http://127.0.0.1:5221/api/sessions/$SID"
```

6. 从旧会话派生新日志版本，系统复制未解决的假设并用真实父项重放：

```bash
curl -sS -X POST "http://127.0.0.1:5221/api/sessions/$SID/derive"
```

7. 导出调试包：

```bash
curl -sS "http://127.0.0.1:5221/api/sessions/$SID/export"
```

导出包含最小切片、假设、规则/日志指纹和确定性拓扑排序种子。

## 并发编辑冲突

工作区和候选都有乐观版本号。两个浏览器都基于旧版本提交合并时：

- 第一个请求成功，工作区版本增加。
- 第二个请求得到 `409 Conflict`，响应包含当前状态、当前选中的候选、当前备注和当前版本。
- 第二个浏览器刷新看到冲突内容后，可以用新的 `expectedWorkspaceRevision` 重新合并。

这一分支由 `WorkbenchApplicationTest.twoStaleBrowserMergesReturnCurrentConflictContentForSecondMerge` 覆盖。

## 测试

```bash
./mvnw -q test
```

测试使用内存 H2，不访问外部网络：

- `ReplayEngineTest`：因果边覆盖墙钟、非法转换、缺父项、占位、有界交错、并发可交换性。
- `WorkbenchApplicationTest`：持久化、重复分类、会话指纹、占位失效、派生重放、双浏览器冲突和导出。

## 代码结构

- `domain/`：JPA 实体，表达不可变收据、批次、规则、会话、工作区和候选。
- `service/ReplayEngine.java`：偏序构建、环检测、拓扑、状态机、有界交错、最小切片和交换性分析。
- `service/WorkbenchService.java`：追加接收、指纹、候选版本、派生、失效、导出和乐观冲突。
- `web/`：REST 控制器和错误映射。
- `src/main/resources/static/`：无需外部 CDN 的单页界面。
- `src/test/`：完全离线的单元测试和 Spring Boot 集成测试。
