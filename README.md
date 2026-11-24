# 省食品安全平台 · 报文交换服务

监管机构、抽样单位、实验室和处置部门之间交换抽样单、检测报告、复检修订及处置回执的后端服务。
技术栈：**Java 21 编译目标 / JDK 25 运行、Spring Boot 3.5、Spring Data JPA、PostgreSQL 18、springdoc-openapi**。

---

## 1. 核心设计

### 1.1 五种身份键严格不混用

| 身份 | 标识 | 含义 |
| --- | --- | --- |
| 容器号 | `containerId` | 一次文件/报文（CSV/XML 正文 + 签名附件 + 批次） |
| 样品键 | `sampleKey` | 实物样品；只按键关联，**不按相似内容自动合并样品** |
| 报告号 | `reportNo` | 检测结论 |
| 报告修订号 + `supersedes` | `revisionNo` / `supersedes[]` | 结论沿革，**只承认显式修订关系** |
| 投递操作号 | `operationNo` | 一次目标端写入尝试，幂等键 |

### 1.2 不可变入站库与事件账

- `inbound_envelope` 保存原始字节、`sha256` 字节摘要、HMAC 签名、发送方、**容器创建时由批次固定的规范版本**、接收时间；数据库触发器禁止 `UPDATE/DELETE`。
- `business_event` 是只追加的不可变业务事件账（全局单调 `event_seq`），同样由触发器禁止修改/删除。

### 1.3 规范版本与解析器

| 批次 | 规范 | 正文 | 单位词表 |
| --- | --- | --- | --- |
| `B2026-01` | `CSV1` | CSV | `mg/kg`、`µg/kg`、`mg/L` |
| `B2026-11` | `XML2` | XML | 上述 + `µg/L`、`g/kg` |

容器按其批次固定的规范版本解析，**不随规范换版而用新规范重解析旧容器**。解析器做结构校验（表头/必填字段/数值格式/附件引用必须存在于附件清单）；业务校验做单位词表、检出限单位完整性、结论词表。

> **签名有效但检出限单位缺失**：结构解析成功，业务校验判定 `BUSINESS_REJECTED / MISSING_DETECTION_LIMIT_UNIT`，整体业务状态绝不是“解析成功”。

### 1.4 修订状态机（业务关联器）

修订状态：`PARSE_FAILED`（解析失败）、`BUSINESS_REJECTED`（业务拒绝）、`ACCEPTED`（已受理）、`SUPERSEDED`（被后续修订取代）、`QUARANTINED`（隔离）。

关联规则（`Correlator`）：

1. 同修订号且摘要相同 → `DUPLICATE_ARRIVAL`（记录重复到达，不重复应用）；
2. 同修订号但摘要不同 → `REVISION_CONFLICT`；
3. 本地未见且 `revisionNo <= 当前链头` → `LATE_REVISION_IGNORED`（**较旧修订晚到不覆盖新修订**）；
4. `revisionNo > 链头` 但缺少 `supersedes` 或未链接到当前链头 → `QUARANTINED`（**缺少 supersedes 的并行新结论必须隔离**）；
5. `supersedes` 显式包含当前链头 → `ACCEPTED`，旧链头转 `SUPERSEDED`。

### 1.5 操作账幂等投递与未知协调

- **相同操作号 + 相同载荷**重发：直接返回原操作结果（`replayed=true`），不新增投递；
- **相同操作号 + 不同载荷**：`409 CONFLICT`，原操作结果不变；
- **不同操作号 + 同报告修订 + 相同摘要**：记 `DUPLICATE_ARRIVAL` / 操作状态 `DUPLICATE`，返回原操作与回执，**不重复应用、不调用目标端**；
- 投递超时 → 操作状态 `UNKNOWN`。回执协调器重试时**先按原操作号 `GET /target/deliveries/{op}` 查询目标状态**：
  - 目标已受理 → 拉取回执，本地收敛 `ACCEPTED`（事件 `DELIVERY_CONFIRMED via TARGET_QUERY`），**不新建操作**；
  - 目标 404（从未收到）→ 用**原操作号、原载荷**重新投递；
  - 查询也超时 → 保持 `UNKNOWN`。

### 1.6 回执类型

- `RECEIVED`：仅表示目标端收到（普通报告投递 `deliveryType=REPORT`）；
- `DISPOSAL_CONFIRMED`：确认处置（`deliveryType=DISPOSAL`）。
回执类型在回执、操作状态与对账证据链中明确区分。

### 1.7 两条读取路线，同一事件序列，结果一致

1. **事件重放**（`GET /api/replay/...?asOfSeq=&asOfTime=`）：直接折叠事件账，可给任意查询时点的报告链与操作状态；直接但需要扫描事件并管理重放窗口（上界序列号/时间点）。
2. **版本化投影**（`report_head_projection` + `report_revision_projection`，按报告号分区、样品键索引，带 `partition_version`）：常用对账读取快；事件写入同事务推进投影。

两条路线共用同一个归约器 `RevisionReducer` / `OperationReducer`，因此当前报告、回执状态、历史时点结果必然一致。`POST /api/projections/rebuild` 清空投影后按事件账全量重建，并报告：

- 重建前后投影差异（投影漂移检测与修复）；
- 操作账与事件折叠状态的不一致；
- **规范换版失效边界**：容器固定规范与批次注册的绑定核查（旧批次重传仍按旧规范，换版不回溯）。

### 1.8 对账页与证据链接口

可视化对账页（零依赖静态页，输入测试账号即可查询）：

- <http://localhost:8080/> —— 容器 / 报告 / 样品 / 操作号四类证据链，含状态标签、事件时间线、回执类型与“一键重建投影核验”。

对应接口：`GET /api/reconciliation/containers/{id}`、`/reports/{no}`、`/samples/{key}`、`/operations/{no}` 串联：
不可变信封（摘要/签名/规范/接收时间）→ 样品 → 报告修订（状态/摘要/supersedes）→ 操作号 → 回执，并附完整事件序列。

---

## 2. 一键启动

### 方式 A：Docker Compose（推荐，含 PostgreSQL）

```bash
docker compose up --build
```

- 应用：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL：`localhost:5432`，库/用户/密码 `ccdev/ccdev/ccdev`

### 方式 B：本地 Maven + 已预装 PostgreSQL（本工作区实际验证方式）

容器内已预装 PostgreSQL 18（`127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 如需确认数据库
pg_isready -h 127.0.0.1 -p 5432
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d ccdev -c 'select 1'

# 启动（schema.sql/data.sql 自动建表并写入批次种子）
mvn spring-boot:run
# 或打包后运行
mvn package
java -jar target/message-exchange-1.0.0.jar
```

自定义数据库连接：

```bash
DB_URL='jdbc:postgresql://host:5432/db' DB_USER=u DB_PASS=p \
TARGET_BASE_URL='http://downstream:8080' \
java -jar target/message-exchange-1.0.0.jar
```

> `TARGET_BASE_URL` 留空时，投递目标指向应用内置的“模拟目标端” `/target/**`（自调用），用于演示超时注入与状态查询；接入真实目标端时配置该地址即可。

---

## 3. 测试账号（HTTP Basic）

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `admin123` | ADMIN |
| `regulator` | `regulator123` | REGULATOR |
| `lab` | `lab123` | LAB |

所有 `/api/**` 均需 Basic 认证；Swagger UI 可在页面右上角 **Authorize** 输入账号密码。
`/target/**` 是模拟外部目标端，放行且无需认证。

报文级发送方身份（HMAC 密钥，见 `application.yml`）：`lab-alpha / lab-alpha-secret`、`lab-beta / lab-beta-secret`、`regulator-a / regulator-a-secret`。
签名算法：`hex(HMAC_SHA256(senderSecret, 报文字节))`。

---

## 4. 样例场景（题面要求）与验证方式

样例：容器 **C1** 含样品 **S17**、报告 **R7** 修订 **1**，操作 **O1** 已受理；复检**修订2**显式取代修订1，以 **O2** 发送后目标超时；再次处理 O2 时**先查询**而不新建操作，目标已受理则本地收敛；旧容器 **C1 重传不回滚**；**修订3** 使用新批次生效的 **XML2** 规范，仍按新容器固定的规范解析。

### 4.1 自动化端到端验证脚本（55 项断言）

服务启动并使用**空数据库**后：

```bash
# 如本机 python3 路径不同： PYTHON=python ./verify.sh
chmod +x verify.sh
./verify.sh http://localhost:8080
```

脚本自动完成上述全部场景，并额外验证：操作号三种幂等/冲突语义、异号同摘要重复到达、并行结论隔离、晚到旧修订、同修订摘要冲突、无效签名、未知附件引用、检出限单位缺失、`RECEIVED` 与 `DISPOSAL_CONFIRMED` 回执区分、重放时点窗口、重放/投影一致、投影重建差异报告、四类对账证据链。退出码 `0` 表示全部通过。

> 重复运行脚本前请清空业务表（脚本假定全新状态）：
> ```sql
> TRUNCATE inbound_envelope, business_event, report_revision_projection,
>   report_head_projection, operation_ledger, receipt,
>   target_inbox, target_receipt RESTART IDENTITY CASCADE;
> UPDATE fault_switch SET timeout_next = FALSE WHERE id = 1;
> ```

### 4.2 JUnit 集成测试（21 个，真实 PostgreSQL + 随机端口）

```bash
mvn test
```

测试类：

- `ScenarioFlowTest`：题面完整样例 + 重放/投影/重建一致性；
- `ValidationCorrelationTest`：检出限单位缺失、坏签名、未知附件、规范版本单位词表、并行隔离、晚到修订、修订冲突、容器载荷冲突、未知批次/发送方；
- `DeliveryIdempotencyTest`：同号同载荷、同号异载荷冲突、异号同摘要重复、未知→查询收敛、目标未收到时原号重发、两类回执、隔离修订不可投递；
- `ProjectionRebuildTest`：重放时点窗口、投影漂移→重建报告差异并修复、不可变触发器拒绝 UPDATE/DELETE、样品键隔离。

### 4.3 手工快速体验

```bash
# 1) 构造 C1 的 CSV 并签名
printf 'sample_key,report_no,revision_no,supersedes,analyte,result_value,result_unit,detection_limit,detection_limit_unit,conclusion,attachment_refs\nS17,R7,1,,lead,0.02,mg/kg,0.005,mg/kg,PASS,\n' > /tmp/c1.csv
SIG=$(python3 -c "import hmac,hashlib;print(hmac.new(b'lab-alpha-secret',open('/tmp/c1.csv','rb').read(),hashlib.sha256).hexdigest())")

# 2) 入站
curl -u admin:admin123 -F containerId=C1 -F batchId=B2026-01 -F sender=lab-alpha \
  -F signature=$SIG -F file=@/tmp/c1.csv http://localhost:8080/api/inbound/containers

# 3) 投递 O1
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -d '{"operationNo":"O1","reportNo":"R7","revisionNo":1,"deliveryType":"REPORT","payload":{"v":1}}' \
  http://localhost:8080/api/deliveries

# 4) 制造下一次目标超时，再投递 O2，然后协调重试
curl -X POST -H 'Content-Type: application/json' -d '{"timeoutNext":true}' \
  http://localhost:8080/target/faults
# ... POST /api/deliveries (O2) -> UNKNOWN
curl -u admin:admin123 -X POST http://localhost:8080/api/deliveries/O2/retry
```

---

## 5. 主要 API 一览

| 方法/路径 | 说明 |
| --- | --- |
| `POST /api/inbound/containers` | multipart 入站：`containerId,batchId,sender,signature,attachments(JSON),file` |
| `GET  /api/inbound/containers/{id}` | 不可变信封元数据 |
| `POST /api/deliveries` | 幂等投递（操作号） |
| `GET  /api/deliveries/{op}` | 操作账 + 回执 + 事件 |
| `POST /api/deliveries/{op}/retry` | 未知协调：先按原操作号查询，再决定原号重试 |
| `GET  /api/replay/reports/{no}?asOfSeq=&asOfTime=` | 事件重放报告链（历史时点） |
| `GET  /api/replay/operations/{op}?asOfSeq=` | 事件重放操作状态 |
| `GET  /api/replay/reports/{no}/verify` | 重放 vs 投影一致性核验 |
| `GET  /api/projections/reports/{no}` | 版本化投影快读 |
| `GET  /api/projections/samples/{key}` | 按样品键读投影 |
| `POST /api/projections/rebuild` | 重建投影 + 差异/操作账/规范边界核验 |
| `GET  /api/reconciliation/containers/{id}` | 容器证据链 |
| `GET  /api/reconciliation/reports/{no}` | 报告证据链 |
| `GET  /api/reconciliation/samples/{key}` | 样品证据链 |
| `GET  /api/reconciliation/operations/{op}` | 操作号证据链 |
| `GET  /api/meta/spec-versions` | 规范版本/批次/单位词表 |
| `POST /target/deliveries` · `GET /target/deliveries/{op}` | 模拟目标端（幂等接收/查询） |
| `POST /target/faults` · `GET /target/inbox` · `POST /target/reset` | 故障注入与检视 |

---

## 6. 本环境实际验证结论

- `mvn compile` / `mvn test`：**21 个集成测试全部通过，0 失败 0 错误**（真实连接 `127.0.0.1:5432/ccdev` PostgreSQL 18.1）；`mvn package` 同样通过。
- 开发态启动后执行 `./verify.sh http://localhost:18080`：**55 项端到端断言全部通过**；最终交付 jar（`java -jar target/message-exchange-1.0.0.jar`，8080 端口）启动后再次执行 `./verify.sh`（默认地址）：**55/55 全部通过，退出码 0**。对账页 `/`、Swagger UI、OpenAPI JSON 均实测返回 200。
- 工作区环境**未安装 Docker**（`docker` 命令不存在），因此 `Dockerfile` 与 `docker-compose.yml` 已按一键启动要求交付，但**镜像构建与 compose 启动未在本环境实际执行**；上述结论均来自本地 JDK 25 + Maven + 预装 PostgreSQL 的真实运行。在装有 Docker 的环境执行 `docker compose up --build` 即可获得等价运行环境。
