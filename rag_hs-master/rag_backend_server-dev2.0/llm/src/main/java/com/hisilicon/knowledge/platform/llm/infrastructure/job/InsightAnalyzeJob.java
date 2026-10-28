package com.hisilicon.knowledge.platform.llm.infrastructure.job;

import com.hisilicon.knowledge.platform.llm.command.InsightCommandService;
import com.hisilicon.knowledge.platform.llm.infrastructure.repository.InsightSettingsPanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 问答洞察定时分析 Job。
 * <p>每分钟 tick，根据 insight_settings 判断当前时刻是否命中配置：
 * <ul>
 *   <li>HH:mm 是否等于 schedule_time</li>
 *   <li>DAILY：每天都跑</li>
 *   <li>WEEKLY：仅 schedule_day（1=周一..7=周日）跑</li>
 *   <li>MONTHLY：仅 schedule_day（1~31日）跑（超过当月天数则跳过）</li>
 * </ul>
 * 前端改配置立即生效。</p>
 */
@ApplicationScoped
public class InsightAnalyzeJob {

    private static final Logger logger = LoggerFactory.getLogger(InsightAnalyzeJob.class);

    private volatile LocalDateTime lastTriggeredAtMinute = null;

    private final InsightSettingsPanacheRepository settingsRepo;
    private final InsightCommandService commandService;

    public InsightAnalyzeJob(InsightSettingsPanacheRepository settingsRepo,
                             InsightCommandService commandService) {
        this.settingsRepo = settingsRepo;
        this.commandService = commandService;
    }

    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @WithSession
    public Uni<Void> tick() {
        LocalDateTime now = LocalDateTime.now();
        if (lastTriggeredAtMinute != null
                && lastTriggeredAtMinute.getHour() == now.getHour()
                && lastTriggeredAtMinute.getMinute() == now.getMinute()) {
            return Uni.createFrom().voidItem();
        }
        return settingsRepo.findById(1)
                .chain(po -> {
                    String type = po == null ? "DAILY" : po.getScheduleType();
                    String time = po == null ? "02:00" : po.getScheduleTime();
                    Integer day = po == null ? null : po.getScheduleDay();

                    LocalTime configured;
                    try {
                        configured = LocalTime.parse(time);
                    } catch (Exception e) {
                        logger.warn("Invalid schedule_time: {}, use 02:00", time);
                        configured = LocalTime.of(2, 0);
                    }
                    if (now.getHour() != configured.getHour() || now.getMinute() != configured.getMinute()) {
                        return Uni.createFrom().voidItem();
                    }
                    LocalDateTime startTime = calcStartTime(type, day, now);
                    if (startTime == null) {
                        return Uni.createFrom().voidItem();
                    }
                    lastTriggeredAtMinute = now;
                    logger.info("Insight scheduled analyze triggered: type={}, day={}, time={}", type, day, time);
                    return commandService.batchAnalyze(startTime, now)
                            .onItem().invoke(r ->
                                    logger.info("Insight scheduled analyze done: scanned={}, analyzed={}, failed={}",
                                            r.scanned(), r.analyzed(), r.failed()))
                            .replaceWithVoid();
                })
                .onFailure().recoverWithUni(e -> {
                    logger.warn("Insight analyze tick failed: {}", e.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }

    /** 判断当前时刻是否命中周期配置，命中返回分析起始时间，否则返回 null。 */
    private static LocalDateTime calcStartTime(String type, Integer day, LocalDateTime now) {
        return switch (type) {
            case "DAILY" -> now.minusDays(1);
            case "WEEKLY" -> {
                int expected = day == null ? 1 : day; // 默认周一
                // 1=周一..7=周日 → DayOfWeek.MONDAY..SUNDAY
                DayOfWeek expectedDow = DayOfWeek.of(((expected - 1) % 7) + 1);
                yield now.getDayOfWeek() == expectedDow ? now.minusWeeks(1) : null;
            }
            case "MONTHLY" -> {
                int expected = day == null ? 1 : day; // 默认1号
                // 当月没有这一天（如31日遇2月）→ 跳过
                int maxDay = now.toLocalDate().lengthOfMonth();
                yield now.getDayOfMonth() == expected && expected <= maxDay ? now.minusMonths(1) : null;
            }
            default -> now.minusDays(1);
        };
    }
}
