package com.hisilicon.knowledge.platform.llm.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.commons.ai.client.RestClientRequestLogger;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskResult;
import com.hisilicon.knowledge.platform.commons.dict.conflict.ConflictTaskSnapshot;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

/**
 * 冲突检测模型 REST Client API（对接 CONFLICT-DETECTION-API V1.2）。
 *
 * <p>路径前缀 {@code /kw/conflict_detection}；响应统一包裹 {@code {code, msg, data}}，
 * 本接口所有方法返回 {@link Envelope}，由 {@code RemoteConflictDetectionClient} 解包 data。</p>
 */
@Path("/kw/conflict_detection")
@RegisterRestClient(configKey = "conflict-service")
@RegisterProvider(RestClientRequestLogger.class)
public interface ModelConflictApi {

    @POST
    @Path("/tasks")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Envelope<TaskCreated>> submitTask(Map<String, Object> body);

    @POST
    @Path("/batch_tasks")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Envelope<TaskCreated>> submitBatch(Map<String, Object> body);

    @GET
    @Path("/tasks/{taskId}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Envelope<ConflictTaskSnapshot>> getTaskStatus(@PathParam("taskId") String taskId);

    @GET
    @Path("/tasks/{taskId}/results")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Envelope<ConflictTaskResult>> getTaskResults(@PathParam("taskId") String taskId);

    @POST
    @Path("/auto_switch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Envelope<AutoSwitchState>> setAutoSwitch(Map<String, Object> body);

    /** 模型端统一响应包裹。 */
    @io.quarkus.runtime.annotations.RegisterForReflection
    class Envelope<T> {
        public Integer code;
        public String msg;
        public T data;
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    class TaskCreated {
        public String task_id;
        public String status;
        public Integer file_count;
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    class AutoSwitchState {
        public String knowledge_base_name;
        public Boolean enabled;
    }
}
