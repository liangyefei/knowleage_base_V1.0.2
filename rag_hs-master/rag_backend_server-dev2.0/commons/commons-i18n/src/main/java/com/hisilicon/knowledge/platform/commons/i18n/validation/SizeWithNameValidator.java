package com.hisilicon.knowledge.platform.commons.i18n.validation;

import com.hisilicon.knowledge.platform.commons.i18n.AcceptHeaderLocaleResolver;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;

@ApplicationScoped
public class SizeWithNameValidator implements ConstraintValidator<SizeWithName, String> {
    private static final Logger logger = LoggerFactory.getLogger(SizeWithNameValidator.class);

    private final AcceptHeaderLocaleResolver localeResolver; // 注入消息束

    private final FieldNameResolver fieldNameResolver;

    public SizeWithNameValidator(AcceptHeaderLocaleResolver localeResolver, FieldNameResolver fieldNameResolver) {
        this.localeResolver = localeResolver;
        this.fieldNameResolver = fieldNameResolver;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        Optional<SizeWithName> annotation = getAnnotationFromContext(context);

        if (annotation.isEmpty()) {
            logger.error("Failed to obtain annotation");
            return true;
        }

        int min = annotation.get().min();
        int max = annotation.get().max();
        String fieldNameKey = annotation.get().fieldName();

        if (value == null) {
            return true;
        }

        int length = value.length();
        if (length >= min && length <= max) {
            return true;
        }

        // 获取字段名称和当前语言环境
        Locale locale = localeResolver.resolve();
        String fieldName = fieldNameResolver.resolve(fieldNameKey, locale);

        // 构建国际化错误消息
        String errorMessage = formatMessage(fieldName, min, max, locale);

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(errorMessage).addConstraintViolation();

        return false;
    }

    /**
     * 格式化消息，替换占位符
     */
    private String formatMessage(String fieldName, int min, int max, Locale locale) {
        // 获取指定语言环境的消息模板
        String template = getMessageTemplate(fieldName, min, max, locale);

        return template.replace("{fieldName}", fieldName)
                .replace("{min}", "" + min).replace("{max}", "" + max);
    }

    /**
     * 从资源文件中获取消息模板
     */
    private String getMessageTemplate(String fieldName, int min, int max, Locale locale) {
        try {
            AppMessages appMessages = LocaleUtil.getAppMessage(locale.toLanguageTag());
            return appMessages.sizeWithName(fieldName, min, max);
        } catch (MissingResourceException | IllegalStateException e) {
            // 回退到默认消息
            logger.error("Failed to get message template common.SizeWithName.message", e);
            return "{0}的长度必须在{1}和{2}之间";
        }
    }

    /**
     * 从上下文中获取注解信息；不可达（非 Hibernate Validator 实现/类型不匹配）为 empty
     */
    private Optional<SizeWithName> getAnnotationFromContext(ConstraintValidatorContext context) {
        try {
            // 使用 Hibernate Validator 的特定实现
            if (context instanceof ConstraintValidatorContextImpl contextImpl
                    && contextImpl.getConstraintDescriptor().getAnnotation() instanceof SizeWithName sizeWithName) {
                return Optional.of(sizeWithName);
            }
        } catch (ClassCastException | IllegalStateException e) {
            logger.error("Failed to obtain annotation info", e);
        }
        return Optional.empty();
    }
}

