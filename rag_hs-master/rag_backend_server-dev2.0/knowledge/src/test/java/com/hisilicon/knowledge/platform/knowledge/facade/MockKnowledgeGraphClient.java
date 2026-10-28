package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.commons.dict.chat.DocCodeFileListResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphFragmentResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphKgResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphNodeResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphProjectsResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSearchResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.GraphSuggestResult;
import com.hisilicon.knowledge.platform.commons.dict.chat.KnowledgeGraphClient;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;

/**
 * 测试桩：{@link KnowledgeGraphClient} 端口的 knowledge 测试侧实现。
 *
 * <p>真实实现（commons-ai 的 {@code KnowledgeGraphClientImpl}）已随解环进入本模块 classpath，
 * 本桩改用 {@code @Mock}（@Alternative）消除二义性：既保留可控静态状态、不做真实 REST 调用，
 * 又满足 {@code KnowledgeGraphService} 的 CDI 注入。</p>
 *
 * <p>通过静态状态控制返回值：各方法默认空结果（projects=[]、search center=null、node=null、
 * kg count=0）；测试可 set 对应 Result 覆盖；{@code setThrowOn...()} 模拟模型失败（抛 C0001 +
 * modelServiceError 国际化，模拟 llm 实现行为）。记录最近调用参数供断言（验证 kbId/q 透传）。</p>
 */
@Mock
public class MockKnowledgeGraphClient implements KnowledgeGraphClient {

    private static volatile GraphProjectsResult projectsResult;
    private static volatile GraphSearchResult searchResult;
    private static volatile GraphNodeResult nodeResult;
    private static volatile GraphKgResult kgResult;
    private static volatile GraphSuggestResult suggestResult;
    private static volatile DocCodeFileListResult docCodeFilesResult;
    private static volatile GraphFragmentResult fragmentResult;

    private static volatile boolean throwOnProjects;
    private static volatile boolean throwOnSearch;
    private static volatile boolean throwOnNode;
    private static volatile boolean throwOnKg;
    private static volatile boolean throwOnSuggest;
    private static volatile boolean throwOnDocCodeFiles;
    private static volatile boolean throwOnFragment;

    private static volatile String lastSearchProject;
    private static volatile String lastSearchQ;
    private static volatile Integer lastSearchLimit;
    private static volatile Integer lastSearchCandidates;
    private static volatile String lastNodeProject;
    private static volatile String lastNodeName;
    private static volatile Integer lastNodeId;
    private static volatile String lastNodeFilePath;
    private static volatile Boolean lastNodeWithSource;
    private static volatile String lastKgQ;
    private static volatile String lastKgPalace;
    private static volatile String lastSuggestProject;
    private static volatile String lastSuggestPalace;
    private static volatile String lastSuggestQ;
    private static volatile Integer lastSuggestLimit;
    private static volatile String lastDocCodeFilesProject;
    private static volatile String lastDocCodeFilesType;
    private static volatile String lastFragmentPalace;
    private static volatile String lastFragmentSubject;
    private static volatile String lastFragmentPredicate;
    private static volatile String lastFragmentObject;
    private static volatile String lastLocale;

    public static void setProjectsResult(GraphProjectsResult result) {
        projectsResult = result;
        throwOnProjects = false;
    }

    public static void setSearchResult(GraphSearchResult result) {
        searchResult = result;
        throwOnSearch = false;
    }

    public static void setNodeResult(GraphNodeResult result) {
        nodeResult = result;
        throwOnNode = false;
    }

    public static void setKgResult(GraphKgResult result) {
        kgResult = result;
        throwOnKg = false;
    }

    public static void setThrowOnProjects() {
        throwOnProjects = true;
    }

    public static void setThrowOnSearch() {
        throwOnSearch = true;
    }

    public static void setThrowOnNode() {
        throwOnNode = true;
    }

    public static void setThrowOnKg() {
        throwOnKg = true;
    }

    public static void setSuggestResult(GraphSuggestResult result) {
        suggestResult = result;
        throwOnSuggest = false;
    }

    public static void setThrowOnSuggest() {
        throwOnSuggest = true;
    }

    public static void setDocCodeFilesResult(DocCodeFileListResult result) {
        docCodeFilesResult = result;
        throwOnDocCodeFiles = false;
    }

    public static void setThrowOnDocCodeFiles() {
        throwOnDocCodeFiles = true;
    }

    public static void setFragmentResult(GraphFragmentResult result) {
        fragmentResult = result;
        throwOnFragment = false;
    }

    public static void setThrowOnFragment() {
        throwOnFragment = true;
    }

    public static String getLastFragmentPalace() {
        return lastFragmentPalace;
    }

    public static String getLastFragmentSubject() {
        return lastFragmentSubject;
    }

    public static String getLastFragmentPredicate() {
        return lastFragmentPredicate;
    }

    public static String getLastFragmentObject() {
        return lastFragmentObject;
    }

    public static String getLastDocCodeFilesProject() {
        return lastDocCodeFilesProject;
    }

    public static String getLastDocCodeFilesType() {
        return lastDocCodeFilesType;
    }

    public static String getLastSuggestProject() {
        return lastSuggestProject;
    }

    public static String getLastSuggestQ() {
        return lastSuggestQ;
    }

    public static Integer getLastSuggestLimit() {
        return lastSuggestLimit;
    }

    public static String getLastSearchProject() {
        return lastSearchProject;
    }

    public static String getLastSearchQ() {
        return lastSearchQ;
    }

    public static Integer getLastSearchLimit() {
        return lastSearchLimit;
    }

    public static Integer getLastSearchCandidates() {
        return lastSearchCandidates;
    }

    public static String getLastNodeProject() {
        return lastNodeProject;
    }

    public static String getLastNodeName() {
        return lastNodeName;
    }

    public static Integer getLastNodeId() {
        return lastNodeId;
    }

    public static String getLastNodeFilePath() {
        return lastNodeFilePath;
    }

    public static Boolean getLastNodeWithSource() {
        return lastNodeWithSource;
    }

    public static String getLastKgQ() {
        return lastKgQ;
    }

    public static String getLastKgPalace() {
        return lastKgPalace;
    }

    public static String getLastSuggestPalace() {
        return lastSuggestPalace;
    }

    public static String getLastLocale() {
        return lastLocale;
    }

    public static void reset() {
        projectsResult = null;
        searchResult = null;
        nodeResult = null;
        kgResult = null;
        suggestResult = null;
        docCodeFilesResult = null;
        fragmentResult = null;
        throwOnProjects = false;
        throwOnSearch = false;
        throwOnNode = false;
        throwOnKg = false;
        throwOnSuggest = false;
        throwOnDocCodeFiles = false;
        throwOnFragment = false;
        lastSearchProject = null;
        lastSearchQ = null;
        lastSearchLimit = null;
        lastSearchCandidates = null;
        lastNodeProject = null;
        lastNodeName = null;
        lastNodeId = null;
        lastNodeFilePath = null;
        lastNodeWithSource = null;
        lastKgQ = null;
        lastKgPalace = null;
        lastSuggestProject = null;
        lastSuggestPalace = null;
        lastSuggestQ = null;
        lastSuggestLimit = null;
        lastDocCodeFilesProject = null;
        lastDocCodeFilesType = null;
        lastFragmentPalace = null;
        lastFragmentSubject = null;
        lastFragmentPredicate = null;
        lastFragmentObject = null;
        lastLocale = null;
    }

    private static <T> Uni<T> failure(String locale) {
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);
        return Uni.createFrom().failure(
                new BusinessException(ErrorCodeEnum.SERVICE_ERROR_C0001, appMessages.modelServiceError()));
    }

    @Override
    public Uni<GraphProjectsResult> projects(String locale) {
        lastLocale = locale;
        if (throwOnProjects) {
            return failure(locale);
        }
        return Uni.createFrom().item(projectsResult != null ? projectsResult
                : new GraphProjectsResult(List.of()));
    }

    @Override
    public Uni<GraphSearchResult> search(String project, String q, Integer limit, Integer candidates,
                                         String locale) {
        lastSearchProject = project;
        lastSearchQ = q;
        lastSearchLimit = limit;
        lastSearchCandidates = candidates;
        lastLocale = locale;
        if (throwOnSearch) {
            return failure(locale);
        }
        return Uni.createFrom().item(searchResult != null ? searchResult
                : new GraphSearchResult(q, project, null, List.of(), List.of(), List.of(), null, null));
    }

    @Override
    public Uni<GraphNodeResult> node(String project, String name, Integer id, String filePath, Boolean withSource,
                                     String locale) {
        lastNodeProject = project;
        lastNodeName = name;
        lastNodeId = id;
        lastNodeFilePath = filePath;
        lastNodeWithSource = withSource;
        lastLocale = locale;
        if (throwOnNode) {
            return failure(locale);
        }
        return Uni.createFrom().item(nodeResult != null ? nodeResult
                : new GraphNodeResult(null, null, null, Map.of(), Map.of(), null, null, null));
    }

    @Override
    public Uni<GraphKgResult> kg(String palace, String q, String locale) {
        lastKgPalace = palace;
        lastKgQ = q;
        lastLocale = locale;
        if (throwOnKg) {
            return failure(locale);
        }
        return Uni.createFrom().item(kgResult != null ? kgResult
                : new GraphKgResult(q, palace, "mempalace-kg", null, 0, List.of(), List.of(), null, null));
    }

    @Override
    public Uni<GraphSuggestResult> suggest(String project, String palace, String q, Integer limit, String locale) {
        lastSuggestProject = project;
        lastSuggestPalace = palace;
        lastSuggestQ = q;
        lastSuggestLimit = limit;
        lastLocale = locale;
        if (throwOnSuggest) {
            return failure(locale);
        }
        return Uni.createFrom().item(suggestResult != null ? suggestResult
                : new GraphSuggestResult(q, project, List.of(), null));
    }

    @Override
    public Uni<DocCodeFileListResult> docCodeFiles(String project, String type, String locale) {
        lastDocCodeFilesProject = project;
        lastDocCodeFilesType = type;
        lastLocale = locale;
        if (throwOnDocCodeFiles) {
            return failure(locale);
        }
        return Uni.createFrom().item(docCodeFilesResult != null ? docCodeFilesResult
                : new DocCodeFileListResult(project, "code", 0, List.of(), null));
    }

    @Override
    public Uni<GraphFragmentResult> graphFragment(String palace, String subject, String predicate, String object,
                                                  String locale) {
        lastFragmentPalace = palace;
        lastFragmentSubject = subject;
        lastFragmentPredicate = predicate;
        lastFragmentObject = object;
        lastLocale = locale;
        if (throwOnFragment) {
            return failure(locale);
        }
        return Uni.createFrom().item(fragmentResult != null ? fragmentResult
                : new GraphFragmentResult(subject, predicate, object, 0, List.of(), null, null));
    }
}
