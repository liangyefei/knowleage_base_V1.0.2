package com.hisilicon.knowledge.platform.llm.infrastructure.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 问答洞察定时扫描配置 PO（单行全局，id 固定=1）。
 * <p>周期类型：DAILY=每日 / WEEKLY=每周 / MONTHLY=每月。
 * <ul>
 *   <li>schedule_time: HH:mm 每天执行时间点（如 "15:01"）</li>
 *   <li>schedule_day: WEEKLY 时 1=周一..7=周日；MONTHLY 时 1~31 日；DAILY 时为 null</li>
 * </ul>
 * Job 每分钟 tick，比对当前时间是否命中配置。</p>
 */
@Entity
@Table(name = "insight_settings")
public class InsightSettingsPO {

    @Id
    private Integer id = 1;

    /** 周期类型：DAILY / WEEKLY / MONTHLY */
    @Column(name = "schedule_type", nullable = false, length = 10)
    private String scheduleType = "DAILY";

    /** 执行时间点 HH:mm，默认 "02:00" */
    @Column(name = "schedule_time", nullable = false, length = 5)
    private String scheduleTime = "02:00";

    /** WEEKLY: 1=周一..7=周日；MONTHLY: 1~31 日；DAILY: null */
    @Column(name = "schedule_day")
    private Integer scheduleDay;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(String scheduleTime) { this.scheduleTime = scheduleTime; }
    public Integer getScheduleDay() { return scheduleDay; }
    public void setScheduleDay(Integer scheduleDay) { this.scheduleDay = scheduleDay; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
