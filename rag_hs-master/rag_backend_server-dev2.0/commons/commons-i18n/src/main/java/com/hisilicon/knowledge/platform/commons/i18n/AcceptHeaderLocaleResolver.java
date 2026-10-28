package com.hisilicon.knowledge.platform.commons.i18n;

import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.core.HttpHeaders;
import org.hibernate.validator.spi.messageinterpolation.LocaleResolver;
import org.hibernate.validator.spi.messageinterpolation.LocaleResolverContext;

import java.util.Locale;

@RequestScoped
public class AcceptHeaderLocaleResolver implements LocaleResolver {
    private final HttpHeaders httpHeaders;

    public AcceptHeaderLocaleResolver(HttpHeaders httpHeaders) {
        this.httpHeaders = httpHeaders;
    }

    @Override
    public Locale resolve(LocaleResolverContext context) {
        // Hibernate Validator locale resolution — fall back to zh-CN
        // for uninitialized locales (en-US requires Quarkus build-time config)
        try {
            return resolve();
        } catch (IllegalArgumentException e) {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }

    public Locale resolve() {
        String acceptLanguage = httpHeaders.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        try {
            return LocaleUtil.findLocale(acceptLanguage);
        } catch (IllegalArgumentException e) {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }
}
