package com.example.smartcs.model;

/**
 * 意图识别结果
 *
 * @param intentType 识别出的意图类型
 * @param confidence 置信度 (0.0 ~ 1.0)
 * @param reason     识别原因（LLM的推理过程）
 */
public record ChatIntent(
    IntentType intentType,
    double confidence,
    String reason
) {}
