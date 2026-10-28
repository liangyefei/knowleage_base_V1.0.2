package com.hisilicon.knowledge.platform.llm.facade;

import com.hisilicon.knowledge.platform.commons.errorcode.exception.IllegalParameterException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import com.hisilicon.knowledge.platform.commons.web.security.Authenticated;
import com.hisilicon.knowledge.platform.llm.query.HitRateQueryService;
import com.hisilicon.knowledge.platform.llm.query.dto.ChipHitRateItem;
import com.hisilicon.knowledge.platform.llm.query.dto.HitRateSummaryItem;
import com.hisilicon.knowledge.platform.llm.query.dto.MissItem;
import com.hisilicon.knowledge.platform.llm.query.dto.TrendPointItem;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
/**
 * 命中率仪表盘 REST 接口（管理侧统计页）
 * <p>数据源 qa_kb_stats（multi_knowledge_base_chat 收尾事件 kb_stats 落库）。
 * 口径：总查询数=范围内全部行（含闲聊，仅展示）；命中率分母=有效查询（排除 no_hit_reason='chitchat'
 * 的闲聊行，闲聊未触发检索）；芯片维度=知识库维度（分母同样排除闲聊）；
 * 未命中列表=kb_hit 非 true 且排除闲聊——与分母同源，<b>列表为空 ⟺ 命中率 100%</b>。</p>
 * <p>时间参数格式 yyyy-MM-dd；from/to 必填（to 为零点时内部取当日 23:59:59.999999 闭区间惯例：
 * to 参数按"含当日"解释，转内部 <code>[from 00:00, to+1d 00:00)</code>）。</p>
 */
@Path("/stats/hit-rate")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HitRateStatsResource {

    private static final Logger logger = LoggerFactory.getLogger(HitRateStatsResource.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 导出列头 */
    private static final String CSV_HEADER = "消息ID,查询问题,用户,来源,所属知识库,模型回答,查询时间";

    /** 所有接口（含 trend）最大时间窗：92 天（三个月；全窗口聚合 + unnest 展开，成本 ∝ 行数） */
    private static final int MAX_RANGE_DAYS = 92;

    @Context
    HttpHeaders httpHeaders;

    private final HitRateQueryService hitRateQueryService;

    @Inject
    public HitRateStatsResource(HitRateQueryService hitRateQueryService) {
        this.hitRateQueryService = hitRateQueryService;
    }

    /**
     * 概览四卡：整体命中率（含环比）、总查询数、最高/最低（芯片维度）
     */
    @GET
    @Path("/summary")
    public Uni<ResponseResult<HitRateSummaryItem>> summary(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("source") String source,
            @QueryParam("kbId") String kbId) {
        String locale = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        Window window = parseWindow(from, to, MAX_RANGE_DAYS);
        return hitRateQueryService.summary(window.from(), window.to(), normalize(source), blankToNull(kbId))
                .onItem().transform(result -> ResponseResult.ok(result, locale));
    }

    /**
     * 时间趋势（granularity=day/week/month，默认 day）
     */
    @GET
    @Path("/trend")
    public Uni<ResponseResult<List<TrendPointItem>>> trend(
            @QueryParam("granularity") String granularity,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("source") String source,
            @QueryParam("kbId") String kbId) {
        Window window = parseWindow(from, to, MAX_RANGE_DAYS);
        return hitRateQueryService.trend(granularity, window.from(), window.to(), normalize(source), blankToNull(kbId))
                .onItem().transform(result -> ResponseResult.ok(result, locale()));
    }

    /**
     * 芯片（知识库）维度命中率（横向条形图；kbId 传入时仅返回该库一条）
     */
    @GET
    @Path("/by-chip")
    public Uni<ResponseResult<List<ChipHitRateItem>>> byChip(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("source") String source,
            @QueryParam("kbId") String kbId) {
        Window window = parseWindow(from, to, MAX_RANGE_DAYS);
        return hitRateQueryService.byChip(window.from(), window.to(), normalize(source), blankToNull(kbId))
                .onItem().transform(result -> ResponseResult.ok(result, locale()));
    }

    /**
     * 未命中列表（分页；kb_hit=false，排除闲聊）
     */
    @GET
    @Path("/misses")
    public Uni<ResponseResult<PageResult<MissItem>>> misses(
            @QueryParam("keyword") String keyword,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("source") String source,
            @QueryParam("page") int page,
            @QueryParam("pageSize") int size) {
        Window window = parseWindow(from, to, MAX_RANGE_DAYS);
        return hitRateQueryService.misses(blankToNull(keyword), window.from(), window.to(),
                        normalize(source), page <= 0 ? 1 : page, size <= 0 ? 20 : size)
                .onItem().transform(result -> ResponseResult.ok(result, locale()));
    }

    /**
     * 未命中列表 CSV 导出（同筛选条件全量；application/octet-stream + BOM，Excel 兼容）
     */
    @GET
    @Path("/misses/export")
    public Uni<Response> exportMisses(
            @QueryParam("keyword") String keyword,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("source") String source) {
        Window window = parseWindow(from, to, MAX_RANGE_DAYS);
        return hitRateQueryService.missesForExport(blankToNull(keyword), window.from(), window.to(),
                        normalize(source))
                .onItem().transform(items -> {
                    byte[] xlsx = buildXlsx(items);
                    return Response.ok(xlsx)
                            // application/octet-stream：字节直通，无 charset 转换问题
                            .type(MediaType.APPLICATION_OCTET_STREAM)
                            .header("Content-Disposition",
                                    "attachment; filename=miss-list-"
                                            + LocalDate.now().format(DATE_FORMAT) + ".xlsx")
                            .build();
                });
    }

    /** 时间窗解析：from/to 必填 yyyy-MM-dd；to 按"含当日"转 next-day 0 点；窗口超上限拒绝。异常消息按 Accept-Language 国际化 */
    private Window parseWindow(String from, String to, int maxDays) {
        AppMessages messages = LocaleUtil.getAppMessage(locale());
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalParameterException(messages.hitRateFromToRequired());
        }
        try {
            LocalDate fromDate = LocalDate.parse(from.trim(), DATE_FORMAT);
            LocalDate toDate = LocalDate.parse(to.trim(), DATE_FORMAT);
            LocalDateTime fromTs = fromDate.atStartOfDay();
            LocalDateTime toTs = toDate.plusDays(1).atStartOfDay();
            if (!fromTs.isBefore(toTs)) {
                throw new IllegalParameterException(messages.hitRateFromBeforeTo());
            }
            if (fromDate.plusDays(maxDays).isBefore(toDate)) {
                throw new IllegalParameterException(messages.hitRateRangeTooLargeMonths());
            }
            return new Window(fromTs, toTs);
        } catch (DateTimeParseException e) {
            throw new IllegalParameterException(messages.hitRateDateFormatError());
        }
    }

    private static String normalize(String source) {
        return (source == null || source.isBlank()) ? null : source.trim();
    }

    /** Accept-Language 取 locale（ResponseResult.ok 需要） */
    private String locale() {
        return httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Xlsx 组装（Apache POI XSSF）：
     * <ul>
     *   <li>表头加粗 + 灰底，冻结首行</li>
     *   <li>列宽按内容自适应（上限 60 字符，中文按 2 计）</li>
     *   <li>大数据量用 SXSSF 流式写（窗口 100 行 flush，内存有界）</li>
     * </ul>
     */
    private static byte[] buildXlsx(List<MissItem> items) {
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String[] headers = CSV_HEADER.split(",");
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook wb =
                     new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
            var sheet = wb.createSheet("未命中列表");
            // 表头样式：加粗 + 灰底
            var headerStyle = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            var headerRow = sheet.createRow(0);
            int[] widths = new int[headers.length];
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
                headerRow.getCell(i).setCellStyle(headerStyle);
                widths[i] = displayWidth(headers[i]);
            }
            // 数据行（source 翻译为中文，与页面展示一致）
            for (int r = 0; r < items.size(); r++) {
                MissItem item = items.get(r);
                String[] cells = {
                        item.messageId(), item.query(), item.account(),
                        sourceLabel(item.source()), item.kbName(), item.answerText(),
                        item.createdAt() == null ? "" : item.createdAt().format(timeFormat)
                };
                var row = sheet.createRow(r + 1);
                for (int i = 0; i < cells.length; i++) {
                    row.createCell(i).setCellValue(cells[i] == null ? "" : cells[i]);
                    widths[i] = Math.max(widths[i], displayWidth(cells[i]));
                }
            }
            // 列宽自适应（POI 单位 1/256 字符宽，上限 60 字符）
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, Math.min(widths[i], 60) * 256);
            }
            // 冻结首行
            sheet.createFreezePane(0, 1);
            try (var bos = new java.io.ByteArrayOutputStream()) {
                wb.write(bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException("未命中列表导出生成失败", e);
        }
    }

    /** 来源渠道中文标签（与页面展示一致；未知值原样输出） */
    private static String sourceLabel(String source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case "CHAT_QA" -> "智能问答";
            case "RECEPTION_BOT" -> "前台机器人";
            case "AGENT_CALL" -> "Agent调用";
            default -> source;
        };
    }

    /** 列宽估算：中文/全角按 2，其余按 1 */
    private static int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 8;
        }
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            width += value.charAt(i) > 127 ? 2 : 1;
        }
        return Math.max(width + 2, 8);
    }

    /** 解析后的统计时间窗 */
    private record Window(LocalDateTime from, LocalDateTime to) {
    }
}
