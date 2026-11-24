package com.foodsafety.exchange.sampling.estimate;

import com.foodsafety.exchange.sampling.CalibrationTotal;
import com.foodsafety.exchange.sampling.CalibrationTotalRepository;
import com.foodsafety.exchange.sampling.FrameStratum;
import com.foodsafety.exchange.sampling.FrameStratumRepository;
import com.foodsafety.exchange.sampling.LabReport;
import com.foodsafety.exchange.sampling.LabReportRepository;
import com.foodsafety.exchange.sampling.SamplingFrame;
import com.foodsafety.exchange.sampling.SamplingFrameRepository;
import com.foodsafety.exchange.sampling.SamplingUnit;
import com.foodsafety.exchange.sampling.SamplingUnitRepository;
import com.foodsafety.exchange.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 估计装配服务：把抽样域实体组装为批次观测（复检在此归约为每抽样单位一个最终判定），
 * 调用纯数学估计器与重抽样分析器，并对“权重分母与抽样框总量对平”做服务端核验。
 */
@Service
public class EstimationService {

  private final SamplingFrameRepository frameRepo;
  private final FrameStratumRepository stratumRepo;
  private final SamplingUnitRepository unitRepo;
  private final LabReportRepository reportRepo;
  private final CalibrationTotalRepository calibrationRepo;

  public EstimationService(SamplingFrameRepository frameRepo, FrameStratumRepository stratumRepo,
      SamplingUnitRepository unitRepo, LabReportRepository reportRepo,
      CalibrationTotalRepository calibrationRepo) {
    this.frameRepo = frameRepo;
    this.stratumRepo = stratumRepo;
    this.unitRepo = unitRepo;
    this.reportRepo = reportRepo;
    this.calibrationRepo = calibrationRepo;
  }

  /** 一次估计所需的全部装配数据。 */
  public static final class Assembly {
    public SamplingFrame frame;
    public List<UnitObservation> observations;
    public Map<String, Long> stratumPopulation;
    public Map<String, Map<String, Long>> calibrationMargins;
    public int recheckCount;
    public int distinctUnits;
  }

  /** 装配抽样框：复检归约、非响应标记、层总量与校准边际。 */
  public Assembly assemble(String frameId) {
    SamplingFrame frame = frameRepo.findById(frameId)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_FRAME", "抽样框不存在: " + frameId));
    List<SamplingUnit> units = unitRepo.findByFrameIdOrderByUnitId(frameId);
    List<LabReport> allReports = reportRepo.findByFrameIdOrderByIssuedAt(frameId);
    int recheckCount = (int) allReports.stream().filter(LabReport::isRecheck).count();

    Map<String, Long> stratumPopulation = new LinkedHashMap<>();
    for (FrameStratum s : stratumRepo.findByFrameId(frameId)) {
      stratumPopulation.put(s.stratumKey(), s.getPopulation());
    }

    Map<String, Map<String, Long>> margins = new LinkedHashMap<>();
    for (CalibrationTotal t : calibrationRepo.findByFrameId(frameId)) {
      // 内部统一用小写维度键，与 DesignWeightEstimator 的 category/region/productionScale 对齐
      margins.computeIfAbsent(t.getDimension().toLowerCase(), k -> new LinkedHashMap<>())
          .put(t.getLevelValue(), t.getPopTotal());
    }

    List<UnitObservation> observations = new ArrayList<>();
    for (SamplingUnit u : units) {
      List<LabReport> reports = reportRepo.findByUnitIdOrderByIssuedAt(u.getUnitId());
      // 复检归约：取时间序列最后一份报告作为该批次唯一最终判定
      Boolean outcome = reports.isEmpty() ? null
          : reports.get(reports.size() - 1).isNonconforming();
      boolean responded = "RESPONDED".equals(u.getResponseStatus()) && outcome != null;
      observations.add(new UnitObservation(
          u.getUnitId(), u.stratumKey(), u.getRegion(), u.getCategory(),
          u.getProductionScale(), u.getInclusionProb(), 1.0 / u.getInclusionProb(),
          outcome, responded, u.isOversample(),
          u.getStratumPopulation(), u.getStratumSampled()));
    }

    Assembly a = new Assembly();
    a.frame = frame;
    a.observations = observations;
    a.stratumPopulation = stratumPopulation;
    a.calibrationMargins = margins;
    a.recheckCount = recheckCount;
    a.distinctUnits = units.size();
    return a;
  }

  /** 三类估计对照 + 对平核验。 */
  public Map<String, Object> compareEstimates(String frameId) {
    Assembly a = assemble(frameId);
    long n = a.frame.getTotalPopulation();

    DesignWeightEstimator.Estimate naive =
        DesignWeightEstimator.naive(a.observations, n);
    DesignWeightEstimator.Estimate design =
        DesignWeightEstimator.designWeighted(a.observations, n, a.stratumPopulation);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("frameId", frameId);
    result.put("targetPopulation", n);
    result.put("distinctSamplingUnits", a.distinctUnits);
    result.put("recheckReportsExcluded", a.recheckCount);
    result.put("estimationUnit", "批次（一个抽样单位 = 一个批次；复检不新增单位）");
    result.put("naive", toView(naive));
    result.put("designWeighted", toView(design));

    if (!a.calibrationMargins.isEmpty()) {
      DesignWeightEstimator.Estimate calibrated = DesignWeightEstimator.calibrated(
          a.observations, n, a.stratumPopulation, a.calibrationMargins, 200, 1e-9);
      result.put("calibrated", toView(calibrated));
    } else {
      Map<String, Object> skip = new LinkedHashMap<>();
      skip.put("skipped", true);
      skip.put("reason", "未登记校准辅助总量，无法做校准估计；设计加权估计仍可用");
      result.put("calibrated", skip);
    }

    result.put("balanceCheck",
        DesignWeightEstimator.balanceCheck(a.observations, n, a.stratumPopulation));

    // 服务端硬核验：响应完全时设计权重分母必须等于 N；非响应时报告缺口
    Map<String, Object> guard = new LinkedHashMap<>();
    long nonResp = a.observations.stream().filter(o -> !o.responded()).count();
    double weightSum = a.observations.stream().filter(UnitObservation::responded)
        .mapToDouble(UnitObservation::designWeight).sum();
    guard.put("nonResponseUnits", nonResp);
    guard.put("respondentDesignWeightSum", weightSum);
    guard.put("frameTotal", n);
    guard.put("balanced", Math.abs(weightSum - n) < 1e-6);
    if (nonResp > 0) {
      guard.put("note", "存在非响应，响应者权重和低于 N，缺口=" + (n - weightSum)
          + " 对应未响应批次权重；完全响应后应对平");
    }
    result.put("reconciliationGuard", guard);
    return result;
  }

  /** 重抽样与设计诊断综合报告。 */
  public Map<String, Object> diagnostics(String frameId, int replications, long seed) {
    Assembly a = assemble(frameId);
    long n = a.frame.getTotalPopulation();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("frameId", frameId);
    result.put("targetPopulation", n);
    result.put("weightDiagnostics",
        ResamplingAnalyzer.weightDiagnostics(a.observations));
    result.put("coverageGaps",
        ResamplingAnalyzer.coverageGaps(a.observations, a.stratumPopulation, 3));
    result.put("nonResponseImpact",
        ResamplingAnalyzer.nonResponseImpact(a.observations, n));
    result.put("oversampleCheck",
        ResamplingAnalyzer.oversampleCheck(a.observations, a.stratumPopulation, n));
    result.put("stratifiedBootstrap",
        ResamplingAnalyzer.stratifiedBootstrap(a.observations, replications, seed));
    return result;
  }

  /** 估计结果转 API 视图。 */
  public static Map<String, Object> toView(DesignWeightEstimator.Estimate e) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("method", e.method);
    v.put("pointEstimate", e.pointEstimate);
    v.put("variance", e.variance);
    v.put("standardError", e.standardError);
    v.put("marginOfError95", e.marginOfError);
    v.put("confidenceInterval95", List.of(e.lowerBound, e.upperBound));
    v.put("respondingUnits", e.respondingUnits);
    v.put("nonconformingCount", e.nonconformingCount);
    v.put("weightDenominator", e.weightDenominator);
    v.put("targetPopulation", e.targetPopulation);
    v.put("stratumContributions", e.stratumContributions);
    v.put("diagnostics", e.diagnostics);
    return v;
  }
}
