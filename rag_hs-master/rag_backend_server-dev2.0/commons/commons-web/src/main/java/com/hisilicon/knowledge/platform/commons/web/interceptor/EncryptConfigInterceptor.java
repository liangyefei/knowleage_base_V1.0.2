package com.hisilicon.knowledge.platform.commons.web.interceptor;

import com.hisilicon.knowledge.platform.commons.web.utils.PemRsaUtils;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

/**
 * 1、使用方式为 正常配置值的前面拼接Encrypt=>字符串，如
 * quarkus.datasource.password = Encrypt=>xxxx
 */
@Priority(value = Priorities.PLATFORM)
public class EncryptConfigInterceptor implements ConfigSourceInterceptor {
    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(EncryptConfigInterceptor.class);

    /** 需要加密值的前缀标记 */
    private static final String ENCRYPT_PREFIX_NAME = "Encrypt=>";

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue config = context.proceed(name);
        if (config != null && config.getValue().startsWith(ENCRYPT_PREFIX_NAME)) {
            String encryptValue = config.getValue().replace(ENCRYPT_PREFIX_NAME, "").trim();
            return config.withValue(PemRsaUtils.decryptStr(encryptValue, null));
        }
        return config;
    }

    public static void main(String[] args) {
        String value = "redis://172.16.20.12:31926/7";
//        String value = "jdbc:postgresql://172.16.20.13:32615/gptbrain-platform";
//        String value = "jdbc:postgresql://172.16.20.13:32615";
        String encryptBase64 = PemRsaUtils.encryptBase64(value, null);
        logger.info("after encrypt: {}", encryptBase64);
    }
}
