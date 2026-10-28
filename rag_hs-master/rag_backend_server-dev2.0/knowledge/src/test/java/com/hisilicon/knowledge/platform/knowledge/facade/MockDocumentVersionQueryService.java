package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.DocumentVersionQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentVersionOption;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 手动 Mock — 替换 {@link DocumentVersionQueryService}
 * <p>通过静态字段控制版本列表返回，避免测试触库。</p>
 */
public class MockDocumentVersionQueryService extends DocumentVersionQueryService {

    /** 预置返回（null = 空列表） */
    private static volatile List<DocumentVersionOption> preset;

    /** 预置失败（非 null = 直接以该异常失败） */
    private static volatile RuntimeException failure;

    /** 最近一次调用的 documentId（断言 trim/传参） */
    private static volatile String lastDocumentId;

    public MockDocumentVersionQueryService() {
        super(null, null);
    }

    public static void reset() {
        preset = null;
        failure = null;
        lastDocumentId = null;
    }

    public static void setPreset(List<DocumentVersionOption> options) {
        preset = options;
    }

    public static void setFailure(RuntimeException e) {
        failure = e;
    }

    public static String getLastDocumentId() {
        return lastDocumentId;
    }

    @Override
    public Uni<List<DocumentVersionOption>> listVersions(String documentId, String locale) {
        lastDocumentId = documentId;
        RuntimeException err = failure;
        if (err != null) {
            return Uni.createFrom().failure(err);
        }
        List<DocumentVersionOption> options = preset;
        return Uni.createFrom().item(options == null ? List.of() : options);
    }
}
