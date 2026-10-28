package com.hisilicon.knowledge.platform.llm.domain.repository;

import com.hisilicon.knowledge.platform.llm.domain.entity.KbStatsEntity;
import io.smallrye.mutiny.Uni;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 问答 KB 统计仓储接口
 */
public interface KbStatsRepository {

    /**
     * 持久化 kb_stats 统计（一回答一行，message_id 主键；重复调用按主键幂等覆盖）
     */
    Uni<KbStatsEntity> persist(KbStatsEntity entity);

    /**
     * 按主键查找统计行
     *
     * @param messageId ASSISTANT 消息 id（= qa_kb_stats.message_id 主键）
     * @return 不存在时为 null
     */
    Uni<KbStatsEntity> findById(String messageId);

    /**
     * 统计时间窗内问答总数、命中数与闲聊数（命中率仪表盘概览/环比口径）。
     * <p>分母口径：命中率 = hit / (total - chitchat)。闲聊（no_hit_reason='chitchat'）未触发检索，
     * 不进分母——否则命中率永远无法由未命中列表解释（列表按同一谓词排除闲聊）。</p>
     *
     * @param from   窗口起点（含）
     * @param to     窗口终点（不含）
     * @param source 来源渠道筛选（null=全部渠道）
     * @param kbId   知识库（芯片）筛选：selected_kbs 或 hit_kbs 含该 id 的行（null=全部）
     * @return total=全部行数（含闲聊，"总查询数"展示口径）；hit=命中数（仅非闲聊行）；chitchat=闲聊行数
     */
    Uni<TotalsAgg> countTotals(LocalDateTime from, LocalDateTime to, String source, String kbId);

    /**
     * 按时间桶（day/week/month）聚合问答总数、命中数与闲聊数（时间趋势图）。
     * <p>桶内命中率 = hit / (total - chitchat)（与概览同口径；柱状仍展示 total）。</p>
     *
     * @param bucket date_trunc 粒度，仅允许 day/week/month（调用方白名单内联）
     * @return bucket=展示标签（day: yyyy-MM-dd；week: ISO 周历 "yyyy年ww周"；month: "yyyy年MM月"），bucketStart=桶起始日
     */
    Uni<List<TrendAgg>> aggregateTrend(String bucket, LocalDateTime from, LocalDateTime to,
                                       String source, String kbId);

    /**
     * 芯片（=知识库）维度命中率聚合。
     * <p>行展开口径：每行按 selected_kbs ∪ hit_kbs 展开 kb（去重），闲聊行（no_hit_reason='chitchat'）
     * 不参与展开——分母=含该 kb 且非闲聊的行数，hit=其中 kb 命中（kb ∈ hit_kbs 且 kb_hit=true）的行数。</p>
     *
     * @param kbId 只看该知识库（非空时仅返回该 kb 一条；null=全部）
     */
    Uni<List<ChipAgg>> aggregateByChip(LocalDateTime from, LocalDateTime to, String source, String kbId);

    /**
     * 分页查询未命中行（kb_hit 非 true；排除 no_hit_reason='chitchat' 闲聊行）。
     * <p>谓词与命中率分母同源：<b>列表为空 ⟺ 命中率 100%</b>（有效查询非 0 时）。</p>
     *
     * @param keyword 问题原文模糊匹配（null=不过滤）
     * @param from    查询时间起点（含；null=不限）
     * @param to      查询时间终点（不含；null=不限）
     * @param source  来源渠道筛选（null=全部）
     * @param page    页码（1 起）
     * @param size    页大小
     * @return 当前页实体（按 created_at 倒序；answerText 已关联 conversation_messages 的 ASSISTANT 消息正文回填）
     */
    Uni<List<KbStatsEntity>> findMisses(String keyword, LocalDateTime from, LocalDateTime to,
                                        String source, int page, int size);

    /**
     * 统计未命中行总数（与 {@link #findMisses} 同筛选条件）
     */
    Uni<Long> countMisses(String keyword, LocalDateTime from, LocalDateTime to, String source);

    /**
     * 知识库名称映射（kb_names_json 落库快照的 distinct id→name 键值对展开）。
     *
     * @return id→name 映射（无快照行返回空映射；调用方对缺失 id 回退展示 id 本身）
     */
    Uni<Map<String, String>> resolveKbNames();

    /**
     * 总数/命中数/闲聊数聚合行
     *
     * @param total    时间窗内全部行数（含闲聊，"总查询数"展示口径）
     * @param hit      命中行数（kb_hit=true 且非闲聊）
     * @param chitchat 闲聊行数（no_hit_reason='chitchat'；不进命中率分母）
     */
    record TotalsAgg(long total, long hit, long chitchat) {
    }

    /** 时间桶聚合行（bucket=展示标签；bucketStart=桶起始日 yyyy-MM-dd，供前端排序/tooltip） */
    record TrendAgg(String bucket, String bucketStart, long total, long hit, long chitchat) {
    }

    /** 芯片维度聚合行（kbId=知识库 id 即芯片标识） */
    record ChipAgg(String kbId, long total, long hit) {
    }
}
