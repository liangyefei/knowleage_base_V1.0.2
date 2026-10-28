package com.hisilicon.knowledge.platform.llm.domain.service;

import io.smallrye.mutiny.Uni;

/**
 * 对话文件存储服务接口（领域服务）
 * <p>llm 模块自有存储抽象（不依赖 knowledge 模块），当前本地文件系统实现，
 * 后续可切换华为云 SFS。</p>
 */
public interface ChatFileStorageService {

    /**
     * 存储文件
     *
     * @param relativePath 存储相对路径（含目标文件名）
     * @param content      文件内容
     * @return 实际存储路径
     */
    Uni<String> store(String relativePath, byte[] content);

    /**
     * 将文件移入回收目录（删除会话时同步清理文件）
     *
     * @param path 存储相对路径
     * @return 完成信号（文件不存在视为成功——幂等）
     */
    Uni<Void> moveToTrash(String path);
}
