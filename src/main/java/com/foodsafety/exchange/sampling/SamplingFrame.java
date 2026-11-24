package com.foodsafety.exchange.sampling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 抽样框：一次抽样设计的总体名册。
 * totalPopulation 为目标总体批次总量 N，须等于各抽样单位所属层批次总量的去重合计（对平校验）。
 */
@Entity
@Table(name = "sampling_frame")
public class SamplingFrame {

  @Id
  @Column(name = "frame_id")
  private String frameId;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "total_population", nullable = false)
  private long totalPopulation;

  @Column(name = "period")
  private String period;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected SamplingFrame() {}

  public SamplingFrame(String frameId, String title, long totalPopulation, String period) {
    this.frameId = frameId;
    this.title = title;
    this.totalPopulation = totalPopulation;
    this.period = period;
    this.createdAt = OffsetDateTime.now();
  }

  public String getFrameId() { return frameId; }
  public String getTitle() { return title; }
  public long getTotalPopulation() { return totalPopulation; }
  public String getPeriod() { return period; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
