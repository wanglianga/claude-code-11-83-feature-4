package com.foodsafety.exchange.sampling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 校准辅助总量：按校准维度（品类 CATEGORY / 地区 REGION / 生产规模 PRODUCTION_SCALE）
 * 登记的已知总体批次总量。校准估计（raking/GCE）依赖这些辅助总量正确，
 * 使加权后的样本边际分布与总体一致。
 */
@Entity
@Table(name = "calibration_total")
public class CalibrationTotal {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "frame_id", nullable = false)
  private String frameId;

  @Column(name = "dimension", nullable = false)
  private String dimension;

  @Column(name = "level_value", nullable = false)
  private String levelValue;

  @Column(name = "pop_total", nullable = false)
  private long popTotal;

  protected CalibrationTotal() {}

  public CalibrationTotal(String frameId, String dimension, String levelValue, long popTotal) {
    this.frameId = frameId;
    this.dimension = dimension;
    this.levelValue = levelValue;
    this.popTotal = popTotal;
  }

  public Long getId() { return id; }
  public String getFrameId() { return frameId; }
  public String getDimension() { return dimension; }
  public String getLevelValue() { return levelValue; }
  public long getPopTotal() { return popTotal; }
}
