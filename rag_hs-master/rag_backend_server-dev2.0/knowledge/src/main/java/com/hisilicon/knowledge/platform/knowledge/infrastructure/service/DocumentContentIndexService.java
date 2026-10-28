package com.hisilicon.knowledge.platform.knowledge.infrastructure.service;

import com.hisilicon.knowledge.platform.knowledge.domain.service.FileStorageService;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.po.DocumentContentPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.DocumentContentPanacheRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 文档内容抽取索引服务（全局搜索按内容检索的数据面）
 * <p>上传 / 文件替换 / 版本覆盖成功后异步抽取文档纯文本写入 {@code document_contents}；
 * 另由 {@code DocumentContentBackfillJob} 定时回填（存量 + 抽取滞后的文件）。</p>
 *
 * <p>抽取策略（按扩展名分类）：</p>
 * <ul>
 *   <li>SKIP（图片 png/jpg/jpeg/bmp）：无文本，写 SKIPPED 行，内容检索不参与；</li>
 *   <li>直读（md/txt/html/htm/csv + 代码/配置类 + makefile）：UTF-8 解码（上传链路统一 UTF-8）；</li>
 *   <li>具体 Parser（pdf→PDFParser / doc,xls,ppt→OfficeParser / docx,xlsx,pptx→OOXMLParser），
 *       worker 线程执行；</li>
 *   <li>其他未知类型：SKIPPED。</li>
 * </ul>
 *
 * <p>失败语义：抽取失败（加密/损坏/超时）写 FAILED 行，仅记日志，<b>不影响</b>上传/编辑/审核主流程；
 * 内容检索只命中 READY 行，标题检索不受影响。同一 filePath 的 FAILED 行不重复重试
 * （回填任务按"无等值 filePath 行"筛选），避免对永久损坏文件反复消耗 CPU；文件被替换（新路径）后自然重试。</p>
 *
 * <p>超时与截断：单文件抽取超时 {@code kb.search.extract-timeout-seconds}（默认 30s，executeBlocking
 * worker 线程执行 + ifNoItem 兜底）；抽取文本超 {@code kb.search.content-max-chars}（默认 200 万字符）截断。</p>
 *
 * <p><b>native 镜像注意</b>：不使用 {@code new Tika()} 门面——其默认构造加载 tika-mimetypes.xml
 * 媒体类型注册表（ServiceLoader + 类路径资源发现），native 下直接 NPE。此处按扩展名直达具体
 * Parser（构造轻量、零资源发现），解析失败由本类兜底写 FAILED，不会传染上传链路。
 * Tika/POI/PDFBox 用法集中于本类，native 运行期如有个别反射缺失，围绕本类收敛 reflect-config。</p>
 */
@ApplicationScoped
public class DocumentContentIndexService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentContentIndexService.class);

    /** 抽取状态：可检索 */
    public static final String STATUS_READY = "READY";
    /** 抽取状态：抽取失败（加密/损坏/超时等），不参与内容检索 */
    public static final String STATUS_FAILED = "FAILED";
    /** 抽取状态：无文本可抽（图片/未知类型），不参与内容检索 */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 无文本类型（写 SKIPPED 行） */
    private static final Set<String> SKIP_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp");
    /** 纯文本直读扩展名（UTF-8 解码，不过 Parser） */
    private static final Set<String> TEXT_DIRECT_EXTENSIONS = Set.of(
            // 文档文本（csv 为纯文本，UTF-8 直读，与 txt 同口径）
            "md", "txt", "html", "htm", "csv",
            // 代码
            "c", "cc", "cpp", "cxx", "h", "hpp", "py", "ts", "tsx", "ets", "java", "js", "mjs", "cjs",
            // 配置
            "ini", "dts", "dtsi", "dtso", "xml", "sh", "cmake");
    /** 允许无扩展名直读的文件名 */
    private static final Set<String> TEXT_DIRECT_FILENAMES = Set.of("makefile");
    /** legacy 二进制 Office 格式（OfficeParser） */
    private static final Set<String> LEGACY_OFFICE_EXTENSIONS = Set.of("doc", "xls", "ppt");
    /** OOXML 格式（OOXMLParser） */
    private static final Set<String> OOXML_EXTENSIONS = Set.of("docx", "xlsx", "pptx");

    /** 关键词片段：命中位置前后各保留的上下文字符数（组装片段用） */
    public static final int SNIPPET_CONTEXT_CHARS = 100;

    private final FileStorageService fileStorageService;
    private final DocumentContentPanacheRepository contentRepository;
    private final Vertx vertx;
    private final int maxChars;
    private final int timeoutSeconds;

    /**
     * 显式构造的具体解析器（不用 Tika 门面/TikaConfig）：{@code new Tika()} 默认构造加载
     * tika-mimetypes.xml 媒体类型注册表（ServiceLoader + 资源发现），native 下 NPE。
     * <p>仅保留 legacy Office（POI/HWPF）走 Tika Parser；
     * <b>pdf 不走 Tika PDFParser</b>——其扫描页 OCR 兜底路径
     * （AbstractPDF2XHTML.doOCROnCurrentPage，Tesseract）在 native 下 NPE，且实例/上下文两级
     * 策略注入均未被 parse 采用（2026-09-07/08 生产实测），改用 PDFBox {@link PDFTextStripper}
     * 直读文本层（见 {@link #extractPdfText}）；
     * <b>OOXML（docx/xlsx/pptx）不走 POI</b>——XmlBeans 强类型转换在 native 下必然失败
     * （XmlComplexContentImpl → DocumentDocument ClassCastException，2026-09-07 生产实测），
     * 改由 {@link #extractOoxmlText} 轻量 zip+XML 直读。</p>
     */
    private final OfficeParser legacyOfficeParser = new OfficeParser();
    private final OOXMLParser ooxmlParser = new OOXMLParser();

    public DocumentContentIndexService(FileStorageService fileStorageService,
                                       DocumentContentPanacheRepository contentRepository,
                                       Vertx vertx,
                                       @ConfigProperty(name = "kb.search.content-max-chars", defaultValue = "2000000")
                                       int maxChars,
                                       @ConfigProperty(name = "kb.search.extract-timeout-seconds", defaultValue = "30")
                                       int timeoutSeconds) {
        this.fileStorageService = fileStorageService;
        this.contentRepository = contentRepository;
        this.vertx = vertx;
        this.maxChars = maxChars;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 异步抽取（fire-and-forget）：写链路各触发点调用，不阻塞、不影响业务结果。
     * <p>成功/失败/跳过均记结构化日志（documentId/filePath/耗时/结果），便于排查。</p>
     *
     * @param documentId 文档 ID
     * @param filePath   已落定的最新版文件存储路径（= documents.file_path）
     */
    public void indexAsync(String documentId, String filePath) {
        long start = System.currentTimeMillis();
        logger.info("Content extraction started: documentId={}, filePath={}", documentId, filePath);
        reindex(documentId, filePath)
                .subscribe().with(
                        status -> logger.info("Content extraction completed: documentId={}, filePath={}, status={}, costMs={}",
                                documentId, filePath, status, System.currentTimeMillis() - start),
                        err -> logger.error("Content extraction error (FAILED already persisted, main flow not affected): documentId={}, filePath={}, costMs={}",
                                documentId, filePath, System.currentTimeMillis() - start, err));
    }

    /**
     * 同步抽取并落库（供 fire-and-forget 与回填任务复用）。
     * <p>流程：读文件 → 分类抽取 → 仓储事务 upsert document_contents。</p>
     * <p>⚠️ 会话/事务管理全部下沉到仓储方法（{@code @WithTransaction} 经仓储代理始终生效，
     * 2026-09-07 生产实测：服务层注解在 native 下因代理未生效导致 "No current Mutiny.Session"、
     * 无事务 persist 不 flush 静默丢数据）。</p>
     *
     * @return 最终写入的 contentStatus（READY/FAILED/SKIPPED）
     */
    public Uni<String> reindex(String documentId, String filePath) {
        return extract(filePath)
                .flatMap(result -> contentRepository
                        .saveExtracted(documentId, filePath, result.text(), result.status())
                        .replaceWith(result.status()));
    }

    /** upsert document_contents：按 documentId 覆盖写（同 PK），返回状态 */
    /**
     * 抽取结果：文本（SKIPPED 为 null）+ 状态
     */
    private record ExtractionResult(String text, String status) {
    }

    /**
     * 按扩展名分类抽取：读文件（存储层）→ 直读 / 具体 Parser / SKIP。
     * 抽取本身切 worker 线程（CPU 密集），统一超时保护。
     */
    private Uni<ExtractionResult> extract(String filePath) {
        String extLower = extensionOf(filePath).orElse(null);
        String fileName = fileNameOf(filePath);
        if (extLower != null && SKIP_EXTENSIONS.contains(extLower)) {
            return Uni.createFrom().item(new ExtractionResult(null, STATUS_SKIPPED));
        }
        boolean directRead = (extLower != null && TEXT_DIRECT_EXTENSIONS.contains(extLower))
                || TEXT_DIRECT_FILENAMES.contains(fileName);
        boolean binaryDoc = extLower != null
                && (LEGACY_OFFICE_EXTENSIONS.contains(extLower)
                    || OOXML_EXTENSIONS.contains(extLower)
                    || "pdf".equals(extLower));
        if (!directRead && !binaryDoc) {
            logger.info("Content extraction skipped (unrecognized text type): filePath={}", filePath);
            return Uni.createFrom().item(new ExtractionResult(null, STATUS_SKIPPED));
        }
        return fileStorageService.read(filePath)
                .flatMap(bytes -> vertx.executeBlocking(() -> directRead
                        ? extractTextDirect(bytes, maxChars)
                        : extractBinary(bytes, extLower, maxChars)))
                .ifNoItem().after(Duration.ofSeconds(timeoutSeconds))
                .failWith(new IllegalStateException("内容抽取超时: " + timeoutSeconds + "s"))
                .onFailure().invoke(err -> logger.warn("Content extraction failed (FAILED row written): filePath={}, error={}",
                        filePath, err.toString(), err))
                .onFailure().recoverWithItem(new ExtractionResult(null, STATUS_FAILED));
    }

    /**
     * 抽取结果（公开版，供版本对比等按需抽取调用方复用）：文本（SKIPPED/FAILED 为 null）+ 状态
     */
    public record ExtractedText(String text, String status) {
    }

    /**
     * 按需抽取（版本对比 version-diff 用）：对<b>任意版本</b>的文件字节做分类抽取，
     * 复用与 {@link #extract(String)} 相同的解析器与 native 约束（PDFBox/zip+XML 直读/Tika legacy）。
     * <p>与索引抽取的差异：不落 document_contents（历史版本文本不持久化，二期再做缓存表）；
     * 截断上限/超时由调用方传入（对比场景允许更大的 maxChars 与超时）。</p>
     * <p>excel（xlsx/xls）：Tika 输出按行组织——每行单元格以 ", " 连接为一行文本，
     * 行级 diff 即「哪一行哪些单元格变了」，无需额外按行分支。</p>
     * <p>必须在 IO 线程外调用方等待；抽取内部切 worker 线程（executeBlocking），带超时与失败兜底。</p>
     *
     * @param bytes    文件字节（调用方已经 FileStorageService.read 读出）
     * @param fileName 存储路径或文件名（仅用于取扩展名分类）
     * @param maxChars 抽取文本截断上限（字符）
     * @param timeoutSeconds 抽取超时（秒）
     * @return 抽取结果：READY（text 非null）/ SKIPPED（无文本类型，text=null）/ FAILED（解析失败，text=null）
     */
    public Uni<ExtractedText> extractFromBytes(byte[] bytes, String fileName,
                                               int maxChars, int timeoutSeconds) {
        String extLower = extensionOf(fileName).orElse(null);
        String baseName = fileNameOf(fileName);
        if (extLower != null && SKIP_EXTENSIONS.contains(extLower)) {
            return Uni.createFrom().item(new ExtractedText(null, STATUS_SKIPPED));
        }
        boolean directRead = (extLower != null && TEXT_DIRECT_EXTENSIONS.contains(extLower))
                || TEXT_DIRECT_FILENAMES.contains(baseName);
        boolean binaryDoc = extLower != null
                && (LEGACY_OFFICE_EXTENSIONS.contains(extLower)
                    || OOXML_EXTENSIONS.contains(extLower)
                    || "pdf".equals(extLower));
        if (!directRead && !binaryDoc) {
            logger.info("Version diff extraction skipped (unrecognized text type): fileName={}", fileName);
            return Uni.createFrom().item(new ExtractedText(null, STATUS_SKIPPED));
        }
        return vertx.executeBlocking(() -> directRead
                        ? extractTextDirect(bytes, maxChars)
                        : extractBinary(bytes, extLower, maxChars))
                .map(r -> new ExtractedText(r.text(), r.status()))
                .ifNoItem().after(Duration.ofSeconds(timeoutSeconds))
                .failWith(new IllegalStateException("版本对比抽取超时: " + timeoutSeconds + "s"))
                .onFailure().invoke(err -> logger.warn("Version diff extraction failed: fileName={}, error={}",
                        fileName, err.toString(), err))
                .onFailure().recoverWithItem(new ExtractedText(null, STATUS_FAILED));
    }

    /** 纯文本直读：UTF-8 解码 + 截断 */
    private static ExtractionResult extractTextDirect(byte[] bytes, int maxChars) {
        String text = truncate(new String(bytes, StandardCharsets.UTF_8), maxChars).orElse(null);
        return new ExtractionResult(text, STATUS_READY);
    }

    /**
     * 二进制文档抽取：按扩展名直达具体 Parser（BodyContentHandler(-1) 不限长，自行截断）。
     * 不走 AutoDetect/TikaConfig（native 下 ServiceLoader/资源发现不可用）。
     * <p>必须注入自定义 {@link EmbeddedDocumentExtractor}（忽略全部内嵌文档）：Parsers 在处理
     * 内嵌资源时经 {@code EmbeddedDocumentUtil} 构建 {@code AutoDetectParser} →
     * {@code TikaConfig.getDefaultConfig()} → 加载 mime 注册表，native 下 NPE
     * （2026-09-07 海思知识库架构设.pdf 生产日志实测）。内容检索只需正文文本。</p>
     */
    private ExtractionResult extractBinary(byte[] bytes, String ext, int maxChars) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            // pdf：PDFBox PDFTextStripper 直读文本层，不经 Tika PDFParser（native 下其 OCR 兜底路径
            // doOCROnCurrentPage NPE，且 OCR 策略注入未生效，2026-09-08 生产实测）
            if ("pdf".equals(ext)) {
                return new ExtractionResult(
                        truncate(extractPdfText(in), maxChars).orElse(null), STATUS_READY);
            }
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            context.set(EmbeddedDocumentExtractor.class, SKIP_EMBEDDED_EXTRACTOR);
            if (LEGACY_OFFICE_EXTENSIONS.contains(ext)) {
                legacyOfficeParser.parse(in, handler, metadata, context);
            } else if (OOXML_EXTENSIONS.contains(ext)) {
                ooxmlParser.parse(in, handler, metadata, context);
            } else {
                return new ExtractionResult(null, STATUS_SKIPPED);
            }
            return new ExtractionResult(truncate(handler.toString(), maxChars).orElse(null), STATUS_READY);
        } catch (java.io.IOException | org.xml.sax.SAXException
                | org.apache.tika.exception.TikaException e) {
            throw new IllegalStateException("文档解析失败: ext=" + ext + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * PDF 文本层提取：PDFBox {@link PDFTextStripper} 直读（不经 Tika），逐页拼接。
     * native 依赖（glyphlist 资源、加密处理器/字体反射）已由 META-INF/native-image 配置覆盖。
     */
    private static String extractPdfText(InputStream in) throws IOException {
        org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(in.readAllBytes());
        try {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder out = new StringBuilder();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc);
                if (pageText != null && !pageText.isBlank()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(pageText.strip());
                }
            }
            return out.toString();
        } finally {
            doc.close();
        }
    }

    /** 忽略全部内嵌文档（字体/图片/子附件）：内容检索只需正文，且规避 AutoDetect/TikaConfig */
    private static final EmbeddedDocumentExtractor SKIP_EMBEDDED_EXTRACTOR =
            new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata metadata) {
                    return false;
                }

                @Override
                public void parseEmbedded(InputStream stream, ContentHandler handler,
                                          Metadata metadata, boolean outputHtml) {
                    // no-op：不解析内嵌文档
                }
            };

    /** 截断到 maxChars（超长截断语义：仅影响尾部内容检索，主流程不受影响；G.MET.06：空值以 Optional 表达） */
    private static Optional<String> truncate(String text, int maxChars) {
        if (text == null) {
            return Optional.empty();
        }
        return Optional.of(text.length() <= maxChars ? text : text.substring(0, maxChars));
    }

    /** 路径末段文件名（反斜杠归一，与 FileTypeUtil 口径一致） */
    private static String fileNameOf(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        String normalized = filePath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    /** 小写扩展名（末个 '.' 之后），无扩展名或入参为空返回 {@link Optional#empty()}（与 FileTypeUtil 口径一致） */
    private static Optional<String> extensionOf(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        int idx = filePath.lastIndexOf('.');
        if (idx < 0 || idx == filePath.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(filePath.substring(idx + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 关键词片段组装（供查询侧复用，纯函数便于单测）：
     * 大小写不敏感定位首个关键词出现位置，前后各取 {@value #SNIPPET_CONTEXT_CHARS} 字符（越界截断）。
     *
     * @param content  全文文本（null 返回 {@link Optional#empty()}）
     * @param keyword  关键词（null/空白返回 {@link Optional#empty()}）
     * @return 片段文本；未命中返回 {@link Optional#empty()}
     */
    public static Optional<String> buildSnippet(String content, String keyword) {
        if (content == null || keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        int idx = lowerContent.indexOf(lowerKeyword);
        if (idx < 0) {
            return Optional.empty();
        }
        int from = Math.max(0, idx - SNIPPET_CONTEXT_CHARS);
        int to = Math.min(content.length(), idx + keyword.length() + SNIPPET_CONTEXT_CHARS);
        return Optional.of(content.substring(from, to));
    }
}
