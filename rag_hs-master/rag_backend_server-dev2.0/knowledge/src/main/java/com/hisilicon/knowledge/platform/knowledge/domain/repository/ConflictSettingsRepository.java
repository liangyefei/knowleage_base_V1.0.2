package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.ConflictSettings;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

/**
 * 冲突检测设置仓储（领域层）。
 *
 * <p>两类记录：
 * <ul>
 *   <li>全局行：id={@link ConflictSettings#GLOBAL_ID}，kb_id=NULL，存「全局总开关」；</li>
 *   <li>KB 行：id={@code kb:{kbId}}，kb_id=xxx，存该知识库的覆盖开关。</li>
 * </ul>
 * </p>
 */
public interface ConflictSettingsRepository {

    Uni<Optional<ConflictSettings>> findGlobal();

    /** 查指定 KB 的覆盖开关；无记录返回 empty（调用方按默认值处理）。 */
    Uni<Optional<ConflictSettings>> findByKbId(String kbId);

    Uni<ConflictSettings> save(ConflictSettings entity);
}
