package com.hisilicon.knowledge.platform.commons.web.exception.mapper;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.hisilicon.knowledge.platform.commons.errorcode.enums.ErrorCodeEnum;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.ResponseResult;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Provider
public class InvalidFormatExceptionMapper implements ExceptionMapper<InvalidFormatException> {
    @Context
    HttpHeaders httpHeaders;
    private static final Logger logger = LoggerFactory.getLogger(InvalidFormatExceptionMapper.class);

    @Override
    public Response toResponse(InvalidFormatException exception) {
        logger.error("", exception);
        // 获取字段名
        String fieldName = exception.getPath().isEmpty() ? "参数" :
                exception.getPath().getFirst().getFieldName();

        // 获取参数值
        String invalidValue = exception.getValue() != null ?
                exception.getValue().toString() : "null";

        // 根据目标类型推断可读的期望描述
        String expectedType = getExpectedTypeDescription(exception);
        AppMessages appMessages = LocaleUtil.getAppMessage(
                httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)
        );

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ResponseResult.<Void>builder()
                        .error(ErrorCodeEnum.USER_ERROR_A0410)
                        .message(appMessages.incorrectFormat(fieldName, expectedType, invalidValue))
                        .build())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** 整数类型全集（G.CTL.01：条件表达式收敛为集合判断） */
    private static final Set<Class<?>> INTEGER_TYPES = Set.of(
            Integer.class, Long.class, Short.class, Byte.class);
    /** 浮点/大数类型全集 */
    private static final Set<Class<?>> DECIMAL_TYPES = Set.of(Double.class, Float.class, BigDecimal.class);
    /** 日期时间类型全集 */
    private static final Set<Class<?>> DATE_TIME_TYPES = Set.of(
            LocalDate.class, LocalDateTime.class, LocalTime.class, OffsetDateTime.class, Instant.class);

    /**
     * 根据反序列化目标类型生成可读的期望描述，替代统一的"日期"文案
     */
    private String getExpectedTypeDescription(InvalidFormatException ex) {
        Class<?> targetType = ex.getTargetType();
        if (targetType == null) {
            return "正确格式";
        }
        // 整数类型（含对应基本类型）
        if (isBoxedOrPrimitive(targetType, INTEGER_TYPES)) {
            return "整数";
        }
        // 布尔类型
        if (Boolean.class.equals(targetType) || boolean.class.equals(targetType)) {
            return "布尔值";
        }
        // 浮点/大数类型（含对应基本类型）
        if (isBoxedOrPrimitive(targetType, DECIMAL_TYPES)) {
            return "数字";
        }
        // 日期类型：优先取 @JsonFormat 注解的 pattern，无注解时给出默认格式
        if (Date.class.isAssignableFrom(targetType) || DATE_TIME_TYPES.contains(targetType)) {
            String pattern = getDateFormatFromAnnotation(ex).orElse(null);
            return (pattern == null || pattern.isBlank()) ? "yyyy-MM-dd HH:mm:ss" : pattern;
        }
        // 枚举类型：列举合法值
        if (targetType.isEnum()) {
            return Arrays.stream(targetType.getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }
        return "正确格式";
    }

    /** 目标类型是否为给定包装类型集合中的类型或对应基本类型 */
    private static boolean isBoxedOrPrimitive(Class<?> targetType, Set<Class<?>> boxedTypes) {
        return boxedTypes.contains(targetType)
                || (targetType.isPrimitive() && boxedTypes.contains(boxedEquivalent(targetType)));
    }

    /** 基本类型 → 包装类型（无映射返回原类型） */
    private static Class<?> boxedEquivalent(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        return primitive;
    }

    // 从字段注解中提取日期格式（无注解/反射不可达为 empty）
    private Optional<String> getDateFormatFromAnnotation(InvalidFormatException ex) {
        try {
            Class<?> targetClass = ex.getTargetType();
            String fieldName = ex.getPath().getFirst().getFieldName();
            Field field = targetClass.getDeclaredField(fieldName);

            JsonFormat format = field.getAnnotation(JsonFormat.class);
            if (format != null && !format.pattern().isEmpty()) {
                return Optional.of(format.pattern());
            }
        } catch (NoSuchFieldException | SecurityException e) {
            // 处理异常
            logger.error("", e);
            return Optional.empty();
        }
        // 默认格式
        return Optional.empty();
    }
}
