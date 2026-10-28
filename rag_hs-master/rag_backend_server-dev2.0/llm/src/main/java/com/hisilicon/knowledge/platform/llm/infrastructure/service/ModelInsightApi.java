package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

/**
 * 模型端问答分类接口（/kw/chat/question_classify）。
 */
@Path("/kw/chat")
@RegisterRestClient(configKey = "model-service")
public interface ModelInsightApi {

    @POST
    @Path("/question_classify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Envelope<ClassifyResult>> classify(Map<String, Object> body);

    class Envelope<T> {
        public Integer code;
        public String msg;
        public T data;
    }

    class ClassifyResult {
        public String category;
        public String reason;
    }
}
