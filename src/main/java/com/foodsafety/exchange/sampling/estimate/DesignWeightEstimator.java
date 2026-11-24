package com.foodsafety.exchange.sampling.estimate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯数学估计器：在同一抽样框内估计批次级不合格率。
 *
 * <p>提供三类估计：
 * <ol>
 *   <li><b>未加权（朴素）</b>：阳性样本 / 响应样本数，风险加抽会高估总体率，仅作对照；</li>
 *   <li><b>设计加权（Horvitz–Thompson / Hařek）</b>：按已知入样概率 pi 取权重 d=1/pi，
 *       风险加抽因 pi 大、权重小而被正确还原；分层方差带有限总体校正；</li>
 *   <li><b>校准（raking/GCE 迭代比例拟合）</b>：以设计权重为底，按品类/地区/生产规模的
 *       已知辅助总量调整权重，使加权边际与总体一致；精度可能提高，但依赖辅助总量正确与模型充分。</li>
 * </ol>
 *
 * <p>非响应单位不进入点估计的分子/分母（按响应者归算并显式提示），其存在通过有效样本下降
 * 与非响应诊断体现为更宽的置信区间。复检在进入本类前已归约为每抽样单位一个判定。
 */
public final class DesignWeightEstimator {

  /** 95% 置信度对应的正态分位数。 */
  public static final double Z_95 = 1.959964;

  private DesignWeightEstimator() {}

  /** 一次估计的完整结果（点估计、方差、标准误、置信区间、各层贡献、对平信息）。 */
  public static final class Estimate {
    public String method;
    public double pointEstimate;
    public double variance;
    public double standardError;
    public double marginOfError;
    public double lowerBound;
    public double upperBound;
    public int respondingUnits;
    public int nonconformingCount;
    public double weightDenominator;
    public long targetPopulation;
    public List<Map<String, Object>> stratumContributions = new ArrayList<>();
    public Map<String, Object> diagnostics = new LinkedHashMap<>();
  }

  /**
   * 未加权朴素估计：仅对响应单位等权计数。忽略入样概率，风险加抽越多偏差越大。
   */
  public static Estimate naive(List<UnitObservation> all, long targetPopulation) {
    List<UnitObservation> resp = responding(all);
    Estimate e = base("NAIVE_UNWEIGHTED", targetPopulation);
    e.respondingUnits = resp.size();
    e.nonconformingCount = (int) resp.stream().filter(o -> Boolean.TRUE.equals(o.nonconforming())).count();
    double n = resp.size();
    double p = n == 0 ? Double.NaN : e.nonconformingCount / n;
    e.pointEstimate = p;
    // 朴素 SRS 二项方差（仅作对照，不反映设计效应）
    e.variance = n <= 1 ? 0.0 : p * (1 - p) / n;
    e.weightDenominator = n;
    finalizeInterval(e);
    e.diagnostics.put("warning", "未加权估计忽略入样概率，高风险加抽会高估总体不合格率，仅作对照");
    return e;
  }

  /**
   * 设计加权估计。点估计采用 Hařek 比率形式（加权和之比，对非响应更稳健），
   * 方差按分层随机抽样、层内带有限总体校正（fpc）计算：
   * <pre>
   *   V(p̂) = Σ_h (1-f_h) · (N_h / N)² · s_h² / n_h
   *   s_h² = n_h/(n_h-1) · p_h(1-p_h)
   * </pre>
   * 其中 f_h = n_h/N_h；HT 加权点估计与 Hařek 比率点估计同时给出，便于核验。
   */
  public static Estimate designWeighted(List<UnitObservation> all, long targetPopulation,
      Map<String, Long> stratumPopulation) {
    List<UnitObservation> resp = responding(all);
    Estimate e = base("DESIGN_WEIGHTED_HT_HAJEK", targetPopulation);
    e.respondingUnits = resp.size();
    e.nonconformingCount = (int) resp.stream().filter(o -> Boolean.TRUE.equals(o.nonconforming())).count();

    double sumW = 0.0;
    double sumWY = 0.0;
    for (UnitObservation o : resp) {
      double w = o.designWeight();
      sumW += w;
      if (Boolean.TRUE.equals(o.nonconforming())) {
        sumWY += w;
      }
    }
    e.weightDenominator = sumW;

    // HT 估计：加权阳性数 / 目标总体 N
    double htNumerator = sumWY;
    double htEstimate = targetPopulation > 0 ? htNumerator / targetPopulation : Double.NaN;
    // Hařek 比率估计：加权阳性 / 加权总数（自加权、无缺失时与 HT 相等）
    double hajekEstimate = sumW > 0 ? sumWY / sumW : Double.NaN;
    e.pointEstimate = hajekEstimate;

    // 分层方差
    Map<String, List<UnitObservation>> byStratum = groupByStratum(resp);
    double variance = 0.0;
    List<Map<String, Object>> contributions = new ArrayList<>();
    for (Map.Entry<String, List<UnitObservation>> entry : byStratum.entrySet()) {
      List<UnitObservation> units = entry.getValue();
      int nh = units.size();
      long Nh = stratumPopulation.getOrDefault(entry.getKey(),
          units.get(0).stratumPopulation());
      double wh = 0.0;
      double why = 0.0;
      for (UnitObservation o : units) {
        wh += o.designWeight();
        if (Boolean.TRUE.equals(o.nonconforming())) {
          why += o.designWeight();
        }
      }
      double ph = wh > 0 ? why / wh : 0.0;
      // 层内样本方差（二项），n/(n-1) 纠偏
      double sh2 = nh > 1 ? (ph * (1 - ph) * nh) / (nh - 1) : 0.0;
      double fh = Nh > 0 ? (double) nh / Nh : 0.0;
      double fpc = Math.max(0.0, 1.0 - fh);
      double vh = fpc * Math.pow((double) Nh / targetPopulation, 2.0) * sh2 / nh;
      variance += vh;

      Map<String, Object> c = new LinkedHashMap<>();
      c.put("stratum", entry.getKey());
      c.put("region", units.get(0).region());
      c.put("category", units.get(0).category());
      c.put("populationNh", Nh);
      c.put("sampledNh", nh);
      c.put("samplingFraction", fh);
      c.put("stratumRate", ph);
      c.put("nonconformingWeighted", why);
      c.put("weightSum", wh);
      c.put("withinVariance", sh2);
      c.put("fpc", fpc);
      c.put("varianceContribution", vh);
      c.put("shareOfTotalVariance", null); // 下方回填
      contributions.add(c);
    }
    double totalV = variance;
    contributions.forEach(c -> c.put("shareOfTotalVariance",
        totalV > 0 ? (double) c.get("varianceContribution") / totalV : 0.0));
    contributions.sort((a, b) -> Double.compare(
        (double) b.get("varianceContribution"), (double) a.get("varianceContribution")));
    e.variance = variance;
    e.stratumContributions = contributions;
    finalizeInterval(e);

    e.diagnostics.put("htEstimate", htEstimate);
    e.diagnostics.put("hajekEstimate", hajekEstimate);
    e.diagnostics.put("weightedNonconforming", sumWY);
    e.diagnostics.put("weightDenominatorSumW", sumW);
    e.diagnostics.put("htVsHarekVsNaiveNote",
        "完全响应且自加权时 HT 与 Hařek 相等；非响应下 Hařek 以响应者权重和为分母");
    return e;
  }

  /**
   * 校准估计：以设计权重 d=1/pi 为底，对品类、地区、生产规模三个边际做迭代比例拟合
   * （raking / 广义回归校准的乘性形式），使每个边际的加权合计等于已知辅助总量。
   * 校准后总权重和等于辅助总量合计（应等于目标总体 N）。方差沿用分层线性化近似，
   * 并报告校准前后权重变化与 g-权重（校准因子）。
   *
   * @param maxIter 最大迭代次数
   * @param tol 边际相对误差收敛阈值
   */
  public static Estimate calibrated(List<UnitObservation> all, long targetPopulation,
      Map<String, Long> stratumPopulation, Map<String, Map<String, Long>> margins,
      int maxIter, double tol) {
    List<UnitObservation> resp = responding(all);
    Estimate e = base("CALIBRATED_RAKING", targetPopulation);
    e.respondingUnits = resp.size();
    e.nonconformingCount = (int) resp.stream().filter(o -> Boolean.TRUE.equals(o.nonconforming())).count();

    double[] w = new double[resp.size()];
    double[] d = new double[resp.size()];
    for (int i = 0; i < resp.size(); i++) {
      w[i] = resp.get(i).designWeight();
      d[i] = resp.get(i).designWeight();
    }

    // 迭代比例拟合：逐边际把当前权重调整到辅助总量
    String[] dims = {"category", "region", "productionScale"};
    double achievedTol = 0.0;
    for (int iter = 0; iter < maxIter; iter++) {
      achievedTol = 0.0;
      for (String dim : dims) {
        Map<String, Long> target = margins.getOrDefault(dim, Map.of());
        Map<String, double[]> current = new LinkedHashMap<>();
        for (int i = 0; i < resp.size(); i++) {
          String level = levelOf(resp.get(i), dim);
          current.computeIfAbsent(level, k -> new double[1])[0] += w[i];
        }
        for (Map.Entry<String, Long> t : target.entrySet()) {
          String level = t.getKey();
          double have = current.getOrDefault(level, new double[1])[0];
          if (have <= 0) {
            // 该边际取值在响应样本中完全缺失：无法校准，记录后跳过
            e.diagnostics.put("uncoveredMargin_" + dim + "_" + level,
                "响应样本无此取值，校准无法覆盖，依赖模型外推");
            continue;
          }
          double factor = t.getValue() / have;
          achievedTol = Math.max(achievedTol, Math.abs(factor - 1.0));
          for (int i = 0; i < resp.size(); i++) {
            if (levelOf(resp.get(i), dim).equals(level)) {
              w[i] *= factor;
            }
          }
        }
      }
      if (achievedTol <= tol) {
        break;
      }
    }

    double sumW = 0.0;
    double sumWY = 0.0;
    for (int i = 0; i < resp.size(); i++) {
      sumW += w[i];
      if (Boolean.TRUE.equals(resp.get(i).nonconforming())) {
        sumWY += w[i];
      }
    }
    e.weightDenominator = sumW;
    e.pointEstimate = sumW > 0 ? sumWY / sumW : Double.NaN;

    // 校准权重下方差：用校准权重重做分层线性化近似
    Map<String, List<Integer>> idxByStratum = new LinkedHashMap<>();
    for (int i = 0; i < resp.size(); i++) {
      idxByStratum.computeIfAbsent(resp.get(i).stratumKey(), k -> new ArrayList<>()).add(i);
    }
    double variance = 0.0;
    List<Map<String, Object>> contributions = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> entry : idxByStratum.entrySet()) {
      List<Integer> idxs = entry.getValue();
      int nh = idxs.size();
      long Nh = stratumPopulation.getOrDefault(entry.getKey(),
          resp.get(idxs.get(0)).stratumPopulation());
      double wh = 0.0;
      double why = 0.0;
      for (int i : idxs) {
        wh += w[i];
        if (Boolean.TRUE.equals(resp.get(i).nonconforming())) {
          why += w[i];
        }
      }
      double ph = wh > 0 ? why / wh : 0.0;
      double sh2 = nh > 1 ? (ph * (1 - ph) * nh) / (nh - 1) : 0.0;
      double fh = Nh > 0 ? (double) nh / Nh : 0.0;
      double fpc = Math.max(0.0, 1.0 - fh);
      double vh = fpc * Math.pow((double) Nh / targetPopulation, 2.0) * sh2 / nh;
      variance += vh;
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("stratum", entry.getKey());
      c.put("region", resp.get(idxs.get(0)).region());
      c.put("category", resp.get(idxs.get(0)).category());
      c.put("populationNh", Nh);
      c.put("sampledNh", nh);
      c.put("stratumRate", ph);
      c.put("calibratedWeightSum", wh);
      c.put("fpc", fpc);
      c.put("varianceContribution", vh);
      contributions.add(c);
    }
    e.variance = variance;
    e.stratumContributions = contributions;
    finalizeInterval(e);

    // g-权重（校准因子）与边际达成情况
    List<Map<String, Object>> gWeights = new ArrayList<>();
    for (int i = 0; i < resp.size(); i++) {
      Map<String, Object> g = new LinkedHashMap<>();
      g.put("unitId", resp.get(i).unitId());
      g.put("designWeight", d[i]);
      g.put("calibratedWeight", w[i]);
      g.put("gFactor", d[i] != 0 ? w[i] / d[i] : Double.NaN);
      gWeights.add(g);
    }
    e.diagnostics.put("gWeights", gWeights);
    e.diagnostics.put("calibrationConvergence", achievedTol <= tol ? "CONVERGED" : "MAX_ITER_REACHED");
    e.diagnostics.put("finalMarginRelativeError", achievedTol);
    e.diagnostics.put("calibratedWeightSum", sumW);
    e.diagnostics.put("auxiliaryTotalSum", margins.values().stream()
        .flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum());
    e.diagnostics.put("assumptionNote",
        "校准精度提高依赖辅助总量正确且校准模型充分；辅助总量错误会引入系统性偏差");
    return e;
  }

  /** 权重对平：设计权重合计与抽样框总量、分层权重和与层 N_h 的对照。 */
  public static Map<String, Object> balanceCheck(List<UnitObservation> all,
      long targetPopulation, Map<String, Long> stratumPopulation) {
    List<UnitObservation> resp = responding(all);
    Map<String, Object> result = new LinkedHashMap<>();
    double totalWeight = resp.stream().mapToDouble(UnitObservation::designWeight).sum();
    result.put("targetPopulationN", targetPopulation);
    result.put("designWeightSum", totalWeight);
    result.put("difference", totalWeight - targetPopulation);
    result.put("relativeDifference",
        targetPopulation > 0 ? Math.abs(totalWeight - targetPopulation) / targetPopulation : Double.NaN);

    List<Map<String, Object>> perStratum = new ArrayList<>();
    Map<String, List<UnitObservation>> grouped = groupByStratum(resp);
    for (Map.Entry<String, Long> s : stratumPopulation.entrySet()) {
      double wsum = grouped.getOrDefault(s.getKey(), List.of()).stream()
          .mapToDouble(UnitObservation::designWeight).sum();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("stratum", s.getKey());
      row.put("populationNh", s.getValue());
      row.put("designWeightSum", wsum);
      row.put("difference", wsum - s.getValue());
      perStratum.add(row);
    }
    result.put("perStratum", perStratum);
    result.put("interpretation",
        "完全响应且按层抽样数/层总量设计时，每层权重和=N_h、总权重和=N；"
            + "非响应会使权重和低于 N，缺口即未响应批次所占权重");
    return result;
  }

  // ---------- 内部工具 ----------

  private static Estimate base(String method, long targetPopulation) {
    Estimate e = new Estimate();
    e.method = method;
    e.targetPopulation = targetPopulation;
    return e;
  }

  private static void finalizeInterval(Estimate e) {
    e.standardError = Math.sqrt(Math.max(0.0, e.variance));
    e.marginOfError = Z_95 * e.standardError;
    double p = clamp(e.pointEstimate);
    e.lowerBound = clamp(p - e.marginOfError);
    e.upperBound = clamp(p + e.marginOfError);
    e.pointEstimate = p;
  }

  private static double clamp(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    return Math.max(0.0, Math.min(1.0, x));
  }

  private static List<UnitObservation> responding(List<UnitObservation> all) {
    return all.stream().filter(UnitObservation::responded).toList();
  }

  private static Map<String, List<UnitObservation>> groupByStratum(List<UnitObservation> units) {
    Map<String, List<UnitObservation>> map = new LinkedHashMap<>();
    for (UnitObservation o : units) {
      map.computeIfAbsent(o.stratumKey(), k -> new ArrayList<>()).add(o);
    }
    return map;
  }

  private static String levelOf(UnitObservation o, String dimension) {
    return switch (dimension) {
      case "category" -> o.category();
      case "region" -> o.region();
      case "productionScale" -> o.productionScale();
      default -> throw new IllegalArgumentException("未知校准维度: " + dimension);
    };
  }
}
