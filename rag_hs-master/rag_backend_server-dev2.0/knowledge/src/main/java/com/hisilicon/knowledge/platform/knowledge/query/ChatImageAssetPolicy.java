package com.hisilicon.knowledge.platform.knowledge.query;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 对话图片兜底放行目录策略（{@code kb.chat.image-fallback-dirs}）
 *
 * <p><b>背景</b>：模型端解析 PDF 时把抽出的图片写进自己的产物目录（如
 * {@code /kb-files/mineru_output/{任务hash}/images/…}），这些文件<b>不是平台文档树文件</b>——
 * documents 表里没有行，文档图片域签名（绑定 documentId + 文档目录子树）签不出来，图片在
 * 前端必然 404。本策略给出「哪些目录下的图片可以走对话兜底端点签发」的<b>唯一判定口径</b>，
 * 由 {@link ChatImageAssetUrlRewriterImpl}（签发侧）与
 * {@link com.hisilicon.knowledge.platform.knowledge.facade.ChatImageAssetResource}（服务侧）
 * 共用，两侧判定恒等。</p>
 *
 * <p><b>为什么是白名单且必须配置化</b>：
 * <ul>
 *   <li>没有文档行 = 没有可绑定的授权范围。若按"文件存在就签发"实现，可签发空间会变成整个
 *       存储卷（{@code .trash/} 里的软删文件、其它知识库的文档路径、模型端将来新增的任何目录
 *       全部可读）。白名单把空间收敛到一个自己人写的目录，是这套方案能成立的前提。</li>
 *   <li>目录名是<b>模型端的内部命名</b>，平台无权假设。写死常量后模型端改名，结果是图片静默
 *       不显示且无人察觉；配置化 + 启动 INFO 打印放行目录，让这个耦合看得见。</li>
 * </ul></p>
 *
 * <p><b>匹配语义</b>：按<b>段级</b>前缀匹配（要求 {@code dir + "/"} 且其后还有内容），
 * 不做裸字符串前缀——否则 {@code mineru_output} 会连带放行 {@code mineru_output_old/}、
 * {@code mineru_output.bak/} 等同级兄弟目录。传入路径必须是已由
 * {@link MarkdownImageRewriter#normalizeRelative} 规范化（已消解 {@code .}/{@code ..}）的存储相对路径。</p>
 *
 * <p><b>本策略不负责</b>（各司其职，不要揉进来）：软链逃逸与真实路径包含关系由存储层
 * {@code FileStorageService#existingRegularFilesWithin} 判定；扩展名白名单由
 * {@link MarkdownImageRewriter#IMAGE_EXTENSIONS} 判定；签名由 {@link AssetUrlSigner} 判定。</p>
 *
 * <p><b>生命周期契约</b>：该目录的属主是模型服务——它按归档/废弃事件清理对应图片，此时文档本身
 * 也不可见（产品已确认口径一致）。因此本兜底<b>只保证"文件当前存在则可展示"</b>，不承担生命周期：
 * 文件被清掉后，历史问答里的图片会失效，这是预期行为，不是缺陷。</p>
 */
@ApplicationScoped
@Startup
public class ChatImageAssetPolicy {

    private static final Logger logger = LoggerFactory.getLogger(ChatImageAssetPolicy.class);

    /** 多目录分隔符（配置值形如 {@code mineru_output} 或 {@code dir-a,dir-b}） */
    private static final String SEPARATOR = ",";

    /** 规范化后的允许目录（无首尾斜杠、去重、保持配置顺序） */
    private final List<String> allowedDirs;

    /**
     * CDI 注入构造：配置缺失/空白 = 兜底关闭（对话图片只走 documents 绑定链路，行为与本次改动前一致）。
     */
    @Inject
    public ChatImageAssetPolicy(
            @ConfigProperty(name = "kb.chat.image-fallback-dirs") Optional<String> rawDirs) {
        this.allowedDirs = normalize(rawDirs == null ? null : rawDirs.orElse(""));
    }

    /** 直构便捷入口（单元测试用，绕开 CDI 配置注入） */
    public ChatImageAssetPolicy(String rawDirs) {
        this.allowedDirs = normalize(rawDirs);
    }

    /**
     * 启动可见性：把"当前放行哪些目录 / 兜底是否关闭"打到启动日志。
     * <p>这个功能失效是<b>静默</b>的（图片不显示，页面无提示），所以放行集合必须在启动时可见，
     * 否则模型端改目录名只能靠猜。</p>
     */
    @PostConstruct
    void logAllowedDirs() {
        if (allowedDirs.isEmpty()) {
            logger.warn("kb.chat.image-fallback-dirs is not configured: chat image fallback is disabled, images produced by the model side"
                    + "(no row in the documents table, e.g. /kb-files/mineru_output/…) stay as-is and are not shown in the frontend");
            return;
        }
        logger.info("Chat image fallback allowed directories: {} (only images under these directories whose real path is still inside can be signed as chat asset URLs; "
                + "directory names are decided by the model side, so renaming them requires syncing this configuration, otherwise the images are silently not shown)", allowedDirs);
    }

    /** 规范化后的放行目录（无首尾斜杠；空 = 兜底关闭） */
    public List<String> allowedDirs() {
        return allowedDirs;
    }

    /** 兜底是否启用（放行目录非空） */
    public boolean enabled() {
        return !allowedDirs.isEmpty();
    }

    /**
     * 规范化存储相对路径命中的放行目录（段级前缀匹配）。
     *
     * @param normalizedPath 已规范化的存储相对路径（{@code /kb-files/} 剥离后的值）
     * @return 命中的目录段（如 {@code mineru_output}）；未命中返回 null
     */
    public String matchAllowedDir(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return null;
        }
        for (String dir : allowedDirs) {
            // 段级匹配：必须是 dir + "/" + 非空内容。
            // 裸 startsWith(dir) 会连带放行 mineru_output_old/、mineru_output.bak/ 等兄弟目录。
            if (normalizedPath.length() > dir.length() + 1
                    && normalizedPath.startsWith(dir)
                    && normalizedPath.charAt(dir.length()) == '/') {
                return dir;
            }
        }
        return null;
    }

    /**
     * 配置解析与规范化：逗号分隔 → 去空白 → 去首尾斜杠 → 丢掉空项与含 {@code ..} 的非法项 →
     * 去重（保持顺序）。
     * <p>含 {@code ..} 的配置项直接丢弃并 WARN：配置是可信输入，但一个手滑的 {@code ../}
     * 会把放行范围抬到存储根之上，宁可拒绝也不能让它静默生效。</p>
     */
    private static List<String> normalize(String rawDirs) {
        if (rawDirs == null || rawDirs.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawDirs.split(SEPARATOR)) {
            String dir = raw == null ? "" : raw.trim().replace('\\', '/');
            while (dir.startsWith("/")) {
                dir = dir.substring(1);
            }
            while (dir.endsWith("/")) {
                dir = dir.substring(0, dir.length() - 1);
            }
            if (dir.isEmpty()) {
                continue;
            }
            if (!MarkdownImageRewriter.normalizeRelative(dir).map(dir::equals).orElse(false)) {
                // 含空段/./.. 之类的非法形态：normalizeRelative 会消解或拒绝（越界返回空 Optional），
                // 这里只接受"规范化后等于自身"的目录
                logger.warn("Chat image fallback directory configuration is invalid (contains . / .. / empty segment), ignored: dir={}", raw);
                continue;
            }
            normalized.add(dir);
        }
        return List.copyOf(new ArrayList<>(normalized));
    }
}
