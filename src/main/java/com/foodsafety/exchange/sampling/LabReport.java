package com.foodsafety.exchange.sampling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 实验室报告：链接到抽样单（抽样单位/批次）。
 * recheck=TRUE 表示同一批次的复检，仅作为该抽样单位的结论沿革，
 * 不新增抽样单位、不作为新样本进入估计。
 * 一个抽样单位可有初检 + 多份复检；批次级最终判定以最后一份报告（复检优先）为准。
 */
@Entity
@Table(name = "lab_report")
public class LabReport {

  @Id
  @Column(name = "report_id")
  private String reportId;

  @Column(name = "frame_id", nullable = false)
  private String frameId;

  @Column(name = "unit_id", nullable = false)
  private String unitId;

  @Column(name = "lab_name", nullable = false)
  private String labName;

  @Column(name = "recheck", nullable = false)
  private boolean recheck;

  @Column(name = "nonconforming", nullable = false)
  private boolean nonconforming;

  @Column(name = "issued_at", nullable = false)
  private OffsetDateTime issuedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected LabReport() {}

  public LabReport(String reportId, String frameId, String unitId, String labName,
      boolean recheck, boolean nonconforming, OffsetDateTime issuedAt) {
    this.reportId = reportId;
    this.frameId = frameId;
    this.unitId = unitId;
    this.labName = labName;
    this.recheck = recheck;
    this.nonconforming = nonconforming;
    this.issuedAt = issuedAt;
    this.createdAt = OffsetDateTime.now();
  }

  public String getReportId() { return reportId; }
  public String getFrameId() { return frameId; }
  public String getUnitId() { return unitId; }
  public String getLabName() { return labName; }
  public boolean isRecheck() { return recheck; }
  public boolean isNonconforming() { return nonconforming; }
  public OffsetDateTime getIssuedAt() { return issuedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
