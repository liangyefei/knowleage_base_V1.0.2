package com.hisilicon.knowledge.platform.commons.i18n.utils;

import com.hisilicon.knowledge.platform.commons.i18n.LanguageEnum;
import com.hisilicon.knowledge.platform.commons.i18n.LocaleNotSupportedException;
import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import io.quarkus.qute.i18n.Localized;
import io.quarkus.qute.i18n.MessageBundles;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 国际化工具
 */
public class LocaleUtil {
    /**
     * 获取系统制定语言消息类
     * @param localeStr 语言
     * @return 系统国际化消息类
     */
    public static AppMessages getAppMessage(String localeStr) {
        Locale locale = findLocale(localeStr);

        return MessageBundles.get(AppMessages.class, Localized.Literal.of(locale.toLanguageTag()));
    }

    /**
     * 获取消息类
     * @param localeStr 区域
     * @param clazz 消息类类型
     * @return 消息对象
     */
    public static <T> T getLocaleMessage(String localeStr, Class<T> clazz) {
        Locale locale = findLocale(localeStr);

        return MessageBundles.get(clazz, Localized.Literal.of(locale.toLanguageTag()));
    }

    public static Locale findLocale(String localeStr) {
        Locale locale = Locale.SIMPLIFIED_CHINESE;

        if (Objects.nonNull(localeStr) && !localeStr.isBlank()) {
            String[] localeArr = localeStr.split(",");
            Optional<LanguageEnum> languageEnum = Optional.empty();
            for (String nowLocale : localeArr) {
                String candidate = nowLocale.contains(";") ? nowLocale.split(";")[0] : nowLocale;
                languageEnum = findLanguage(candidate);
                if (languageEnum.isPresent()) {
                    break;
                }
            }

            if (languageEnum.isEmpty()) {
                throw new LocaleNotSupportedException("很抱歉，区域：[%s]暂时还不支持！".formatted(localeStr));
            }
            locale = Locale.forLanguageTag(languageEnum.get().getLanguage());
        }
        return locale;
    }

    private static Optional<LanguageEnum> findLanguage(String localeStr) {
        for (LanguageEnum e : LanguageEnum.values()) {
            if (localeStr.equalsIgnoreCase(e.getLanguage()) || localeStr.equalsIgnoreCase(e.name())) {
                return Optional.of(e);
            }
        }

        return Optional.empty();
    }

    private LocaleUtil() {
        throw new IllegalStateException("工具类不允许创建实例");
    }
}
