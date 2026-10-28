package com.hisilicon.knowledge.platform.commons.web.interceptor;

import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * 对get请求的参数进行trim
 */
@Provider
public class TrimStringParamConverterProvider implements ParamConverterProvider {
    @SuppressWarnings("unchecked")
    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (String.class.equals(rawType)) {
            return (ParamConverter<T>) new TrimStringParamConverter();
        }
        return null;
    }

    public static class TrimStringParamConverter implements ParamConverter<String> {
        @Override
        public String fromString(String value) {
            return value != null ? value.trim() : null;
        }

        @Override
        public String toString(String value) {
            return value;
        }
    }
}
