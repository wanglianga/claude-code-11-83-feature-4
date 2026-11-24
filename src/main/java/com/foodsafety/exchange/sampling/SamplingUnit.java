package com.foodsafety.exchange.sampling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 抽样单位（抽样单）：一行 = 抽中的一个批次，是估计的基本单位。
 * 保存抽样设计依据：地区/品类/生产规模（层与校准变量）、层批次总量、层抽样数、
 * 已知入样概率 inclusionProb、风险加抽标记与非响应原因。
 * 同一批次的复检挂在该单位下，不新增抽样单位。
 */
@Entity
@Table(name = "sampling_unit")
public class SamplingUnit {

  @Id
  @Column(name = "unit_id")
  private String unitId;

  @Column(name = "frame_id", nullable = false)
  private String frameId;

  @Column(name = "region", nullable = false)
  private String region;

  @Column(name = "category", nullable = false)
  private String category;

  @Column(name = "production_scale", nullable = false)
  private String productionScale;

  @Column(name = "stratum_population", nullable = false)
  private long stratumPopulation;

  @Column(name = "stratum_sampled", nullable = false)
  private int stratumSampled;

  @Column(name = "inclusion_prob", nullable = false)
  private double inclusionProb;

  @Column(name = "oversample", nullable = false)
  private boolean oversample;

  @Column(name = "response_status", nullable = false)
  private String responseStatus;

  @Column(name = "non_response_reason")
  private String nonResponseReason;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected SamplingUnit() {}

  public SamplingUnit(String unitId, String frameId, String region, String category,
      String productionScale, long stratumPopulation, int stratumSampled,
      double inclusionProb, boolean oversample) {
    this.unitId = unitId;
    this.frameId = frameId;
    this.region = region;
    this.category = category;
    this.productionScale = productionScale;
    this.stratumPopulation = stratumPopulation;
    this.stratumSampled = stratumSampled;
    this.inclusionProb = inclusionProb;
    this.oversample = oversample;
    this.responseStatus = "RESPONDED";
    this.createdAt = OffsetDateTime.now();
  }

  public void markNonResponse(String reason) {
    this.responseStatus = "NON_RESPONSE";
    this.nonResponseReason = reason;
  }

  public String getUnitId() { return unitId; }
  public String getFrameId() { return frameId; }
  public String getRegion() { return region; }
  public String getCategory() { return category; }
  public String getProductionScale() { return productionScale; }
  public long getStratumPopulation() { return stratumPopulation; }
  public int getStratumSampled() { return stratumSampled; }
  public double getInclusionProb() { return inclusionProb; }
  public boolean isOversample() { return oversample; }
  public String getResponseStatus() { return responseStatus; }
  public String getNonResponseReason() { return nonResponseReason; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  /** 层键：地区 × 品类（风险加抽单位并入其原属层，不另立总体）。 */
  public String stratumKey() {
    return region + "||" + category;
  }
}
