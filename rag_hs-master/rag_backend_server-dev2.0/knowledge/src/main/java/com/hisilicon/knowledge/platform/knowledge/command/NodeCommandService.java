package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.errorcode.exception.BusinessException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.CategoryId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseEntity;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.KnowledgeBaseId;
import com.hisilicon.knowledge.platform.knowledge.domain.event.KnowledgeEvent;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.CategoryRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.DocumentOutboxRepository;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.KnowledgeBaseRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNameDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.exception.CategoryNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNameDuplicateException;
import com.hisilicon.knowledge.platform.knowledge.exception.KnowledgeBaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NavTreeItem;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NodeCreatedResult;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 统一树节点命令服务（左侧面板：新建子文件夹 / 重命名 / 删除）
 * <p>以 {@code nodeType}（KB / CATEGORY）区分操作对象，复用
 * {@link CategoryCommandService} / {@link KnowledgeBaseCommandService} 的既有校验与级联逻辑；
 * 重命名仅改名称（不触碰 description / sortOrder / inheritParent）。</p>
 */
@ApplicationScoped
public class NodeCommandService {

    private static final Logger logger = LoggerFactory.getLogger(NodeCommandService.class);

    private final KnowledgeBaseCommandService knowledgeBaseCommandService;
    private final CategoryCommandService categoryCommandService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentOutboxRepository outboxRepository;

    public NodeCommandService(KnowledgeBaseCommandService knowledgeBaseCommandService,
                              CategoryCommandService categoryCommandService,
                              KnowledgeBaseRepository knowledgeBaseRepository,
                              CategoryRepository categoryRepository,
                              DocumentOutboxRepository outboxRepository) {
        this.knowledgeBaseCommandService = knowledgeBaseCommandService;
        this.categoryCommandService = categoryCommandService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * 新建子文件夹：父节点为 KB → 根类别；父节点为 CATEGORY → 子类别
     *
     * @return 新节点信息（nodeType 恒为 CATEGORY）
     */
    @WithTransaction
    public Uni<NodeCreatedResult> create(String operatorId, CreateNodeCommand command, String locale) {
        String parentId = command.parentId().trim();
        String parentNodeType = command.parentNodeType().trim();
        String name = command.name().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        if (!NavTreeItem.TYPE_KB.equals(parentNodeType)
                && !NavTreeItem.TYPE_CATEGORY.equals(parentNodeType)) {
            logger.error("Invalid node type: parentNodeType={}", parentNodeType);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidNodeTypeValue()));
        }

        if (NavTreeItem.TYPE_KB.equals(parentNodeType)) {
            // KB 下创建根类别（parentId=null），kbId = 父 KB
            return categoryCommandService.create(operatorId, parentId,
                            new CreateCategoryCommand(name, null), locale)
                    .map(this::toResult);
        }
        // CATEGORY 下创建子类别：先查父类别获取所属 KB
        return categoryRepository.findById(CategoryId.of(parentId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Parent category not found: id={}", parentId);
                    return new CategoryNotFoundException(appMessages.categoryParentNotFound());
                })
                .flatMap(parent -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(parent.getStatus())) {
                        logger.error("Parent category already deleted: id={}", parentId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryParentNotFound()));
                    }
                    return categoryCommandService.create(operatorId, parent.getKbId(),
                                    new CreateCategoryCommand(name, parentId), locale)
                            .map(this::toResult);
                });
    }

    /**
     * 重命名节点（仅改名称，其他字段保持原值）
     */
    @WithTransaction
    public Uni<Void> rename(String operatorId, RenameNodeCommand command, String locale) {
        String nodeId = command.nodeId().trim();
        String nodeType = command.nodeType().trim();
        String newName = command.name().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        if (!NavTreeItem.TYPE_KB.equals(nodeType)
                && !NavTreeItem.TYPE_CATEGORY.equals(nodeType)) {
            logger.error("Invalid node type: nodeType={}", nodeType);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidNodeTypeValue()));
        }

        if (NavTreeItem.TYPE_KB.equals(nodeType)) {
            return renameKb(operatorId, nodeId, newName, appMessages);
        }
        return renameCategory(operatorId, nodeId, newName, appMessages);
    }

    /**
     * 删除节点：KB → 级联软删类别 + 发 KB_DELETED 事件；CATEGORY → 级联软删后代
     * <p>本方法为无事务编排层：事务边界由下游
     * {@code KnowledgeBaseCommandService.deleteKbInTx} / {@code CategoryCommandService.deleteCategoryInTx}
     * 控制，保证"文件移入回收站"发生在事务提交之后（两阶段删除）。</p>
     */
    public Uni<Void> delete(String operatorId, DeleteNodeCommand command, String locale) {
        String nodeId = command.nodeId().trim();
        String nodeType = command.nodeType().trim();
        AppMessages appMessages = LocaleUtil.getAppMessage(locale);

        if (!NavTreeItem.TYPE_KB.equals(nodeType)
                && !NavTreeItem.TYPE_CATEGORY.equals(nodeType)) {
            logger.error("Invalid node type: nodeType={}", nodeType);
            return Uni.createFrom().failure(new BusinessException(
                    ErrorCodeEnum.USER_ERROR_A0420, appMessages.invalidNodeTypeValue()));
        }

        if (NavTreeItem.TYPE_KB.equals(nodeType)) {
            return knowledgeBaseCommandService.delete(operatorId, nodeId, locale);
        }
        return categoryCommandService.delete(operatorId, nodeId, locale);
    }

    // ========== 重命名内部实现（仅改名称） ==========

    private Uni<Void> renameKb(String operatorId, String kbId, String newName, AppMessages appMessages) {
        return knowledgeBaseRepository.findById(KnowledgeBaseId.of(kbId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Knowledge base not found: id={}", kbId);
                    return new KnowledgeBaseNotFoundException(appMessages.kbNotFound());
                })
                .flatMap(entity -> {
                    if (!KnowledgeBaseEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Knowledge base already deleted, cannot rename: id={}", kbId);
                        return Uni.createFrom().failure(
                                new KnowledgeBaseNotFoundException(appMessages.kbNotFound()));
                    }
                    // 名称未变则跳过唯一性校验（避免自身冲突）
                    if (newName.equals(entity.getName())) {
                        return Uni.createFrom().item(entity);
                    }
                    return knowledgeBaseRepository.findByName(newName)
                            .onItem().ifNotNull().failWith(() -> {
                                logger.error("Knowledge base name already exists: {}", newName);
                                return new KnowledgeBaseNameDuplicateException(appMessages.kbNameAlreadyExists());
                            })
                            .map(ignored -> entity);
                })
                .flatMap(entity -> {
                    // 仅改名称，description 保持原值
                    KnowledgeBaseEntity updated = entity.update(newName, entity.getDescription());
                    return knowledgeBaseRepository.update(updated)
                            .invoke(() -> logger.info("Knowledge base renamed: id={}, newName={}, operatorId={}",
                                    kbId, newName, operatorId))
                            // 名称变化影响模型侧展示，发布编辑事件（payload 仅名称）
                            .call(() -> outboxRepository.insert(KnowledgeEvent.withPayload(
                                    KnowledgeEvent.EventType.KB_UPDATED,
                                    new KnowledgeEvent.Subject(kbId, null, null, operatorId),
                                    Map.of("kbName", newName))));
                })
                .replaceWithVoid();
    }

    private Uni<Void> renameCategory(String operatorId, String categoryId, String newName,
                                     AppMessages appMessages) {
        return categoryRepository.findById(CategoryId.of(categoryId))
                .onItem().ifNull().failWith(() -> {
                    logger.error("Category not found: id={}", categoryId);
                    return new CategoryNotFoundException(appMessages.categoryNotFound());
                })
                .flatMap(entity -> {
                    if (!CategoryEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
                        logger.error("Category already deleted, cannot rename: id={}", categoryId);
                        return Uni.createFrom().failure(
                                new CategoryNotFoundException(appMessages.categoryNotFound()));
                    }
                    // 名称未变则跳过同级唯一性校验
                    if (newName.equals(entity.getName())) {
                        return Uni.createFrom().item(entity);
                    }
                    return categoryRepository.findByName(entity.getKbId(), entity.getParentId(), newName)
                            .onItem().ifNotNull().failWith(() -> {
                                logger.error("Sibling category name already exists: kbId={}, parentId={}, name={}",
                                        entity.getKbId(), entity.getParentId(), newName);
                                return new CategoryNameDuplicateException(appMessages.categoryNameAlreadyExists());
                            })
                            .map(ignored -> entity);
                })
                .flatMap(entity -> {
                    // 仅改名称，sortOrder/inheritParent 保持原值
                    CategoryEntity updated = entity.update(newName, entity.getSortOrder(), entity.getInheritParent());
                    return categoryRepository.update(updated)
                            .invoke(() -> logger.info("Category renamed: id={}, newName={}, operatorId={}",
                                    categoryId, newName, operatorId));
                })
                .replaceWithVoid();
    }

    private NodeCreatedResult toResult(CategoryEntity entity) {
        return new NodeCreatedResult(
                entity.getId().value(), NavTreeItem.TYPE_CATEGORY, entity.getName());
    }
}
