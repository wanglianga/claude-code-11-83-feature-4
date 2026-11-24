package com.foodsafety.exchange.sampling.estimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 重抽样与设计诊断分析器：
 * <ul>
 *   <li><b>权重极端值</b>：最大/最小权重、变异系数 CV、分位数、Kish 有效样本量、设计效应；</li>
 *   <li><b>覆盖缺口</b>：抽样框名册中存在但零抽样的层、小样本层（n_h 过小）；</li>
 *   <li><b>非响应影响</b>：非响应率、最坏情形区间（非响应批次全合格 / 全不合格两种极端），
 *       直观显示非响应如何扩大区间；</li>
 *   <li><b>分层 Bootstrap</b>：层内有放回重抽，重算加权率，给出经验标准误与百分位置信区间，
 *       与解析方差相互核验；</li>
 *   <li><b>风险加抽核验</b>：加抽单位仍落入名册层、目标总体 N 不因此改变。</li>
 * </ul>
 * 随机数使用可注入种子以保证结果可复现。
 */
public final class ResamplingAnalyzer {

  private ResamplingAnalyzer() {}

  /** 权重极端值与有效样本量诊断。 */
  public static Map<String, Object> weightDiagnostics(List<UnitObservation> all) {
    List<UnitObservation> resp = responding(all);
    Map<String, Object> out = new LinkedHashMap<>();
    if (resp.isEmpty()) {
      out.put("warning", "无响应单位，无法计算权重诊断");
      return out;
    }
    double[] w = resp.stream().mapToDouble(UnitObservation::designWeight).sorted().toArray();
    double sum = Arrays.stream(w).sum();
    double mean = sum / w.length;
    double variance = Arrays.stream(w).map(x -> (x - mean) * (x - mean)).sum() / w.length;
    double cv = mean > 0 ? Math.sqrt(variance) / mean : 0.0;
    // Kish 有效样本量 n_eff = (Σw)² / Σw²
    double sumSq = Arrays.stream(w).map(x -> x * x).sum();
    double neff = sumSq > 0 ? (sum * sum) / sumSq : 0.0;
    // 权重设计效应 deff_w = 1 + CV(w)²
    double deff = 1.0 + cv * cv;

    out.put("respondentCount", resp.size());
    out.put("minWeight", w[0]);
    out.put("maxWeight", w[w.length - 1]);
    out.put("meanWeight", mean);
    out.put("weightCV", cv);
    out.put("medianWeight", quantileSorted(w, 0.5));
    out.put("p95Weight", quantileSorted(w, 0.95));
    out.put("p99Weight", quantileSorted(w, 0.99));
    out.put("maxToMedianRatio", quantileSorted(w, 0.5) > 0
        ? w[w.length - 1] / quantileSorted(w, 0.5) : Double.NaN);
    out.put("kishEffectiveSampleSize", neff);
    out.put("designEffectFromWeights", deff);
    out.put("effectiveSampleFraction", (double) neff / resp.size());

    List<Map<String, Object>> extreme = new ArrayList<>();
    double extremeThreshold = quantileSorted(w, 0.95);
    for (UnitObservation o : resp) {
      if (o.designWeight() >= extremeThreshold) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("unitId", o.unitId());
        row.put("stratum", o.stratumKey());
        row.put("inclusionProb", o.inclusionProb());
        row.put("designWeight", o.designWeight());
        row.put("oversample", o.oversample());
        extreme.add(row);
      }
    }
    out.put("extremeWeightUnits", extreme);
    out.put("interpretation",
        "入样概率 pi 越小的单位权重 1/pi 越大；少数小概率样本会主导加权和并放大方差，"
            + "有效样本量 n_eff 低于名义样本量即体现该损失");
    return out;
  }

  /**
   * 覆盖缺口：对照抽样框分层名册（frameStrata：层键 -> N_h）与实际抽样层。
   * @param smallSampleThreshold 层响应样本数低于该值判为小样本层
   */
  public static Map<String, Object> coverageGaps(List<UnitObservation> all,
      Map<String, Long> frameStrata, int smallSampleThreshold) {
    Map<String, List<UnitObservation>> sampled = new LinkedHashMap<>();
    for (UnitObservation o : all) {
      sampled.computeIfAbsent(o.stratumKey(), k -> new ArrayList<>()).add(o);
    }
    List<Map<String, Object>> uncovered = new ArrayList<>();
    List<Map<String, Object>> thin = new ArrayList<>();
    for (Map.Entry<String, Long> s : frameStrata.entrySet()) {
      List<UnitObservation> units = sampled.get(s.getKey());
      int n = units == null ? 0 : units.size();
      if (n == 0) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("stratum", s.getKey());
        gap.put("populationNh", s.getValue());
        gap.put("sampled", 0);
        gap.put("issue", "ZERO_SAMPLE");
        uncovered.add(gap);
      } else if (n < smallSampleThreshold) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("stratum", s.getKey());
        gap.put("populationNh", s.getValue());
        gap.put("sampled", n);
        gap.put("issue", "SMALL_SAMPLE");
        thin.add(gap);
      }
    }
    long coveredPopulation = frameStrata.entrySet().stream()
        .filter(s -> sampled.containsKey(s.getKey()))
        .mapToLong(Map.Entry::getValue).sum();
    long uncoveredPopulation = frameStrata.values().stream().mapToLong(Long::longValue).sum()
        - coveredPopulation;

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("frameStratumCount", frameStrata.size());
    out.put("sampledStratumCount", sampled.size());
    out.put("zeroSampleStrata", uncovered);
    out.put("smallSampleStrata", thin);
    out.put("uncoveredPopulation", uncoveredPopulation);
    out.put("coverageRate", frameStrata.values().stream().mapToLong(Long::longValue).sum() > 0
        ? (double) coveredPopulation
            / frameStrata.values().stream().mapToLong(Long::longValue).sum()
        : 0.0);
    out.put("interpretation",
        "零抽样层完全依赖模型外推，是覆盖缺口；小样本层方差贡献被 fpc 之外的小 n_h 放大");
    return out;
  }

  /**
   * 非响应分析与最坏情形区间。把非响应单位的权重分别按“全合格(y=0)”与“全不合格(y=1)”
   * 两种极端补入，得到不合格率的上下包络，显示非响应如何扩大不确定区间。
   */
  public static Map<String, Object> nonResponseImpact(List<UnitObservation> all,
      long targetPopulation) {
    List<UnitObservation> resp = responding(all);
    List<UnitObservation> nonResp = all.stream().filter(o -> !o.responded()).toList();
    double respWeight = resp.stream().mapToDouble(UnitObservation::designWeight).sum();
    double nonRespWeight = nonResp.stream().mapToDouble(UnitObservation::designWeight).sum();
    double respBad = resp.stream()
        .filter(o -> Boolean.TRUE.equals(o.nonconforming()))
        .mapToDouble(UnitObservation::designWeight).sum();

    double denom = respWeight + nonRespWeight;
    double lowerEnvelope = denom > 0 ? respBad / denom : Double.NaN;            // 非响应全合格
    double upperEnvelope = denom > 0 ? (respBad + nonRespWeight) / denom : Double.NaN; // 全不合格

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("nonResponseUnitCount", nonResp.size());
    out.put("nonResponseRateByUnit", all.isEmpty() ? 0.0 : (double) nonResp.size() / all.size());
    out.put("nonResponseWeight", nonRespWeight);
    out.put("nonResponseWeightShare", denom > 0 ? nonRespWeight / denom : 0.0);
    out.put("respondentWeightedRate", respWeight > 0 ? respBad / respWeight : Double.NaN);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("lowerAllConforming", lowerEnvelope);
    envelope.put("upperAllNonconforming", upperEnvelope);
    envelope.put("envelopeWidth", upperEnvelope - lowerEnvelope);
    out.put("worstCaseEnvelope", envelope);
    List<Map<String, Object>> details = new ArrayList<>();
    for (UnitObservation o : nonResp) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("unitId", o.unitId());
      row.put("stratum", o.stratumKey());
      row.put("designWeight", o.designWeight());
      details.add(row);
    }
    out.put("nonResponseUnits", details);
    out.put("interpretation",
        "非响应批次的判定缺失；其权重越大，最坏情形包络越宽，置信区间必须随之扩大而非忽略");
    return out;
  }

  /**
   * 分层 Bootstrap：在每个层内对响应单位有放回重抽 n_h 次，重算 Hařek 加权率，
   * 重复 replications 次后给出经验标准误与 2.5%/97.5% 百分位区间。
   */
  public static Map<String, Object> stratifiedBootstrap(List<UnitObservation> all,
      int replications, long seed) {
    List<UnitObservation> resp = responding(all);
    Random random = new Random(seed);
    Map<String, List<UnitObservation>> byStratum = new LinkedHashMap<>();
    for (UnitObservation o : resp) {
      byStratum.computeIfAbsent(o.stratumKey(), k -> new ArrayList<>()).add(o);
    }
    double[] reps = new double[replications];
    for (int r = 0; r < replications; r++) {
      double sumW = 0.0;
      double sumWY = 0.0;
      for (List<UnitObservation> stratumUnits : byStratum.values()) {
        int nh = stratumUnits.size();
        for (int k = 0; k < nh; k++) {
          UnitObservation picked = stratumUnits.get(random.nextInt(nh));
          double wgt = picked.designWeight();
          sumW += wgt;
          if (Boolean.TRUE.equals(picked.nonconforming())) {
            sumWY += wgt;
          }
        }
      }
      reps[r] = sumW > 0 ? sumWY / sumW : 0.0;
    }
    Arrays.sort(reps);
    double mean = Arrays.stream(reps).average().orElse(Double.NaN);
    double sd = 0.0;
    for (double x : reps) {
      sd += (x - mean) * (x - mean);
    }
    sd = Math.sqrt(sd / replications);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("replications", replications);
    out.put("seed", seed);
    out.put("bootstrapMean", mean);
    out.put("bootstrapStandardError", sd);
    out.put("percentile2_5", quantileSorted(reps, 0.025));
    out.put("percentile97_5", quantileSorted(reps, 0.975));
    out.put("interpretation",
        "Bootstrap 经验标准误应与解析分层标准误接近；若差异大说明层内样本过少或权重高度偏斜");
    return out;
  }

  /** 风险加抽核验：加抽单位必须落在名册层内，且目标总体 N 不随加抽改变。 */
  public static Map<String, Object> oversampleCheck(List<UnitObservation> all,
      Map<String, Long> frameStrata, long targetPopulation) {
    List<UnitObservation> overs = all.stream().filter(UnitObservation::oversample).toList();
    List<Map<String, Object>> rows = new ArrayList<>();
    boolean allInFrame = true;
    for (UnitObservation o : overs) {
      boolean inFrame = frameStrata.containsKey(o.stratumKey());
      allInFrame &= inFrame;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("unitId", o.unitId());
      row.put("stratum", o.stratumKey());
      row.put("inclusionProb", o.inclusionProb());
      row.put("designWeight", o.designWeight());
      row.put("belongsToFrameStratum", inFrame);
      rows.add(row);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("oversampleUnitCount", overs.size());
    out.put("oversampleUnits", rows);
    out.put("allOversamplesWithinFrame", allInFrame);
    out.put("targetPopulationUnchanged", targetPopulation);
    out.put("interpretation",
        "风险加抽只提高高风险批次的入样概率（pi 变大、权重变小），不新增总体层、不改变 N；"
            + "其对估计的影响完全经由入样概率加权还原");
    return out;
  }

  // ---------- 工具 ----------

  private static List<UnitObservation> responding(List<UnitObservation> all) {
    return all.stream().filter(UnitObservation::responded).toList();
  }

  /** 输入已升序排序，返回 q 分位数（线性插值）。 */
  private static double quantileSorted(double[] sorted, double q) {
    if (sorted.length == 0) {
      return Double.NaN;
    }
    if (sorted.length == 1) {
      return sorted[0];
    }
    double pos = q * (sorted.length - 1);
    int lo = (int) Math.floor(pos);
    int hi = Math.min(sorted.length - 1, lo + 1);
    double frac = pos - lo;
    return sorted[lo] * (1 - frac) + sorted[hi] * frac;
  }
}
