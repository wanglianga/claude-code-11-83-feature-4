package com.foodsafety.exchange.sampling.estimate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 估计与诊断接口：在同一抽样框内估计批次级不合格率。
 * 三种方法（未加权 / 设计加权 / 校准）估计同一目标总体，复检不进入新样本。
 */
@RestController
@RequestMapping("/api/estimation")
@Tag(name = "抽样估计", description = "设计加权/校准的总体不合格率、分层方差贡献、重抽样与覆盖诊断")
public class EstimationController {

  private final EstimationService estimationService;

  public EstimationController(EstimationService estimationService) {
    this.estimationService = estimationService;
  }

  @GetMapping("/frames/{frameId}/estimates")
  @Operation(summary = "总体不合格率估计对照",
      description = "返回未加权朴素估计、按真实入样概率的设计加权(HT/Hařek)估计与（如有辅助总量）"
          + "校准估计；含方差、95% 区间、各层贡献与权重分母-抽样框总量对平核验")
  public Map<String, Object> estimates(@PathVariable String frameId) {
    return estimationService.compareEstimates(frameId);
  }

  @GetMapping("/frames/{frameId}/diagnostics")
  @Operation(summary = "重抽样与设计诊断",
      description = "权重极端值与有效样本量、覆盖缺口、非响应最坏情形区间、风险加抽核验、"
          + "分层 Bootstrap 经验标准误与百分位区间")
  public Map<String, Object> diagnostics(
      @PathVariable String frameId,
      @RequestParam(defaultValue = "1000") int replications,
      @RequestParam(defaultValue = "20261124") long seed) {
    return estimationService.diagnostics(frameId, replications, seed);
  }
}
