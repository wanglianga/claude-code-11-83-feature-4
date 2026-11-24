package com.foodsafety.exchange.sampling.estimate;

/**
 * 进入估计的单条批次观测（一个抽样单位 = 一个批次）。
 * 复检已在服务层归约为该单位唯一的最终判定 nonconforming，不会出现重复计数。
 *
 * @param designWeight 设计权重 d = 1/pi
 * @param nonconforming 批次级最终判定；null 表示非响应（无判定）
 * @param responded 是否响应
 * @param oversample 是否风险加抽单位（仍属原抽样框层，不另立总体）
 */
public record UnitObservation(
    String unitId,
    String stratumKey,
    String region,
    String category,
    String productionScale,
    double inclusionProb,
    double designWeight,
    Boolean nonconforming,
    boolean responded,
    boolean oversample,
    long stratumPopulation,
    int stratumSampled) {
}
