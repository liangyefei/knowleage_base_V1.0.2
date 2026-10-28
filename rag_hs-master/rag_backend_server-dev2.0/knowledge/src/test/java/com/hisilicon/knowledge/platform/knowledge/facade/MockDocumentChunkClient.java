package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocChunk;
import com.hisilicon.knowledge.platform.commons.dict.chat.DocumentChunkClient;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * 测试桩：{@link DocumentChunkClient} 端口的 knowledge 测试侧实现。
 *
 * <p>真实实现（commons-ai 的 {@code DocumentChunkClientImpl}）已随解环进入本模块 classpath，
 * 本桩改用 {@code @Mock}（@Alternative）消除二义性：既保留可控静态状态、不做真实 REST 调用，
 * 又满足 {@code DocumentChunkQueryService} 的 CDI 注入。</p>
 *
 * <p>通过静态状态控制返回值：默认空列表；测试可 {@link #setChunks(List)} 覆盖；
 * {@link #setThrowOnGet()} 模拟模型失败（抛 C0001 + modelServiceError 国际化，模拟 llm 实现行为）。
 * 记录最近调用的 collectionName/fullFilename/locale 供断言（验证 /kb-files/ 前缀拼接与 kbId 透传）。</p>
 */
@Mock
public class MockDocumentChunkClient implements DocumentChunkClient {

    /** getDocByFilename 返回的切片列表 */
    private static volatile List<DocChunk> chunksToReturn = List.of();
    /** 是否抛失败（模拟模型非200/异常） */
    private static volatile boolean throwOnGet;
    /** 最近一次调用的 collectionName */
    private static volatile String lastCollectionName;
    /** 最近一次调用的 fullFilename */
    private static volatile String lastFullFilename;
    /** 最近一次调用的 locale */
    private static volatile String lastLocale;

    public static void setChunks(List<DocChunk> chunks) {
        chunksToReturn = chunks;
        throwOnGet = false;
    }

    public static void setThrowOnGet() {
        throwOnGet = true;
    }

    public static String getLastCollectionName() {
        return lastCollectionName;
    }

    public static String getLastFullFilename() {
        return lastFullFilename;
    }

    public static String getLastLocale() {
        return lastLocale;
    }

    public static void reset() {
        chunksToReturn = List.of();
        throwOnGet = false;
        lastCollectionName = null;
        lastFullFilename = null;
        lastLocale = null;
    }

    @Override
    public Uni<List<DocChunk>> getDocByFilename(String collectionName, String fullFilename, String locale) {
        lastCollectionName = collectionName;
        lastFullFilename = fullFilename;
        lastLocale = locale;
        if (throwOnGet) {
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            return Uni.createFrom().failure(
                    new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, appMessages.modelServiceError()));
        }
        return Uni.createFrom().item(chunksToReturn != null ? chunksToReturn : List.of());
    }
}
