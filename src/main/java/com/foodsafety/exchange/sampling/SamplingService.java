package com.foodsafety.exchange.sampling;

import com.foodsafety.exchange.error.ApiException;
import com.foodsafety.exchange.event.EventKeys;
import com.foodsafety.exchange.event.EventLedger;
import com.foodsafety.exchange.event.EventType;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抽样管理服务：抽样框/分层名册/抽样单位/实验室报告（含复检）/非响应/校准总量的登记。
 * 所有状态变化先写入不可变事件账，再落业务表；登记时做强一致性校验
 * （入样概率区间、层归属、复检不新增抽样单位、权重分母与抽样框总量对平）。
 */
@Service
public class SamplingService {

  private final SamplingFrameRepository frameRepo;
  private final FrameStratumRepository stratumRepo;
  private final SamplingUnitRepository unitRepo;
  private final LabReportRepository reportRepo;
  private final CalibrationTotalRepository calibrationRepo;
  private final EventLedger eventLedger;

  public SamplingService(SamplingFrameRepository frameRepo, FrameStratumRepository stratumRepo,
      SamplingUnitRepository unitRepo, LabReportRepository reportRepo,
      CalibrationTotalRepository calibrationRepo, EventLedger eventLedger) {
    this.frameRepo = frameRepo;
    this.stratumRepo = stratumRepo;
    this.unitRepo = unitRepo;
    this.reportRepo = reportRepo;
    this.calibrationRepo = calibrationRepo;
    this.eventLedger = eventLedger;
  }

  /** 建立抽样框并登记全部分层名册；校验各层批次总量之和等于框总量（对平）。 */
  @Transactional
  public Map<String, Object> createFrame(String frameId, String title, long totalPopulation,
      String period, List<StratumInput> strata) {
    if (frameRepo.existsById(frameId)) {
      throw ApiException.conflict("FRAME_EXISTS", "抽样框已存在: " + frameId);
    }
    if (totalPopulation <= 0 || strata == null || strata.isEmpty()) {
      throw ApiException.badRequest("BAD_FRAME", "抽样框总量与分层名册不能为空");
    }
    long strataSum = strata.stream().mapToLong(StratumInput::population).sum();
    if (strataSum != totalPopulation) {
      throw ApiException.unprocessable("FRAME_NOT_BALANCED",
          "分层批次总量之和(" + strataSum + ")与抽样框总量(" + totalPopulation + ")不对平");
    }
    SamplingFrame frame = new SamplingFrame(frameId, title, totalPopulation, period);
    frameRepo.save(frame);
    for (StratumInput s : strata) {
      if (s.population() <= 0) {
        throw ApiException.badRequest("BAD_STRATUM", "层批次总量必须为正: " + s);
      }
      stratumRepo.save(new FrameStratum(frameId, s.region(), s.category(), s.population()));
    }
    eventLedger.append(EventType.SAMPLING_FRAME_CREATED, EventKeys.none(), Map.of(
        "frameId", frameId, "title", title, "totalPopulation", totalPopulation,
        "strata", strata.stream().map(s -> Map.of(
            "region", s.region(), "category", s.category(), "population", s.population())).toList()));
    return frameView(frame);
  }

  /** 登记抽样单位（一个批次）。风险加抽单位也必须落在名册已有的层内，不另立总体。 */
  @Transactional
  public Map<String, Object> recordUnit(UnitInput input) {
    SamplingFrame frame = requireFrame(input.frameId());
    if (unitRepo.existsById(input.unitId())) {
      throw ApiException.conflict("UNIT_EXISTS", "抽样单位已存在: " + input.unitId());
    }
    double pi = input.inclusionProb();
    if (pi <= 0.0 || pi > 1.0) {
      throw ApiException.badRequest("BAD_INCLUSION_PROB", "入样概率必须在 (0,1]: " + pi);
    }
    if (input.stratumPopulation() <= 0 || input.stratumSampled() <= 0) {
      throw ApiException.badRequest("BAD_STRATUM_SIZE", "层批次总量与层抽样数必须为正");
    }
    FrameStratum stratum = stratumRepo.findById(
            new FrameStratum.Key(input.frameId(), input.region(), input.category()))
        .orElseThrow(() -> ApiException.unprocessable("STRATUM_NOT_IN_FRAME",
            "地区×品类不在抽样框分层名册中: " + input.region() + "/" + input.category()));
    if (stratum.getPopulation() != input.stratumPopulation()) {
      throw ApiException.unprocessable("STRATUM_POP_MISMATCH",
          "层批次总量(" + input.stratumPopulation() + ")与名册登记(" + stratum.getPopulation()
              + ")不一致");
    }
    SamplingUnit unit = new SamplingUnit(input.unitId(), input.frameId(), input.region(),
        input.category(), input.productionScale(), input.stratumPopulation(),
        input.stratumSampled(), pi, input.oversample());
    unitRepo.save(unit);
    eventLedger.append(EventType.SAMPLING_UNIT_RECORDED, EventKeys.none(), Map.of(
        "frameId", unit.getFrameId(), "unitId", unit.getUnitId(),
        "region", unit.getRegion(), "category", unit.getCategory(),
        "productionScale", unit.getProductionScale(),
        "stratumPopulation", unit.getStratumPopulation(),
        "stratumSampled", unit.getStratumSampled(),
        "inclusionProb", unit.getInclusionProb(), "designWeight", 1.0 / pi,
        "oversample", unit.isOversample()));
    return unitView(unit);
  }

  /** 登记非响应：保留抽样单位在样本内（权重仍在），仅记录原因；其判定缺失由估计器反映为更宽区间。 */
  @Transactional
  public Map<String, Object> markNonResponse(String frameId, String unitId, String reason) {
    SamplingUnit unit = requireUnit(frameId, unitId);
    if (reason == null || reason.isBlank()) {
      throw ApiException.badRequest("BAD_NON_RESPONSE_REASON", "非响应原因不能为空");
    }
    unit.markNonResponse(reason);
    unitRepo.save(unit);
    eventLedger.append(EventType.SAMPLING_NON_RESPONSE, EventKeys.none(), Map.of(
        "frameId", frameId, "unitId", unitId, "reason", reason));
    return unitView(unit);
  }

  /**
   * 登记实验室报告。复检（recheck=true）挂在同一抽样单位下，不新增抽样单位、不重复计数；
   * 批次级最终判定以该单位时间序列上最后一份报告为准（复检优先）。
   */
  @Transactional
  public Map<String, Object> recordLabReport(ReportInput input) {
    SamplingUnit unit = requireUnit(input.frameId(), input.unitId());
    if (reportRepo.existsById(input.reportId())) {
      throw ApiException.conflict("REPORT_EXISTS", "实验室报告已存在: " + input.reportId());
    }
    OffsetDateTime issuedAt = input.issuedAt() != null
        ? OffsetDateTime.parse(input.issuedAt()) : OffsetDateTime.now();
    LabReport report = new LabReport(input.reportId(), input.frameId(), input.unitId(),
        input.labName(), input.recheck(), input.nonconforming(), issuedAt);
    reportRepo.save(report);
    eventLedger.append(input.recheck() ? EventType.LAB_RECHECK_RECORDED : EventType.LAB_REPORT_RECORDED,
        EventKeys.none(), Map.of(
            "frameId", input.frameId(), "unitId", input.unitId(), "reportId", input.reportId(),
            "labName", input.labName(), "recheck", input.recheck(),
            "nonconforming", input.nonconforming(), "issuedAt", issuedAt.toString()));
    Map<String, Object> view = reportView(report);
    view.put("unitFinalNonconforming", finalOutcome(unit.getUnitId()));
    view.put("distinctSamplingUnits", 1);
    return view;
  }

  /** 登记校准辅助总量（品类/地区/生产规模）。同维度取值覆盖更新。 */
  @Transactional
  public Map<String, Object> recordCalibrationTotals(String frameId, List<CalibrationInput> inputs) {
    requireFrame(frameId);
    List<Map<String, Object>> saved = new java.util.ArrayList<>();
    for (CalibrationInput input : inputs) {
      if (input.popTotal() <= 0) {
        throw ApiException.badRequest("BAD_CALIBRATION_TOTAL", "辅助总量必须为正: " + input);
      }
      calibrationRepo.findByFrameId(frameId).stream()
          .filter(c -> c.getDimension().equals(input.dimension())
              && c.getLevelValue().equals(input.levelValue()))
          .findAny()
          .ifPresent(existing -> calibrationRepo.deleteById(existing.getId()));
      CalibrationTotal total = new CalibrationTotal(
          frameId, input.dimension(), input.levelValue(), input.popTotal());
      calibrationRepo.save(total);
      saved.add(Map.of("dimension", total.getDimension(),
          "levelValue", total.getLevelValue(), "popTotal", total.getPopTotal()));
    }
    eventLedger.append(EventType.CALIBRATION_TOTAL_RECORDED, EventKeys.none(), Map.of(
        "frameId", frameId, "totals", saved));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("frameId", frameId);
    view.put("totals", saved);
    return view;
  }

  /** 归约一个抽样单位的批次级最终判定：复检优先（最后一份报告），无报告返回 null。 */
  public Boolean finalOutcome(String unitId) {
    List<LabReport> reports = reportRepo.findByUnitIdOrderByIssuedAt(unitId);
    if (reports.isEmpty()) {
      return null;
    }
    return reports.get(reports.size() - 1).isNonconforming();
  }

  public SamplingFrame requireFrame(String frameId) {
    return frameRepo.findById(frameId)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_FRAME", "抽样框不存在: " + frameId));
  }

  public SamplingUnit requireUnit(String frameId, String unitId) {
    SamplingUnit unit = unitRepo.findById(unitId)
        .orElseThrow(() -> ApiException.notFound("UNKNOWN_UNIT", "抽样单位不存在: " + unitId));
    if (!unit.getFrameId().equals(frameId)) {
      throw ApiException.unprocessable("UNIT_FRAME_MISMATCH",
          "抽样单位 " + unitId + " 不属于抽样框 " + frameId);
    }
    return unit;
  }

  // ---------- 视图 ----------

  public Map<String, Object> frameView(SamplingFrame frame) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("frameId", frame.getFrameId());
    view.put("title", frame.getTitle());
    view.put("totalPopulation", frame.getTotalPopulation());
    view.put("period", frame.getPeriod());
    view.put("createdAt", frame.getCreatedAt().toString());
    view.put("strata", stratumRepo.findByFrameId(frame.getFrameId()).stream()
        .map(s -> Map.of("region", s.getRegion(), "category", s.getCategory(),
            "population", s.getPopulation())).toList());
    return view;
  }

  public Map<String, Object> unitView(SamplingUnit unit) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", unit.getUnitId());
    view.put("frameId", unit.getFrameId());
    view.put("region", unit.getRegion());
    view.put("category", unit.getCategory());
    view.put("productionScale", unit.getProductionScale());
    view.put("stratumPopulation", unit.getStratumPopulation());
    view.put("stratumSampled", unit.getStratumSampled());
    view.put("inclusionProb", unit.getInclusionProb());
    view.put("designWeight", 1.0 / unit.getInclusionProb());
    view.put("oversample", unit.isOversample());
    view.put("responseStatus", unit.getResponseStatus());
    view.put("nonResponseReason", unit.getNonResponseReason());
    Boolean outcome = finalOutcome(unit.getUnitId());
    view.put("finalNonconforming", outcome);
    view.put("reports", reportRepo.findByUnitIdOrderByIssuedAt(unit.getUnitId()).stream()
        .map(this::reportView).toList());
    return view;
  }

  public Map<String, Object> reportView(LabReport report) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("reportId", report.getReportId());
    view.put("frameId", report.getFrameId());
    view.put("unitId", report.getUnitId());
    view.put("labName", report.getLabName());
    view.put("recheck", report.isRecheck());
    view.put("nonconforming", report.isNonconforming());
    view.put("issuedAt", report.getIssuedAt().toString());
    return view;
  }

  // ---------- 入参记录 ----------

  public record StratumInput(String region, String category, long population) {}

  public record UnitInput(String unitId, String frameId, String region, String category,
      String productionScale, long stratumPopulation, int stratumSampled,
      double inclusionProb, boolean oversample) {}

  public record ReportInput(String reportId, String frameId, String unitId, String labName,
      boolean recheck, boolean nonconforming, String issuedAt) {}

  public record CalibrationInput(String dimension, String levelValue, long popTotal) {}
}
