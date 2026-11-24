package com.foodsafety.exchange.sampling;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 抽样样例播种器（逐实体幂等）：启动时为题给核验样例补齐缺失数据，已存在的实体跳过。
 *
 * <pre>
 * 抽样框 F2026-S1，目标总体 N=400 批（市区100 + 郊县300），含乳制品/肉制品两个品类：
 *   市区 N=100：常规抽 10 + 高风险加抽 20 = 30 个抽样单位，统一入样概率 30/100=0.30
 *   郊县 N=300：抽 30 个，入样概率 30/300=0.10；其中 2 个非响应
 * 不合格（最终批次判定）：市区常规 2、市区加抽 4、郊县 3，合计 9
 * 复检：对 2 个合格单位各追加一份复检（结论一致），演示复检不新增抽样单位
 * </pre>
 *
 * 数值核验：未加权 9/58≈15.5% 会高估；设计加权（自加权、按地区）HT 总体率 12.5%；
 * 完全响应时设计权重和=400=N 对平；非响应 2 个郊县单位形成权重缺口并扩大最坏情形区间。
 */
@Configuration
public class SamplingSeedRunner {

  public static final String FRAME_ID = "F2026-S1";
  private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-20T09:00:00Z");

  @Bean
  ApplicationRunner samplingSeed(SamplingService service, SamplingFrameRepository frameRepo,
      SamplingUnitRepository unitRepo, LabReportRepository reportRepo,
      CalibrationTotalRepository calibrationRepo) {
    return args -> {
      // 1) 抽样框 + 分层名册（各层 N_h 之和 = 400）
      if (!frameRepo.existsById(FRAME_ID)) {
        List<SamplingService.StratumInput> strata = List.of(
            new SamplingService.StratumInput("市区", "乳制品", 60),
            new SamplingService.StratumInput("市区", "肉制品", 40),
            new SamplingService.StratumInput("郊县", "乳制品", 180),
            new SamplingService.StratumInput("郊县", "肉制品", 120));
        service.createFrame(FRAME_ID, "2026 年肉制品/乳制品风险监测抽样框", 400, "2026-Q4", strata);
      }

      // 2) 校准辅助总量（三个边际合计均为 400）；按 维度+取值 去重补齐
      if (calibrationRepo.findByFrameId(FRAME_ID).isEmpty()) {
        service.recordCalibrationTotals(FRAME_ID, List.of(
            new SamplingService.CalibrationInput("CATEGORY", "乳制品", 240),
            new SamplingService.CalibrationInput("CATEGORY", "肉制品", 160),
            new SamplingService.CalibrationInput("REGION", "市区", 100),
            new SamplingService.CalibrationInput("REGION", "郊县", 300),
            new SamplingService.CalibrationInput("PRODUCTION_SCALE", "SMALL", 150),
            new SamplingService.CalibrationInput("PRODUCTION_SCALE", "MEDIUM", 150),
            new SamplingService.CalibrationInput("PRODUCTION_SCALE", "LARGE", 100)));
      }

      List<String> urban = createUrban(service, unitRepo);
      List<String> rural = createRural(service, unitRepo);

      // 3) 不合格判定（最终）：市区常规 2、市区加抽 4、郊县 3，共 9
      java.util.Set<String> bad = java.util.Set.of(
          "U-URB-01", "U-URB-02",                 // 市区常规 2
          "U-URB-11", "U-URB-12", "U-URB-26", "U-URB-27", // 市区高风险加抽 4
          "U-RUR-01", "U-RUR-02", "U-RUR-03");    // 郊县 3

      int reportSeq = 0;
      for (String unitId : urban) {
        reportSeq++;
        String rid = "LR-" + reportSeq;
        if (!reportRepo.existsById(rid)) {
          service.recordLabReport(new SamplingService.ReportInput(
              rid, FRAME_ID, unitId, "市食品检验中心", false, bad.contains(unitId),
              T0.plusHours(reportSeq).toString()));
        }
      }
      for (String unitId : rural) {
        // 两个非响应单位没有实验室报告
        if (unitId.equals("U-RUR-29") || unitId.equals("U-RUR-30")) {
          continue;
        }
        reportSeq++;
        String rid = "LR-" + reportSeq;
        if (!reportRepo.existsById(rid)) {
          service.recordLabReport(new SamplingService.ReportInput(
              rid, FRAME_ID, unitId, "县综合检测站", false, bad.contains(unitId),
              T0.plusHours(reportSeq).toString()));
        }
      }

      // 4) 复检：对两个合格单位各追加一份复检（结论仍合格），不新增抽样单位
      if (!reportRepo.existsById("LR-RECHECK-1")) {
        service.recordLabReport(new SamplingService.ReportInput(
            "LR-RECHECK-1", FRAME_ID, "U-URB-05", "市食品检验中心", true, false,
            T0.plusDays(2).toString()));
      }
      if (!reportRepo.existsById("LR-RECHECK-2")) {
        service.recordLabReport(new SamplingService.ReportInput(
            "LR-RECHECK-2", FRAME_ID, "U-RUR-10", "省复检中心", true, false,
            T0.plusDays(2).toString()));
      }

      // 5) 非响应登记（郊县肉制品层两个批次，原因不同）；按当前状态补齐
      service.requireUnit(FRAME_ID, "U-RUR-29");
      service.requireUnit(FRAME_ID, "U-RUR-30");
      if (!"NON_RESPONSE".equals(unitRepo.findById("U-RUR-29").orElseThrow().getResponseStatus())) {
        service.markNonResponse(FRAME_ID, "U-RUR-29", "企业停产无法采样");
      }
      if (!"NON_RESPONSE".equals(unitRepo.findById("U-RUR-30").orElseThrow().getResponseStatus())) {
        service.markNonResponse(FRAME_ID, "U-RUR-30", "拒检");
      }
    };
  }

  /** 市区 30 个单位：常规 10 + 高风险加抽 20，pi=0.30。 */
  private List<String> createUrban(SamplingService service, SamplingUnitRepository unitRepo) {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= 30; i++) {
      String id = String.format("U-URB-%02d", i);
      ids.add(id);
      if (unitRepo.existsById(id)) {
        continue;
      }
      boolean oversample = i > 10; // 11..30 为风险加抽
      // 乳制品：01..25（N_h=60），肉制品：26..30（N_h=40）
      String category = i <= 25 ? "乳制品" : "肉制品";
      long nhPop = category.equals("乳制品") ? 60 : 40;
      int nhSampled = category.equals("乳制品") ? 25 : 5;
      service.recordUnit(new SamplingService.UnitInput(id, FRAME_ID, "市区", category,
          scaleOf(i), nhPop, nhSampled, 0.30, oversample));
    }
    return ids;
  }

  /** 郊县 30 个单位：pi=0.10；乳制品 01..18（N_h=180），肉制品 19..30（N_h=120）。 */
  private List<String> createRural(SamplingService service, SamplingUnitRepository unitRepo) {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= 30; i++) {
      String id = String.format("U-RUR-%02d", i);
      ids.add(id);
      if (unitRepo.existsById(id)) {
        continue;
      }
      String category = i <= 18 ? "乳制品" : "肉制品";
      long nhPop = category.equals("乳制品") ? 180 : 120;
      int nhSampled = category.equals("乳制品") ? 18 : 12;
      service.recordUnit(new SamplingService.UnitInput(id, FRAME_ID, "郊县", category,
          scaleOf(i), nhPop, nhSampled, 0.10, false));
    }
    return ids;
  }

  private String scaleOf(int i) {
    return switch (i % 3) {
      case 0 -> "SMALL";
      case 1 -> "MEDIUM";
      default -> "LARGE";
    };
  }
}
