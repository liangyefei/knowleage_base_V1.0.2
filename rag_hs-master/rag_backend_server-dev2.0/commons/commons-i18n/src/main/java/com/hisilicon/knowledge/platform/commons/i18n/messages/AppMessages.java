package com.hisilicon.knowledge.platform.commons.i18n.messages;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * 权限相关的消息
 */
@MessageBundle("app")
public interface AppMessages {
    @Message
    String refreshTokenExpired();

    @Message
    String illegalAccessToken();

    @Message
    String tokenPairsNotMatch();

    @Message
    String cacheTokenFailed();

    @Message
    String illegalRefreshToken();

    @Message
    String unpermittedIpAddress();

    @Message
    String accessUnauthorized();

    @Message
    String redisUnavailable();

    @Message
    String deleteCachedTokenFailed();

    @Message
    String systemResourcesUnavailable();

    @Message
    String redisAuthenticationRequired();

    @Message
    String systemInternalError();

    @Message
    String createDatasourceError();

    @Message
    String fileCannotEmpty();

    @Message
    String noParameters();

    @Message
    String incorrectDate(String param, String pattern, String received);

    @Message
    String incorrectFormat(String param, String expectedType, String received);

    @Message
    String accessWebServiceFail();

    @Message
    String operationSuccessful();

    @Message
    String decryptFailed();

    @Message
    String encryptFailed();

    @Message
    String signFailed();

    @Message
    String verifySignatureFailed();

    @Message
    String restClientFail(String name, String msg);

    String sizeWithName(String fieldName, long min, long max);

    @Message
    String methodNotAllowed();

    @Message
    String resourceNotFind();

    @Message
    String invalidParameter();

    // ========== 登录消息 ==========

    @Message
    String loginUsernamePasswordMismatch();

    @Message
    String loginAccountDisabled();

    // ========== 知识库消息 ==========

    @Message
    String kbNameAlreadyExists();

    @Message
    String kbNotFound();

    // ========== 类别消息 ==========

    @Message
    String categoryNameAlreadyExists();

    @Message
    String categoryParentNotFound();

    @Message
    String categoryNotFound();

    @Message
    String categoryMoveSelfOrDescendant();

    @Message
    String categoryDepthExceeded();

    @Message
    String unauthorizedOrTokenExpired();

    @Message
    String invalidStatusValue();

    @Message
    String fileSizeExceeded(String max);

    @Message
    String fileTypeNotSupported(String extension);

    @Message
    String fileTypeChanged(String expected, String received);

    @Message
    String versionRequired();

    @Message
    String versionAlreadyPublished(String version);

    // ========== 文档消息 ==========

    @Message
    String documentNotFound();

    @Message
    String kgNoResult();

    @Message
    String invalidDocCodeType();

    @Message
    String documentStateInvalid();

    @Message
    String documentUnderReview();

    @Message
    String documentPublishedUseVersion();

    @Message
    String documentAlreadyExists();

    @Message
    String documentArchived();

    /** 已发布文档不允许通过编辑修改版本号 */
    @Message
    String documentPublishedVersionLocked();

    /** 版本级删除生命周期守卫：仅草稿状态的文档可删除 */
    @Message
    String documentOnlyDraftCanDelete();

    /** 版本级操作：目标版本已归档（A0508 消息） */
    @Message
    String versionArchived();

    /** 版本级操作：目标版本已废弃（A0508 消息） */
    @Message
    String versionDeprecated();

    /** 批量删除（仅版本级）任一条目缺失版本号：整批拒绝 */
    @Message
    String batchDeleteVersionRequired();

    /** 版本覆盖：旧版本向量化处理中，须等待完成（A0508） */
    @Message
    String versionOverrideProcessing();

    /** 版本覆盖：旧版本向量化失败，须先重试/打回处理（A0508） */
    @Message
    String versionOverrideFailed();

    @Message
    String fileCannotRead();

    /** 文件内容查看：按 kbId + 相对路径组装的存储路径未命中常规文件 */
    @Message
    String fileNotExist();

    @Message
    String kbIdRequired();

    @Message
    String zipNotValid();

    @Message
    String rarNotValid();

    @Message
    String rarEncrypted();

    @Message
    String rarMultiVolume();

    @Message
    String zipExtractFailed();

    @Message
    String processStatusInvalid();

    @Message
    String invalidPageSizeValue();

    @Message
    String invalidNodeTypeValue();

    @Message
    String invalidSortFieldValue();

    @Message
    String invalidSearchFieldValue();

    /** 代码↔文档映射：映射方向不合法 */
    @Message
    String invalidDirectionValue();

    @Message
    String documentVersionNotFound();

    /** 版本对比：源版本与目标版本相同，无需对比 */
    @Message
    String versionDiffSameVersion();

    /** 版本对比：一侧抽取失败/无文本（图片、未知类型、加密损坏等），无法对比 */
    @Message
    String diffExtractUnavailable();

    @Message
    String documentSearchResultTooMany();

    /** 删除类别被拒：目录下存在审核中/已发布/已归档文档 */
    @Message
    String categoryDeleteBlockedByDocuments();

    /** 删除知识库被拒：知识库下存在审核中/已发布/已归档文档 */
    @Message
    String kbDeleteBlockedByDocuments();

    /** 删除知识库被拒：知识库下待级联物理删除的文件数量超过上限（防超时/网络错误） */
    @Message
    String kbDeleteCountExceeded(int max);

    /** 删除类别被拒：目录下待级联物理删除的文件数量超过上限（防超时/网络错误） */
    @Message
    String categoryDeleteCountExceeded(int max);

    // ========== 批量审核消息 ==========

    @Message
    String batchReviewScopeInvalid();

    @Message
    String batchReviewActionInvalid();

    @Message
    String batchReviewKbIdRequired();

    @Message
    String batchReviewCategoryIdsRequired();

    @Message
    String batchReviewDocumentIdsRequired();

    @Message
    String batchReviewReasonRequired();

    @Message
    String documentNotReviewing();

    @Message
    String documentKbMismatch();

    // ========== 批量提交审核消息 ==========

    @Message
    String batchSubmitReviewScopeInvalid();

    @Message
    String documentNotDraft();

    // ========== 批量删除消息 ==========

    @Message
    String batchDeleteDocumentIdsRequired();

    @Message
    String batchDeleteCountExceeded(int max);

    // ========== 元数据消息 ==========

    @Message
    String metadataKeyInvalid(String key);

    @Message
    String metadataValueInvalid(String key);

    @Message
    String metadataFieldUnrecognized(String field);

    @Message
    String invalidEntityTypeValue();

    @Message
    String metadataKeyNotInDict(String key);

    // ========== 系统字典消息 ==========

    @Message
    String dictTypeCodeAlreadyExists();

    @Message
    String dictTypeNotFound();

    @Message
    String dictItemValueAlreadyExists();

    @Message
    String dictItemNotFound();

    @Message
    String dictTypeCodeInvalid();

    @Message
    String dictPresetTypeCannotDelete();

    @Message
    String dictItemInUseCannotDelete(String value);

    @Message
    String dictTypeInUseCannotDelete(String typeCode);

    @Message
    String dictStatusInvalid();

    // ==================== 敏感词管理 ====================

    @Message
    String sensitiveTagNotFound();

    @Message
    String sensitiveTagNameDuplicate();

    @Message
    String sensitiveWordDuplicate();

    @Message
    String sensitiveWordNotFound();

    @Message
    String sensitiveTagStatusInvalid();

    @Message
    String sensitiveWordStatusInvalid();

    @Message
    String sensitiveUploadFileBlank();

    @Message
    String sensitiveFileReadFailed();

    @Message
    String sensitiveExportFailed();

    @Message
    String sensitiveExportNoData();

    @Message
    String sensitiveTemplateBuildFailed();

    @Message
    String sensitiveQuestionContainsSensitiveWord();

    @Message
    String sensitiveAnswerContainsSensitiveWord();

    @Message
    String sensitiveImportInvalid();

    @Message
    String zipEntryCountExceeded(int max);

    @Message
    String zipTotalSizeExceeded(String max);

    @Message
    String zipEntryDepthExceeded(int max);

    /**
     * zip 解压入库结果摘要（分桶计数；占位符与方法参数名一致）
     */
    String zipOutcomeSummary(int total, int success, int unsupported, int duplicate, int empty);

    @Message
    String zipSingleEntryTooLarge(String max);

    @Message
    String httpBodyTooLarge(String max);

    // ========== tus 协议错误（TusProtocolExceptionMapper，保留真实 HTTP 状态码 + 明确业务原因） ==========

    @Message
    String tusOffsetConflict(String expected, String received);

    @Message
    String tusSessionAborted();

    @Message
    String tusSessionExpired();

    @Message
    String tusSessionFailed(String reason);

    @Message
    String tusSessionProcessing();

    @Message
    String tusSessionNotFound();

    @Message
    String tusResumableInvalid();

    // ========== 对话服务消息 ==========

    @Message
    String conversationNotFound();

    @Message
    String conversationFileExists();

    @Message
    String modelServiceError();

    @Message
    String modelServiceEmptyAnswer();

    @Message
    String noMatchingKnowledgeBase();

    @Message
    String contentCannotEmpty();

    @Message
    String messageNotFound();

    @Message
    String feedbackRatingInvalid();

    @Message
    String feedbackReasonsInvalid();

    // ========== 文档编辑消息 ==========

    @Message
    String metadataValueNotInDict(String key, String value);

    @Message
    String tagsCountExceeded(int max);

    @Message
    String tagLengthExceeded(int max);

    // ========== 命中率仪表盘消息 ==========

    /** 统计时间窗 from/to 必填 */
    @Message
    String hitRateFromToRequired();

    /** from 必须早于 to */
    @Message
    String hitRateFromBeforeTo();

    /** 时间格式非法（应为 yyyy-MM-dd） */
    @Message
    String hitRateDateFormatError();

    /** 查询窗口超过上限（三个月） */
    @Message
    String hitRateRangeTooLargeMonths();

    /** 未命中记录不存在 */
    @Message
    String missRecordNotFound(String messageId);

    /** 非法状态流转 */
    @Message
    String missIllegalTransition(String current, String target);
}