package com.foodsafety.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽样设计加权估计集成测试：真实 PostgreSQL + 随机端口，经 API 复刻题给样例。
 *
 * <pre>
 * 目标总体 N=400：市区 100（常规抽10 + 高风险加抽20，pi=0.30）、郊县 300（抽30，pi=0.10）。
 * 不合格：市区常规2、市区加抽4、郊县3，共9；郊县2个非响应；另有2份复检。
 * </pre>
 */
class SamplingEstimationTest extends ExchangeIntegrationTest {

  private static final String FRAME = "SF-TEST";
  private static final double EPS = 1e-9;

  /** 建立题给样例框并登记单位、报告、复检与非响应。 */
  private void buildWorkedExample() {
    // 抽样框：两层（地区），N=100+300=400
    postJson("/api/sampling/frames", """
        {"frameId":"%s","title":"题给样例","totalPopulation":400,"period":"2026-Q4",
         "strata":[{"region":"市区","category":"通用","population":100},
                   {"region":"郊县","category":"通用","population":300}]}""".formatted(FRAME));

    // 校准辅助总量（地区/品类/生产规模三边际，合计均为400）
    postJson("/api/sampling/frames/" + FRAME + "/calibration-totals", """
        {"totals":[
          {"dimension":"REGION","levelValue":"市区","popTotal":100},
          {"dimension":"REGION","levelValue":"郊县","popTotal":300},
          {"dimension":"CATEGORY","levelValue":"通用","popTotal":400},
          {"dimension":"PRODUCTION_SCALE","levelValue":"LARGE","popTotal":400}]}""");

    java.util.Set<String> bad = java.util.Set.of(
        "T-URB-01", "T-URB-02",                                  // 市区常规 2
        "T-URB-11", "T-URB-12", "T-URB-26", "T-URB-27",          // 市区高风险加抽 4
        "T-RUR-01", "T-RUR-02", "T-RUR-03");                     // 郊县 3

    // 市区 30 个单位：1..10 常规，11..30 风险加抽；pi=0.30
    for (int i = 1; i <= 30; i++) {
      String id = String.format("T-URB-%02d", i);
      boolean over = i > 10;
      postJson("/api/sampling/frames/" + FRAME + "/units",
          unitJson(id, "市区", 100, 30, 0.30, over));
      postJson("/api/sampling/reports",
          reportJson("LR-" + id, id, false, bad.contains(id)));
    }
    // 郊县 30 个单位：pi=0.10；29、30 非响应（不登记报告）
    for (int i = 1; i <= 30; i++) {
      String id = String.format("T-RUR-%02d", i);
      postJson("/api/sampling/frames/" + FRAME + "/units",
          unitJson(id, "郊县", 300, 30, 0.10, false));
      if (i == 29 || i == 30) {
        postJson("/api/sampling/frames/" + FRAME + "/units/" + id + "/non-response",
            "{\"reason\":\"拒检\"}");
      } else {
        postJson("/api/sampling/reports",
            reportJson("LR-" + id, id, false, bad.contains(id)));
      }
    }
    // 两份复检（合格），挂在已有单位下
    postJson("/api/sampling/reports", reportJson("LR-T-RC-1", "T-URB-05", true, false));
    postJson("/api/sampling/reports", reportJson("LR-T-RC-2", "T-RUR-10", true, false));
  }

  private String unitJson(String id, String region, long nhPop, int nhSampled,
      double pi, boolean oversample) {
    return "{\"unitId\":\"" + id + "\",\"region\":\"" + region + "\",\"category\":\"通用\","
        + "\"productionScale\":\"LARGE\",\"stratumPopulation\":" + nhPop
        + ",\"stratumSampled\":" + nhSampled + ",\"inclusionProb\":" + pi
        + ",\"oversample\":" + oversample + "}";
  }

  private String reportJson(String rid, String uid, boolean recheck, boolean bad) {
    return "{\"reportId\":\"" + rid + "\",\"frameId\":\"" + FRAME + "\",\"unitId\":\"" + uid
        + "\",\"labName\":\"L\",\"recheck\":" + recheck + ",\"nonconforming\":" + bad + "}";
  }

  @Test
  void 未加权高估而设计加权还原告真值_复检不重复计数() {
    buildWorkedExample();
    JsonNode est = getJson("/api/estimation/frames/" + FRAME + "/estimates");

    assertEquals(400, est.path("targetPopulation").asLong());
    assertEquals(60, est.path("distinctSamplingUnits").asInt());
    assertEquals(2, est.path("recheckReportsExcluded").asInt());

    JsonNode naive = est.path("naive");
    // 未加权：9/58 ≈ 0.1552，显著高估
    assertEquals(9.0 / 58, naive.path("pointEstimate").asDouble(), EPS);
    assertEquals(58, naive.path("respondingUnits").asInt());

    JsonNode design = est.path("designWeighted");
    // HT：加权不合格 50 / N=400 = 0.125；Hařek：50/380≈0.13158
    assertEquals(0.125, design.path("diagnostics").path("htEstimate").asDouble(), EPS);
    assertEquals(50.0 / 380.0, design.path("pointEstimate").asDouble(), EPS);
    assertEquals(9, design.path("nonconformingCount").asInt());
    // 权重分母（响应者权重和）= 市区30×10/3 + 郊县28×10 = 100 + 280 = 380
    assertEquals(380.0, design.path("weightDenominator").asDouble(), EPS);
    // 未加权估计高于设计加权，体现风险加抽导致的高估
    assertTrue(naive.path("pointEstimate").asDouble() > design.path("pointEstimate").asDouble());
  }

  @Test
  void 风险加抽不改变目标总体且全部落在名册层内() {
    buildWorkedExample();
    JsonNode diag = getJson("/api/estimation/frames/" + FRAME + "/diagnostics?replications=500");
    JsonNode over = diag.path("oversampleCheck");
    assertEquals(20, over.path("oversampleUnitCount").asInt());
    assertTrue(over.path("allOversamplesWithinFrame").asBoolean());
    assertEquals(400, over.path("targetPopulationUnchanged").asLong());
  }

  @Test
  void 非响应扩大区间并形成权重缺口() {
    buildWorkedExample();
    JsonNode est = getJson("/api/estimation/frames/" + FRAME + "/estimates");
    JsonNode guard = est.path("reconciliationGuard");
    assertEquals(2, guard.path("nonResponseUnits").asInt());
    assertEquals(380.0, guard.path("respondentDesignWeightSum").asDouble(), EPS);
    assertFalse(guard.path("balanced").asBoolean(), "存在非响应时不应判定为对平");

    JsonNode nr = getJson("/api/estimation/frames/" + FRAME
        + "/diagnostics").path("nonResponseImpact");
    // 非响应权重 = 2×10 = 20，占响应+非响应总权重 400 的 5%
    assertEquals(20.0, nr.path("nonResponseWeight").asDouble(), EPS);
    assertEquals(0.05, nr.path("nonResponseWeightShare").asDouble(), EPS);
    JsonNode env = nr.path("worstCaseEnvelope");
    assertEquals(0.125, env.path("lowerAllConforming").asDouble(), EPS);
    assertEquals(0.175, env.path("upperAllNonconforming").asDouble(), EPS);
    assertEquals(0.05, env.path("envelopeWidth").asDouble(), EPS);
  }

  @Test
  void 完全响应时权重分母与抽样框总量对平() {
    postJson("/api/sampling/frames", """
        {"frameId":"FULL","title":"全响应","totalPopulation":100,
         "strata":[{"region":"市区","category":"通用","population":100}]}""");
    for (int i = 1; i <= 10; i++) {
      String id = String.format("FU-%02d", i);
      postJson("/api/sampling/frames/FULL/units",
          "{\"unitId\":\"" + id + "\",\"region\":\"市区\",\"category\":\"通用\","
              + "\"productionScale\":\"LARGE\",\"stratumPopulation\":100,\"stratumSampled\":10,"
              + "\"inclusionProb\":0.1,\"oversample\":false}");
      postJson("/api/sampling/reports",
          "{\"reportId\":\"FR-" + i + "\",\"frameId\":\"FULL\",\"unitId\":\"" + id
              + "\",\"labName\":\"L\",\"recheck\":false,\"nonconforming\":" + (i <= 2) + "}");
    }
    JsonNode est = getJson("/api/estimation/frames/FULL/estimates");
    JsonNode guard = est.path("reconciliationGuard");
    assertTrue(guard.path("balanced").asBoolean());
    assertEquals(100.0, guard.path("respondentDesignWeightSum").asDouble(), EPS);
    assertEquals(100.0, est.path("designWeighted").path("weightDenominator").asDouble(), EPS);
    // 自加权全响应：HT=Hařek=朴素=0.2
    assertEquals(0.2, est.path("designWeighted").path("pointEstimate").asDouble(), EPS);
    assertEquals(0.2, est.path("designWeighted").path("diagnostics").path("htEstimate").asDouble(), EPS);
  }

  @Test
  void 复检不新增抽样单位且复检结论优先() {
    buildWorkedExample();
    JsonNode before = getJson("/api/sampling/frames/" + FRAME + "/overview");
    assertEquals(60, before.path("samplingUnitCount").asInt());
    assertEquals(2, before.path("recheckReportCount").asInt());

    // 对一个初检合格单位追加“不合格”复检
    JsonNode resp = postJson("/api/sampling/reports",
        reportJson("LR-T-RC-FLIP", "T-RUR-11", true, true));
    assertEquals(1, resp.path("distinctSamplingUnits").asInt());
    assertTrue(resp.path("unitFinalNonconforming").asBoolean(), "复检结论应优先");

    JsonNode after = getJson("/api/sampling/frames/" + FRAME + "/overview");
    assertEquals(60, after.path("samplingUnitCount").asInt(), "复检不得新增抽样单位");
    assertEquals(3, after.path("recheckReportCount").asInt());
  }

  @Test
  void 校准权重合计等于辅助总量且收敛() {
    buildWorkedExample();
    JsonNode cal = getJson("/api/estimation/frames/" + FRAME + "/estimates").path("calibrated");
    assertEquals("CONVERGED", cal.path("diagnostics").path("calibrationConvergence").asText());
    // 校准后权重和被调整到辅助总量合计 N=400
    assertEquals(400.0, cal.path("weightDenominator").asDouble(), 1e-6);
    assertTrue(cal.path("diagnostics").path("gWeights").size() > 0);
  }

  @Test
  void 分层方差贡献齐全且Bootstrap标准误与解析接近() {
    buildWorkedExample();
    JsonNode est = getJson("/api/estimation/frames/" + FRAME + "/estimates");
    JsonNode contributions = est.path("designWeighted").path("stratumContributions");
    assertEquals(2, contributions.size(), "市区/郊县两个层各一条贡献");
    double analyticSe = est.path("designWeighted").path("standardError").asDouble();
    assertTrue(analyticSe > 0);

    JsonNode boot = getJson("/api/estimation/frames/" + FRAME
        + "/diagnostics?replications=2000&seed=42").path("stratifiedBootstrap");
    double bootSe = boot.path("bootstrapStandardError").asDouble();
    // Bootstrap 经验标准误与解析标准误相对差异在 25% 以内
    assertEquals(analyticSe, bootSe, analyticSe * 0.25);
  }

  @Test
  void 权重极端值与覆盖缺口被识别() {
    buildWorkedExample();
    JsonNode diag = getJson("/api/estimation/frames/" + FRAME + "/diagnostics");
    JsonNode wd = diag.path("weightDiagnostics");
    // 郊县 pi=0.10 -> 权重 10 为最大；市区权重 10/3≈3.333 最小
    assertEquals(10.0, wd.path("maxWeight").asDouble(), EPS);
    assertEquals(10.0 / 3.0, wd.path("minWeight").asDouble(), EPS);
    assertTrue(wd.path("kishEffectiveSampleSize").asDouble() < 58, "有效样本量应低于名义样本量");
    assertTrue(wd.path("designEffectFromWeights").asDouble() > 1.0);
    // 样例两层均有抽样，无覆盖缺口
    assertEquals(0, diag.path("coverageGaps").path("zeroSampleStrata").size());
  }

  @Test
  void 零抽样层被判为覆盖缺口() {
    postJson("/api/sampling/frames", """
        {"frameId":"GAP","title":"缺口","totalPopulation":200,
         "strata":[{"region":"市区","category":"通用","population":80},
                   {"region":"郊县","category":"通用","population":120}]}""");
    postJson("/api/sampling/frames/GAP/units",
        "{\"unitId\":\"G1\",\"region\":\"市区\",\"category\":\"通用\",\"productionScale\":\"LARGE\","
            + "\"stratumPopulation\":80,\"stratumSampled\":1,\"inclusionProb\":0.5,\"oversample\":false}");
    postJson("/api/sampling/reports",
        "{\"reportId\":\"GR1\",\"frameId\":\"GAP\",\"unitId\":\"G1\",\"labName\":\"L\","
            + "\"recheck\":false,\"nonconforming\":true}");
    JsonNode gaps = getJson("/api/estimation/frames/GAP/diagnostics").path("coverageGaps");
    assertEquals(1, gaps.path("zeroSampleStrata").size());
    assertEquals(120, gaps.path("uncoveredPopulation").asLong());
    assertEquals(0.4, gaps.path("coverageRate").asDouble(), EPS);
  }

  @Test
  void 抽样框不对平与入样概率越界被拒绝() {
    // 分层总量 100 ≠ 框总量 99 -> 422
    org.springframework.http.ResponseEntity<JsonNode> r1 = authed().postForEntity(
        "/api/sampling/frames",
        new org.springframework.http.HttpEntity<>("""
            {"frameId":"BAD","title":"x","totalPopulation":99,
             "strata":[{"region":"市区","category":"通用","population":100}]}""",
            jsonHeaders()), JsonNode.class);
    assertEquals(422, r1.getStatusCode().value());

    postJson("/api/sampling/frames", """
        {"frameId":"OK","title":"x","totalPopulation":100,
         "strata":[{"region":"市区","category":"通用","population":100}]}""");
    // pi=1.5 越界 -> 400
    org.springframework.http.ResponseEntity<JsonNode> r2 = authed().postForEntity(
        "/api/sampling/frames/OK/units",
        new org.springframework.http.HttpEntity<>(
            "{\"unitId\":\"X\",\"region\":\"市区\",\"category\":\"通用\",\"productionScale\":\"LARGE\","
                + "\"stratumPopulation\":100,\"stratumSampled\":10,\"inclusionProb\":1.5,"
                + "\"oversample\":false}", jsonHeaders()), JsonNode.class);
    assertEquals(400, r2.getStatusCode().value());
    // 单位层不在名册 -> 422
    org.springframework.http.ResponseEntity<JsonNode> r3 = authed().postForEntity(
        "/api/sampling/frames/OK/units",
        new org.springframework.http.HttpEntity<>(
            "{\"unitId\":\"Y\",\"region\":\"外省\",\"category\":\"通用\",\"productionScale\":\"LARGE\","
                + "\"stratumPopulation\":100,\"stratumSampled\":10,\"inclusionProb\":0.1,"
                + "\"oversample\":false}", jsonHeaders()), JsonNode.class);
    assertEquals(422, r3.getStatusCode().value());
  }

  private org.springframework.http.HttpHeaders jsonHeaders() {
    org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
    h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return h;
  }
}
