package com.foodsafety.exchange.sampling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * 抽样框分层名册：目标总体的全部（地区 × 品类）层及批次总量 N_h。
 * 各层 N_h 之和须等于抽样框总量；名册中有、但无抽样单位的层为覆盖缺口。
 */
@Entity
@Table(name = "frame_stratum")
@IdClass(FrameStratum.Key.class)
public class FrameStratum {

  @Id
  @Column(name = "frame_id")
  private String frameId;

  @Id
  @Column(name = "region")
  private String region;

  @Id
  @Column(name = "category")
  private String category;

  @Column(name = "population", nullable = false)
  private long population;

  protected FrameStratum() {}

  public FrameStratum(String frameId, String region, String category, long population) {
    this.frameId = frameId;
    this.region = region;
    this.category = category;
    this.population = population;
  }

  public String getFrameId() { return frameId; }
  public String getRegion() { return region; }
  public String getCategory() { return category; }
  public long getPopulation() { return population; }

  public String stratumKey() {
    return region + "||" + category;
  }

  /** 复合主键：抽样框 + 地区 + 品类。 */
  public static class Key implements Serializable {
    private String frameId;
    private String region;
    private String category;

    protected Key() {}

    public Key(String frameId, String region, String category) {
      this.frameId = frameId;
      this.region = region;
      this.category = category;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key key)) {
        return false;
      }
      return Objects.equals(frameId, key.frameId) && Objects.equals(region, key.region)
          && Objects.equals(category, key.category);
    }

    @Override
    public int hashCode() {
      return Objects.hash(frameId, region, category);
    }
  }
}
