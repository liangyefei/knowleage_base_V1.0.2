package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.query.DocumentGlobalSearchQueryService;
import com.hisilicon.knowledge.platform.knowledge.query.dto.DocumentNameOption;
import com.hisilicon.knowledge.platform.knowledge.query.dto.FileOptionNode;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * Mock — 替换 {@link DocumentGlobalSearchQueryService}，仅覆写 {@link #nameOptions}。
 * <p>测试环境关闭 hibernate-orm，本 mock 返回预设结果或抛超量业务异常，避免真实数据库访问。
 * 与 {@link MockKbSearchQueryService} 一致：无参构造跳过父类 CDI 注入。</p>
 */
public class MockDocumentGlobalSearchQueryService extends DocumentGlobalSearchQueryService {

    private static volatile List<DocumentNameOption> presetOptions;
    private static volatile FileOptionNode presetFileTree;
    private static volatile boolean tooMany;
    private static volatile String lastNodeType;
    private static volatile String lastNodeId;
    private static volatile String lastVersion;
    private static volatile String lastDirection;
    private static volatile String lastKeyword;

    public MockDocumentGlobalSearchQueryService() {
        super(null, null, null, null, null, null);
    }

    public static void setPresetOptions(List<DocumentNameOption> options) {
        presetOptions = options;
    }

    public static void setTooMany(boolean tooMany) {
        MockDocumentGlobalSearchQueryService.tooMany = tooMany;
    }

    /** 读取最近一次调用收到的 nodeType（验证接口层透传/trim） */
    public static String getLastNodeType() {
        return lastNodeType;
    }

    /** 读取最近一次调用收到的 nodeId（验证接口层透传/trim） */
    public static String getLastNodeId() {
        return lastNodeId;
    }

    /** 读取最近一次调用收到的 version（验证接口层透传/trim；未传时为 null） */
    public static String getLastVersion() {
        return lastVersion;
    }

    public static void setPresetFileTree(FileOptionNode root) {
        presetFileTree = root;
    }

    /** 读取最近一次 fileOptions 调用收到的 direction（验证接口层透传/trim） */
    public static String getLastDirection() {
        return lastDirection;
    }

    /** 读取最近一次 fileOptions 调用收到的 keyword（未传时为 null） */
    public static String getLastKeyword() {
        return lastKeyword;
    }

    /** 重置预设状态（每个测试用例独立） */
    public static void reset() {
        presetOptions = null;
        presetFileTree = null;
        tooMany = false;
        lastNodeType = null;
        lastNodeId = null;
        lastVersion = null;
        lastDirection = null;
        lastKeyword = null;
    }

    @Override
    public Uni<List<DocumentNameOption>> nameOptions(String nodeType, String nodeId,
                                                     String version, String locale) {
        lastNodeType = nodeType;
        lastNodeId = nodeId;
        lastVersion = version;
        if (tooMany) {
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.DOCUMENT_SEARCH_RESULT_TOO_MANY,
                    appMessages.documentSearchResultTooMany()));
        }
        return Uni.createFrom().item(presetOptions != null ? presetOptions : List.of());
    }

    @Override
    public Uni<FileOptionNode> fileOptions(String direction, String nodeType, String nodeId,
                                           String keyword, String locale) {
        lastDirection = direction;
        lastNodeType = nodeType;
        lastNodeId = nodeId;
        lastKeyword = keyword;
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        // 与真实服务同口径的入参校验（direction/nodeType），使接口层校验用例可离库运行
        String dir = direction == null ? null : direction.trim();
        if (!DocumentGlobalSearchQueryService.DIRECTION_DOC2CODE.equals(dir)
                && !DocumentGlobalSearchQueryService.DIRECTION_CODE2DOC.equals(dir)) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidDirectionValue()));
        }
        String type = nodeType == null ? null : nodeType.trim();
        if (!"KB".equals(type) && !"CATEGORY".equals(type)) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidNodeTypeValue()));
        }
        if (tooMany) {
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.DOCUMENT_SEARCH_RESULT_TOO_MANY,
                    appMessages.documentSearchResultTooMany()));
        }
        return Uni.createFrom().item(presetFileTree);
    }
}
