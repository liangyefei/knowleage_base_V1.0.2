package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.query.VersionDiffQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.VersionDiffResult;
import io.smallrye.mutiny.Uni;

/**
 * 手动 Mock — 替换 {@link VersionDiffQueryService}
 * <p>通过静态字段控制 diff 结果返回，避免测试触库/触存储。</p>
 */
public class MockVersionDiffQueryService extends VersionDiffQueryService {

    /** 预置返回（null = 兜底空结果） */
    private static volatile VersionDiffResult preset;

    /** 预置失败（非 null = 直接以该异常失败） */
    private static volatile RuntimeException failure;

    /** 最近一次调用入参（断言 trim/传参） */
    private static volatile String lastDocumentId;
    private static volatile String lastSourceVersion;
    private static volatile String lastTargetVersion;

    public MockVersionDiffQueryService() {
        super(null, null, null, null, null, null, 1, 1);
    }

    public static void reset() {
        preset = null;
        failure = null;
        lastDocumentId = null;
        lastSourceVersion = null;
        lastTargetVersion = null;
    }

    public static void setPreset(VersionDiffResult result) {
        preset = result;
    }

    public static void setFailure(RuntimeException e) {
        failure = e;
    }

    public static String getLastDocumentId() { return lastDocumentId; }
    public static String getLastSourceVersion() { return lastSourceVersion; }
    public static String getLastTargetVersion() { return lastTargetVersion; }

    @Override
    public Uni<VersionDiffResult> diff(String documentId, String sourceVersion,
                                       String targetVersion, String locale) {
        lastDocumentId = documentId;
        lastSourceVersion = sourceVersion;
        lastTargetVersion = targetVersion;
        RuntimeException err = failure;
        if (err != null) {
            return Uni.createFrom().failure(err);
        }
        VersionDiffResult result = preset;
        return Uni.createFrom().item(result);
    }
}
