package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 洞察分析客户端实现（调模型端 question_classify）。
 */
@ApplicationScoped
public class InsightAnalysisClient {

    private static final Logger logger = LoggerFactory.getLogger(InsightAnalysisClient.class);

    private final ModelInsightApi api;

    public InsightAnalysisClient(@RestClient ModelInsightApi api) {
        this.api = api;
    }

    /**
     * 单条分类。失败返回 null（不抛异常，由调用方决定跳过/重试）。
     */
    public Uni<ClassifyOutcome> classify(String query, String answer) {
        return api.classify(Map.of("query", query, "answer", answer == null ? "" : answer))
                .onItem().transform(env -> {
                    if (env == null || env.code == null || env.code != 200 || env.data == null) {
                        logger.warn("Classify failed: code={}, msg={}",
                                env == null ? null : env.code, env == null ? null : env.msg);
                        return null;
                    }
                    return new ClassifyOutcome(env.data.category, env.data.reason);
                })
                .onFailure().recoverWithItem(e -> {
                    logger.warn("Classify call error: {}", e.getMessage());
                    return null;
                });
    }

    public record ClassifyOutcome(String category, String reason) {
    }
}
