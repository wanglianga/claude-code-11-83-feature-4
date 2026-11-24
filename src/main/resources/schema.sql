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

-- ======================================================================
-- 抽样设计域：抽样框 -> 抽样单位（抽样单）-> 实验室报告（含复检）
-- 目标总体为“抽样框内批次”，估计单位为批次（一个抽样单位 = 一个批次）。
-- 同一批次的复检只保留同一抽样单位，绝不作为新样本重复计数。
-- ======================================================================

-- 抽样框：一次抽样设计的总体名册及其辅助信息
CREATE TABLE IF NOT EXISTS sampling_frame (
  frame_id        TEXT PRIMARY KEY,
  title           TEXT NOT NULL,
  -- 目标总体批次总量（N = 各层批次总量之和，由服务端对平校验）
  total_population BIGINT NOT NULL,
  period          TEXT,
  created_at      TIMESTAMPTZ NOT NULL
);

-- 抽样框分层名册：列出目标总体的全部（地区 × 品类）层及其批次总量 N_h。
-- 各层 N_h 之和必须等于 sampling_frame.total_population（对平校验）；
-- 名册中存在但抽样单位数为 0 的层即“覆盖缺口”。
CREATE TABLE IF NOT EXISTS frame_stratum (
  frame_id    TEXT NOT NULL REFERENCES sampling_frame(frame_id),
  region      TEXT NOT NULL,
  category    TEXT NOT NULL,
  population  BIGINT NOT NULL,
  PRIMARY KEY (frame_id, region, category)
);

-- 抽样单位（抽样单）：一行 = 抽中的一个批次
CREATE TABLE IF NOT EXISTS sampling_unit (
  unit_id              TEXT PRIMARY KEY,
  frame_id             TEXT NOT NULL REFERENCES sampling_frame(frame_id),
  region               TEXT NOT NULL,   -- 地区（层变量）
  category             TEXT NOT NULL,   -- 品类（层变量）
  production_scale     TEXT NOT NULL,   -- 生产规模（校准变量：SMALL/MEDIUM/LARGE）
  -- 该单位所属层在抽样框中的批次总量 N_h
  stratum_population   BIGINT NOT NULL,
  -- 该层实际抽中的抽样单位数 n_h
  stratum_sampled      INT NOT NULL,
  -- 已知入样概率 pi（0,1]；设计权重 d = 1/pi
  inclusion_prob       DOUBLE PRECISION NOT NULL,
  -- 风险加抽标记：TRUE 表示针对高风险批次的额外抽样（过抽样），
  -- 其总体仍是原抽样框，不改变目标总体，只通过入样概率进入加权
  oversample           BOOLEAN NOT NULL DEFAULT FALSE,
  -- 响应状态：RESPONDED（已检）/ NON_RESPONSE（非响应）
  response_status      TEXT NOT NULL DEFAULT 'RESPONDED',
  non_response_reason  TEXT,
  created_at           TIMESTAMPTZ NOT NULL,
  UNIQUE (frame_id, unit_id)
);
CREATE INDEX IF NOT EXISTS idx_unit_frame ON sampling_unit (frame_id);

-- 实验室报告：链接到抽样单；复检报告以 recheck=TRUE 挂在同一抽样单位下
CREATE TABLE IF NOT EXISTS lab_report (
  report_id     TEXT PRIMARY KEY,
  frame_id      TEXT NOT NULL REFERENCES sampling_frame(frame_id),
  unit_id       TEXT NOT NULL REFERENCES sampling_unit(unit_id),
  lab_name      TEXT NOT NULL,
  -- 是否复检：TRUE 表示同批次复检，不新增抽样单位、不重复计数
  recheck       BOOLEAN NOT NULL DEFAULT FALSE,
  -- 最终批次级判定：TRUE=不合格。复检存在时以复检结论为准（服务端归约）
  nonconforming BOOLEAN NOT NULL,
  issued_at     TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_report_unit ON lab_report (unit_id);
CREATE INDEX IF NOT EXISTS idx_report_frame ON lab_report (frame_id);

-- 校准辅助总量：按校准维度（品类/地区/生产规模）登记的已知总体批次总量
CREATE TABLE IF NOT EXISTS calibration_total (
  id           BIGSERIAL PRIMARY KEY,
  frame_id     TEXT NOT NULL REFERENCES sampling_frame(frame_id),
  dimension    TEXT NOT NULL,          -- CATEGORY / REGION / PRODUCTION_SCALE
  level_value  TEXT NOT NULL,          -- 该维度的取值
  pop_total    BIGINT NOT NULL,        -- 该取值在总体中的已知批次总量
  UNIQUE (frame_id, dimension, level_value)
);
CREATE INDEX IF NOT EXISTS idx_cal_frame ON calibration_total (frame_id);
