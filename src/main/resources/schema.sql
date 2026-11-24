-- 省食品安全平台报文交换服务数据库结构
-- 身份模型：容器号(一次报文) / 样品键(实物样品) / 报告号(检测结论) / 修订号+supersedes(结论沿革) / 操作号(一次投递尝试)

-- 发送批次注册表：批次 -> 规范版本（容器启动时固定的规范版本由此确定）
CREATE TABLE IF NOT EXISTS batch_registry (
  batch_id       TEXT PRIMARY KEY,
  spec_version   TEXT NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL
);

-- 入站库：不可变保存字节摘要、签名、发送方、规范版本与接收时间
CREATE TABLE IF NOT EXISTS inbound_envelope (
  id              BIGSERIAL PRIMARY KEY,
  container_id    TEXT NOT NULL,
  batch_id        TEXT NOT NULL,
  sender          TEXT NOT NULL,
  spec_version    TEXT NOT NULL,
  payload_sha256  TEXT NOT NULL,
  signature       TEXT NOT NULL,
  signature_valid BOOLEAN NOT NULL,
  attachments     JSONB NOT NULL DEFAULT '[]',
  payload_bytes   BYTEA NOT NULL,
  received_at     TIMESTAMPTZ NOT NULL,
  UNIQUE (container_id, payload_sha256)
);

-- 不可变业务事件账：所有状态变化的唯一事实来源
CREATE TABLE IF NOT EXISTS business_event (
  event_seq    BIGSERIAL PRIMARY KEY,
  event_id     UUID NOT NULL UNIQUE,
  event_type   TEXT NOT NULL,
  container_id TEXT,
  sample_key   TEXT,
  report_no    TEXT,
  revision_no  INT,
  operation_no TEXT,
  payload      JSONB NOT NULL,
  occurred_at  TIMESTAMPTZ NOT NULL,
  recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_report    ON business_event (report_no, event_seq);
CREATE INDEX IF NOT EXISTS idx_event_operation ON business_event (operation_no, event_seq);
CREATE INDEX IF NOT EXISTS idx_event_container ON business_event (container_id, event_seq);
CREATE INDEX IF NOT EXISTS idx_event_sample    ON business_event (sample_key, event_seq);

-- 版本化当前投影：按报告号分区（样品键为二级索引），可由事件账重建
CREATE TABLE IF NOT EXISTS report_revision_projection (
  report_no         TEXT NOT NULL,
  revision_no       INT  NOT NULL,
  sample_key        TEXT NOT NULL,
  status            TEXT NOT NULL,
  digest            TEXT NOT NULL,
  spec_version      TEXT NOT NULL,
  supersedes        JSONB NOT NULL DEFAULT '[]',
  container_id      TEXT,
  late_arrival      BOOLEAN NOT NULL DEFAULT FALSE,
  reason            TEXT,
  applied_event_seq BIGINT NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (report_no, revision_no)
);
CREATE INDEX IF NOT EXISTS idx_revision_sample ON report_revision_projection (sample_key);

CREATE TABLE IF NOT EXISTS report_head_projection (
  report_no         TEXT PRIMARY KEY,
  sample_key        TEXT NOT NULL,
  head_revision     INT,
  head_status       TEXT,
  partition_version BIGINT NOT NULL,
  last_event_seq    BIGINT NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_head_sample ON report_head_projection (sample_key);

-- 操作账：按操作号幂等投递
CREATE TABLE IF NOT EXISTS operation_ledger (
  operation_no    TEXT PRIMARY KEY,
  payload_sha256  TEXT NOT NULL,
  target_endpoint TEXT NOT NULL,
  delivery_type   TEXT NOT NULL,
  report_no       TEXT NOT NULL,
  revision_no     INT  NOT NULL,
  request_payload JSONB NOT NULL,
  status          TEXT NOT NULL,
  duplicate_of    TEXT,
  last_event_seq  BIGINT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_operation_revision ON operation_ledger (report_no, revision_no);

-- 回执：类型区分“仅收到”(RECEIVED) 与“确认处置”(DISPOSAL_CONFIRMED)
CREATE TABLE IF NOT EXISTS receipt (
  receipt_id         TEXT PRIMARY KEY,
  operation_no       TEXT NOT NULL,
  receipt_type       TEXT NOT NULL,
  issuer             TEXT NOT NULL,
  detail             TEXT,
  issued_at          TIMESTAMPTZ NOT NULL,
  recorded_event_seq BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_receipt_operation ON receipt (operation_no);

-- 模拟目标端收件箱（演示用远端系统，与本服务状态相互独立）
CREATE TABLE IF NOT EXISTS target_inbox (
  operation_no    TEXT PRIMARY KEY,
  payload_sha256  TEXT NOT NULL,
  request_payload JSONB NOT NULL,
  status          TEXT NOT NULL,
  received_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS target_receipt (
  receipt_id   TEXT PRIMARY KEY,
  operation_no TEXT NOT NULL,
  receipt_type TEXT NOT NULL,
  issued_at    TIMESTAMPTZ NOT NULL
);

-- 故障注入开关（演示超时未知场景）
CREATE TABLE IF NOT EXISTS fault_switch (
  id           INT PRIMARY KEY CHECK (id = 1),
  timeout_next BOOLEAN NOT NULL DEFAULT FALSE
);

-- 注：入站库与事件账的不可变触发器由 ImmutabilityGuardConfig 在启动时以单语句方式创建
-- （PL/pgSQL 函数体含分号，不能放在本脚本中按分号拆分执行）。
