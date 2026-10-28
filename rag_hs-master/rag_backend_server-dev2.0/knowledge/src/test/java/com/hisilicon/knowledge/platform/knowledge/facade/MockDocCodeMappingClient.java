package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingClient;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingQuery;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeMappingResult;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * 测试桩：{@link DocCodeMappingClient} 端口的 knowledge 测试侧实现。
 *
 * <p>真实实现（commons-ai 的 {@code DocCodeMappingClientImpl}）已随解环进入本模块 classpath，
 * 本桩改用 {@code @Mock}（@Alternative）消除二义性：既保留可控静态状态、不做真实 REST 调用，
 * 又满足 {@code DocCodeMappingQueryService} 的 CDI 注入。</p>
 *
 * <p>通过静态状态控制行为：默认返回 count=1 空透传结果；可设置固定结果、抛
 * {@code WebApplicationException}（模拟模型 404/400/5xx）、null 响应或任意异常。
 * 记录最近一次请求的 {@link DocCodeMappingQuery} 供断言（验证 file=版本文件路径、project=kbId）。</p>
 */
@Mock
public class MockDocCodeMappingClient implements DocCodeMappingClient {

    private static volatile DocCodeMappingResult resultToReturn;
    private static volatile Integer modelStatusToThrow;
    private static volatile boolean returnNull;
    private static volatile RuntimeException failureToThrow;
    private static volatile DocCodeMappingQuery lastQuery;
    /** 顺序结果队列（非空时逐次弹出，供降级重试等多次调用场景）；同时模拟 422 响应体 */
    private static volatile java.util.ArrayDeque<DocCodeMappingResult> resultQueue;
    /** 模拟错误响应体（配合 modelStatusToThrow 构造带 detail 的响应） */
    private static volatile String errorBody;

    public static void reset() {
        resultToReturn = null;
        modelStatusToThrow = null;
        returnNull = false;
        failureToThrow = null;
        lastQuery = null;
        resultQueue = null;
        errorBody = null;
        if (allQueries != null) {
            allQueries.clear();
        }
    }

    public static void setResult(DocCodeMappingResult result) {
        resultToReturn = result;
    }

    /** 顺序结果队列：每次调用弹出一个（耗尽后回退默认结果），供降级重试场景 */
    @SafeVarargs
    public static void setResultQueue(DocCodeMappingResult... results) {
        resultQueue = new java.util.ArrayDeque<>(java.util.List.of(results));
    }

    /** 模拟错误响应体（modelStatusToThrow 抛出的响应将携带该实体） */
    public static void setErrorBody(String body) {
        errorBody = body;
    }

    /** 模拟模型非 2xx：抛携带该 HTTP 状态码的 WebApplicationException */
    public static void setModelStatusToThrow(int status) {
        modelStatusToThrow = status;
    }

    /** 模拟模型返回 null 响应体（服务端未按契约返回） */
    public static void setReturnNull(boolean returnNull) {
        MockDocCodeMappingClient.returnNull = returnNull;
    }

    public static void setFailure(RuntimeException failure) {
        failureToThrow = failure;
    }

    /** 最近一次请求参数（验证 file/project/direction 透传） */
    public static DocCodeMappingQuery getLastQuery() {
        return lastQuery;
    }

    /** 全部请求参数（按调用顺序，验证降级重试的两次 file） */
    public static java.util.List<DocCodeMappingQuery> getAllQueries() {
        return allQueries == null ? java.util.List.of() : java.util.List.copyOf(allQueries);
    }

    private static final java.util.List<DocCodeMappingQuery> allQueries =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Override
    public Uni<DocCodeMappingResult> docCode(DocCodeMappingQuery query) {
        lastQuery = query;
        allQueries.add(query);
        if (failureToThrow != null) {
            return Uni.createFrom().failure(failureToThrow);
        }
        if (modelStatusToThrow != null) {
            return Uni.createFrom().failure(new WebApplicationException(
                    Response.status(modelStatusToThrow)
                            .entity(errorBody != null ? errorBody : "model error")
                            .build()));
        }
        if (resultQueue != null && !resultQueue.isEmpty()) {
            return Uni.createFrom().item(resultQueue.poll());
        }
        if (returnNull) {
            return Uni.createFrom().item(null);
        }
        if (resultToReturn != null) {
            return Uni.createFrom().item(resultToReturn);
        }
        return Uni.createFrom().item(new DocCodeMappingResult(
                query.file(), query.project(), query.direction(), "document", 1, null, null, 5L));
    }
}
