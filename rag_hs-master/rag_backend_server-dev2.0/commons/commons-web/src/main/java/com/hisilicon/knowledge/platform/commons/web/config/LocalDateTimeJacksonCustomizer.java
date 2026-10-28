package com.hisilicon.knowledge.platform.commons.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 Jackson 定制：LocalDateTime 序列化格式
 * <p>统一为 {@code yyyy-MM-dd HH:mm:ss}（空格分隔，前端约定 2026-08-10 10:46:52），
 * 替代 JavaTimeModule 默认的 ISO-8601 {@code 2026-08-10T10:46:52}。</p>
 * <p>影响所有模块所有接口返回的 LocalDateTime 字段（createdAt/updatedAt 等）。
 * 反序列化不覆盖——保持 Jackson 默认宽松模式（本项目 Command 无 LocalDateTime 入参）。</p>
 */
@Singleton
public class LocalDateTimeJacksonCustomizer implements ObjectMapperCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(LocalDateTimeJacksonCustomizer.class);

    /** 统一时间格式：yyyy-MM-dd HH:mm:ss */
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 必须最后执行：Quarkus 以 priority 降序执行 customizer，
     * JavaTimeModule 通过内置 customizer 注册（默认 priority），
     * 本类用 MINIMUM_PRIORITY 排在最后，保证 registerModule 覆盖 JavaTimeModule 的 ISO 序列化器。
     */
    @Override
    public int priority() {
        return MINIMUM_PRIORITY;
    }

    @Override
    public void customize(ObjectMapper objectMapper) {
        logger.info("LocalDateTimeJacksonCustomizer: applying unified time format yyyy-MM-dd HH:mm:ss");
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        objectMapper.registerModule(module);
    }
}
