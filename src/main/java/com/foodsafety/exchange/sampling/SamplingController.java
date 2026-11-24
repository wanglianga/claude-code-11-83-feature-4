package com.foodsafety.exchange.sampling;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抽样管理接口：抽样框（含分层名册）、抽样单位、实验室报告/复检、非响应、校准辅助总量。
 * 目标总体为抽样框内批次；同一批次复检只挂同一抽样单位，不作为新样本。
 */
@RestController
@RequestMapping("/api/sampling")
@Tag(name = "抽样设计", description = "抽样框/抽样单位/实验室报告（复检不重复计数）/非响应/校准总量")
public class SamplingController {

  private final SamplingService samplingService;
  private final SamplingFrameRepository frameRepo;
  private final SamplingUnitRepository unitRepo;
  private final LabReportRepository reportRepo;

  public SamplingController(SamplingService samplingService, SamplingFrameRepository frameRepo,
      SamplingUnitRepository unitRepo, LabReportRepository reportRepo) {
    this.samplingService = samplingService;
    this.frameRepo = frameRepo;
    this.unitRepo = unitRepo;
    this.reportRepo = reportRepo;
  }

  public record FrameRequest(String frameId, String title, Long totalPopulation, String period,
      List<SamplingService.StratumInput> strata) {}

  public record UnitRequest(String unitId, String region, String category, String productionScale,
      Long stratumPopulation, Integer stratumSampled, Double inclusionProb, Boolean oversample) {}

  public record ReportRequest(String reportId, String frameId, String unitId, String labName,
      Boolean recheck, Boolean nonconforming, String issuedAt) {}

  public record NonResponseRequest(String reason) {}

  public record CalibrationRequest(List<SamplingService.CalibrationInput> totals) {}

  @PostMapping("/frames")
  @Operation(summary = "建立抽样框", description = "登记目标总体批次总量与全部分层名册；"
      + "各层批次总量之和必须等于框总量，否则 422 不对平")
  public Map<String, Object> createFrame(@RequestBody FrameRequest request) {
    return samplingService.createFrame(request.frameId(), request.title(),
        request.totalPopulation(), request.period(), request.strata());
  }

  @GetMapping("/frames/{frameId}")
  @Operation(summary = "查询抽样框与分层名册")
  public Map<String, Object> frame(@PathVariable String frameId) {
    return samplingService.frameView(samplingService.requireFrame(frameId));
  }

  @GetMapping("/frames")
  @Operation(summary = "列出全部抽样框")
  public List<Map<String, Object>> frames() {
    return frameRepo.findAll().stream().map(samplingService::frameView).toList();
  }

  @PostMapping("/frames/{frameId}/units")
  @Operation(summary = "登记抽样单位（一个批次）", description = "保存地区/品类/生产规模、层批次总量、"
      + "层抽样数、已知入样概率与风险加抽标记；风险加抽单位仍落在名册层内，不另立总体")
  public Map<String, Object> recordUnit(@PathVariable String frameId,
      @RequestBody UnitRequest request) {
    SamplingService.UnitInput input = new SamplingService.UnitInput(
        request.unitId(), frameId, request.region(), request.category(),
        request.productionScale(), request.stratumPopulation(), request.stratumSampled(),
        request.inclusionProb(), Boolean.TRUE.equals(request.oversample()));
    return samplingService.recordUnit(input);
  }

  @GetMapping("/frames/{frameId}/units")
  @Operation(summary = "列出抽样框内全部抽样单位（含响应状态、最终判定与报告沿革）")
  public List<Map<String, Object>> units(@PathVariable String frameId) {
    samplingService.requireFrame(frameId);
    return unitRepo.findByFrameIdOrderByUnitId(frameId).stream()
        .map(samplingService::unitView).toList();
  }

  @PostMapping("/frames/{frameId}/units/{unitId}/non-response")
  @Operation(summary = "登记非响应", description = "抽样单位保留在样本内并记录非响应原因；"
      + "其判定缺失会在估计中扩大置信区间")
  public Map<String, Object> nonResponse(@PathVariable String frameId,
      @PathVariable String unitId, @RequestBody NonResponseRequest request) {
    return samplingService.markNonResponse(frameId, unitId, request.reason());
  }

  @PostMapping("/reports")
  @Operation(summary = "登记实验室报告", description = "报告链接到抽样单；recheck=true 为同批次复检，"
      + "挂在同一抽样单位下，不新增抽样单位、不重复计数，复检结论优先")
  public Map<String, Object> report(@RequestBody ReportRequest request) {
    SamplingService.ReportInput input = new SamplingService.ReportInput(
        request.reportId(), request.frameId(), request.unitId(), request.labName(),
        Boolean.TRUE.equals(request.recheck()), Boolean.TRUE.equals(request.nonconforming()),
        request.issuedAt());
    return samplingService.recordLabReport(input);
  }

  @GetMapping("/frames/{frameId}/reports")
  @Operation(summary = "列出抽样框内全部实验室报告（含复检标记）")
  public List<Map<String, Object>> reports(@PathVariable String frameId) {
    samplingService.requireFrame(frameId);
    return reportRepo.findByFrameIdOrderByIssuedAt(frameId).stream()
        .map(samplingService::reportView).toList();
  }

  @PostMapping("/frames/{frameId}/calibration-totals")
  @Operation(summary = "登记校准辅助总量", description = "按品类/地区/生产规模登记已知总体批次总量；"
      + "校准估计依赖这些总量正确及模型充分")
  public Map<String, Object> calibrationTotals(@PathVariable String frameId,
      @RequestBody CalibrationRequest request) {
    return samplingService.recordCalibrationTotals(frameId, request.totals());
  }

  @GetMapping("/frames/{frameId}/overview")
  @Operation(summary = "抽样框总览", description = "框总量、分层名册、抽样单位数、响应/非响应数、"
      + "复检报告数与去重后的抽样单位计数")
  public Map<String, Object> overview(@PathVariable String frameId) {
    SamplingFrame frame = samplingService.requireFrame(frameId);
    List<SamplingUnit> units = unitRepo.findByFrameIdOrderByUnitId(frameId);
    List<LabReport> reports = reportRepo.findByFrameIdOrderByIssuedAt(frameId);
    long responded = units.stream().filter(u -> "RESPONDED".equals(u.getResponseStatus())).count();
    long nonResponse = units.size() - responded;
    long rechecks = reports.stream().filter(LabReport::isRecheck).count();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("frame", samplingService.frameView(frame));
    view.put("samplingUnitCount", units.size());
    view.put("respondedCount", responded);
    view.put("nonResponseCount", nonResponse);
    view.put("oversampleCount", units.stream().filter(SamplingUnit::isOversample).count());
    view.put("labReportCount", reports.size());
    view.put("recheckReportCount", rechecks);
    view.put("distinctSamplingUnits", units.size());
    view.put("note", "复检报告(" + rechecks + "份)不新增抽样单位；估计单位为去重后的 "
        + units.size() + " 个批次抽样单位");
    return view;
  }
}
