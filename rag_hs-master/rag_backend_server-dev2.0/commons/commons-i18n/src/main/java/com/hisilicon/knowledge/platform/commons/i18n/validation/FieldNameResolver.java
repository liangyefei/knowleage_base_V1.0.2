package com.hisilicon.knowledge.platform.commons.i18n.validation;

import io.quarkus.runtime.annotations.RegisterResourceBundle;
import jakarta.enterprise.context.RequestScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * 字段名解析器。
 * <p>使用 JDK {@link ResourceBundle} 加载字段名（native image 需构建时注册 bundle，
 * 否则线上 native 镜像抛 MissingResourceException——2026-08 生产实测）。</p>
 */
@RegisterResourceBundle(bundleName = "messages.common-fieldNames")
@RequestScoped
public class FieldNameResolver {
    private static final Logger logger = LoggerFactory.getLogger(FieldNameResolver.class);

    @ConfigProperty(name = "app.i18n.fieldName-list")
    Optional<Set<String>> fieldNameOptional;

    public String resolve(String key, Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages.common-fieldNames", locale);
        try {
            return bundle.getString(key);
        } catch (MissingResourceException | ClassCastException e) {
            // 通用配置中获取失败
            if (fieldNameOptional.isEmpty()) {
                logger.error("Failed to get field name [%s] from messages.common-fieldNames".formatted(key), e);
                return key;
            }
            return findFieldWithConfig(key, locale);
        }
    }

    /**
     * 从指定的消息文件中获取字段名
     * @param key 字段名标识
     * @param locale 语言环境
     * @return 获取结果
     */
    private String findFieldWithConfig(String key, Locale locale) {
        Set<String> fieldNameList = fieldNameOptional.orElse(Collections.emptySet());
        for (String name : fieldNameList) {
            try {
                return ResourceBundle.getBundle(name, locale, Thread.currentThread().getContextClassLoader())
                        .getString(key);
            } catch (MissingResourceException | ClassCastException e) {
                logger.error("Failed to get field name [%s] from [%s]".formatted(key, name), e);
            }
        }
        return key;
    }
}
