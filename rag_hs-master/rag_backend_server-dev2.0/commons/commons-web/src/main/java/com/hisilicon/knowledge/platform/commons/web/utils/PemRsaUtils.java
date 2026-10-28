package com.hisilicon.knowledge.platform.commons.web.utils;

import com.hisilicon.knowledge.platform.commons.i18n.messages.AppMessages;
import com.hisilicon.knowledge.platform.commons.i18n.utils.LocaleUtil;
import com.hisilicon.knowledge.platform.commons.web.exception.DecryptException;
import com.hisilicon.knowledge.platform.commons.web.exception.EncryptException;
import com.hisilicon.knowledge.platform.commons.web.exception.SignException;
import com.hisilicon.knowledge.platform.commons.web.exception.VerifySignatureException;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

public class PemRsaUtils {
    private static final Logger logger = LoggerFactory.getLogger(PemRsaUtils.class);
    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;
    private static final int MAX_ENCRYPT_BLOCK = (KEY_SIZE / 8) - 11;
    private static final int MAX_DECRYPT_BLOCK = KEY_SIZE / 8;

    // ======================== 公钥/私钥内容获取 ========================

    /**
     * 获取 RSA 公钥内容（PEM 格式）。
     * <p>优先从 MicroProfile Config 键 {@code rsa.public-key} 读取（支持 K8s ConfigMap 注入），
     * 未配置时回退到 classpath 根目录的 {@code publicKey.pem}。</p>
     *
     * @return PEM 格式公钥字符串
     * @throws IllegalStateException 公钥不可用时抛出
     */
    public static String getPublicKeyContent() {
        return getKeyContent("rsa.public-key", "publicKey.pem");
    }

    /**
     * 获取 RSA 私钥内容（PEM 格式）。
     * <p>优先从 MicroProfile Config 键 {@code rsa.private-key} 读取（支持 K8s ConfigMap 注入），
     * 未配置时回退到 classpath 根目录的 {@code privateKey.pem}。</p>
     *
     * @return PEM 格式私钥字符串
     * @throws IllegalStateException 私钥不可用时抛出
     */
    public static String getPrivateKeyContent() {
        return getKeyContent("rsa.private-key", "privateKey.pem");
    }

    private static String getKeyContent(String configKey, String resourceFile) {
        Optional<String> configValue = ConfigProvider.getConfig().getOptionalValue(configKey, String.class);
        if (configValue.isPresent() && !configValue.get().isBlank()) {
            return configValue.get();
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourceFile)) {
            if (is == null) {
                logger.error("Key resource {} not found and config {} is not set", resourceFile, configKey);
                throw new IllegalStateException(
                        "Key not found: config '" + configKey + "' is unset and classpath resource '" + resourceFile + "' is missing");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to read key resource {}", resourceFile, e);
            throw new IllegalStateException("Failed to read key resource: " + resourceFile, e);
        }
    }

    // ======================== 私钥解析 ========================

    /**
     * 获取默认私钥的 Java {@link PrivateKey} 对象
     *
     * @return PrivateKey
     */
    public static PrivateKey getPrivateKey() {
        return readPrivateKey(new StringReader(getPrivateKeyContent()));
    }

    /**
     * 从 PEM Reader 中读取 PrivateKey
     * <p>按内容嗅探解析（不依赖 PEM 头）：提取 base64 → DER 解码 → 先试 PKCS#8 再试 PKCS#1。
     * 兼容"头 PKCS#1 + 体 PKCS#8"等格式不匹配的私钥（2026-08 生产实测：网站导出工具常见）。</p>
     */
    private static PrivateKey readPrivateKey(Reader privateKeyReader) {
        try {
            String pem = readAll(privateKeyReader);
            return parsePrivateKeyDer(extractDer(pem), pem);
        } catch (DecryptException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            logger.error("Failed to read private key", e);
            throw new DecryptException("Failed to read private key");
        }
    }

    /**
     * 从 PEM 文本提取 DER 字节（去掉 BEGIN/END 行与空白）
     */
    private static byte[] extractDer(String pem) {
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    /**
     * 按 DER 内容解析私钥：先 PKCS#8（PrivateKeyInfo），失败再 PKCS#1（RSAPrivateKey）
     */
    private static PrivateKey parsePrivateKeyDer(byte[] der, String pem) {
        if (pem.contains("ENCRYPTED") || pem.contains("Proc-Type: 4,ENCRYPTED")) {
            throw new DecryptException("Encrypted private key is not supported (password-protected key)");
        }
        try {
            PrivateKeyInfo keyInfo = PrivateKeyInfo.getInstance(der);
            return new JcaPEMKeyConverter().getPrivateKey(keyInfo);
        } catch (IOException | RuntimeException e) {
            logger.debug("PKCS#8 parsing failed, trying PKCS#1: {}", e.getMessage());
        }
        try {
            org.bouncycastle.asn1.pkcs.RSAPrivateKey rsaPrivateKey =
                    org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(der);
            PrivateKeyInfo privateKeyInfo = new PrivateKeyInfo(
                    new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                    rsaPrivateKey
            );
            return new JcaPEMKeyConverter().getPrivateKey(privateKeyInfo);
        } catch (IOException | RuntimeException e) {
            logger.error("Private key parsing failed: neither PKCS#8 nor PKCS#1", e);
            throw new DecryptException("Unsupported or malformed private key (neither PKCS#8 nor PKCS#1)");
        }
    }

    /**
     * 读取 Reader 全部文本
     */
    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ======================== 私钥解密（默认密钥） ========================

    /**
     * 使用默认私钥解密
     *
     * @param str    密文
     * @param locale 语言环境
     * @return 明文
     */
    public static String decryptStr(String str, String locale) {
        if (Objects.isNull(str) || str.isBlank()) {
            return str;
        }
        return doDecrypt(str, locale, new StringReader(getPrivateKeyContent()));
    }

    /**
     * 使用指定私钥文件解密（从 classpath 加载）
     *
     * @param str            密文
     * @param locale         语言环境
     * @param privateKeyFile classpath 上的私钥文件名
     * @return 明文
     */
    public static String decryptStr(String str, String locale, String privateKeyFile) {
        if (Objects.isNull(str) || str.isBlank()) {
            return str;
        }
        return doDecrypt(str, locale, getResourceReader(privateKeyFile));
    }

    /**
     * 使用指定的私钥 PEM 内容解密
     *
     * @param str            密文
     * @param locale         语言环境
     * @param privateKeyPem  私钥 PEM 内容字符串
     * @return 明文
     */
    public static String decryptStrWithKey(String str, String locale, String privateKeyPem) {
        if (Objects.isNull(str) || str.isBlank()) {
            return str;
        }
        return doDecrypt(str, locale, new StringReader(privateKeyPem));
    }

    // ======================== 公钥加密（默认密钥） ========================

    /**
     * 使用默认公钥加密
     *
     * @param str    明文
     * @param locale 语言环境
     * @return 密文
     */
    public static String encryptBase64(String str, String locale) {
        if (Objects.isNull(str) || str.isBlank()) {
            return str;
        }
        return doEncrypt(str, locale, new StringReader(getPublicKeyContent()));
    }

    /**
     * 使用指定公钥文件加密（从 classpath 加载）
     *
     * @param str            明文
     * @param locale         语言环境
     * @param publicKeyFile  classpath 上的公钥文件名
     * @return 密文
     */
    public static String encryptBase64(String str, String locale, String publicKeyFile) {
        if (Objects.isNull(str) || str.isBlank()) {
            return str;
        }
        return doEncrypt(str, locale, getResourceReader(publicKeyFile));
    }

    /**
     * 使用指定的公钥 PEM 内容加密
     *
     * @param str           明文
     * @param locale        语言环境
     * @param publicKeyPem  公钥 PEM 内容字符串
     * @return 密文
     */
    public static String encryptBase64WithKey(String str, String locale, String publicKeyPem) {
        if (Objects.isNull(str) || str.isBlank()) {
            return str;
        }
        return doEncrypt(str, locale, new StringReader(publicKeyPem));
    }

    // ======================== 签名（默认私钥） ========================

    /**
     * 使用默认私钥对数据进行签名
     *
     * @param data   原始数据
     * @param locale 语言环境
     * @return Base64 编码的签名
     */
    public static String sign(String data, String locale) {
        if (Objects.isNull(data) || data.isBlank()) {
            return data;
        }
        try {
            String pem = getPrivateKeyContent();
            PrivateKey privateKey = parsePrivateKeyDer(extractDer(pem), pem);
            return signData(data, privateKey);
        } catch (GeneralSecurityException | RuntimeException e) {
            logger.error("", e);
            if (e instanceof SignException e1) {
                throw e1;
            }
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            throw new SignException(appMessages.signFailed());
        }
    }

    /**
     * 使用指定的私钥 PEM 内容签名
     *
     * @param data          原始数据
     * @param locale        语言环境
     * @param privateKeyPem 私钥 PEM 内容字符串
     * @return Base64 编码的签名
     */
    public static String signWithKey(String data, String locale, String privateKeyPem) {
        if (Objects.isNull(data) || data.isBlank()) {
            return data;
        }
        try {
            PrivateKey privateKey = parsePrivateKeyDer(extractDer(privateKeyPem), privateKeyPem);
            return signData(data, privateKey);
        } catch (GeneralSecurityException | RuntimeException e) {
            logger.error("", e);
            if (e instanceof SignException e1) {
                throw e1;
            }
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            throw new SignException(appMessages.signFailed());
        }
    }

    // ======================== 验签（默认公钥） ========================

    /**
     * 使用默认公钥验证签名
     *
     * @param data           原始数据
     * @param signatureBase64 Base64 编码的签名
     * @param locale         语言环境
     * @return 验证是否通过
     */
    public static boolean verify(String data, String signatureBase64, String locale) {
        if (Objects.isNull(data) || data.isBlank() || Objects.isNull(signatureBase64) || signatureBase64.isBlank()) {
            return false;
        }
        try (PEMParser pemParser = new PEMParser(new StringReader(getPublicKeyContent()))) {
            Object obj = pemParser.readObject();
            if (obj instanceof SubjectPublicKeyInfo keyInfo) {
                PublicKey publicKey = new JcaPEMKeyConverter().getPublicKey(keyInfo);
                return verifySignature(data, signatureBase64, publicKey);
            } else {
                AppMessages appMessages = LocaleUtil.getAppMessage(locale);
                throw new VerifySignatureException(appMessages.verifySignatureFailed());
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logger.error("", e);
            if (e instanceof SignException e1) {
                throw e1;
            }
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            throw new VerifySignatureException(appMessages.verifySignatureFailed());
        }
    }

    /**
     * 使用指定的公钥 PEM 内容验证签名
     *
     * @param data           原始数据
     * @param signatureBase64 Base64 编码的签名
     * @param locale         语言环境
     * @param publicKeyPem   公钥 PEM 内容字符串
     * @return 验证是否通过
     */
    public static boolean verifyWithKey(String data, String signatureBase64, String locale, String publicKeyPem) {
        if (Objects.isNull(data) || data.isBlank() || Objects.isNull(signatureBase64) || signatureBase64.isBlank()) {
            return false;
        }
        try (PEMParser pemParser = new PEMParser(new StringReader(publicKeyPem))) {
            Object obj = pemParser.readObject();
            if (obj instanceof SubjectPublicKeyInfo keyInfo) {
                PublicKey publicKey = new JcaPEMKeyConverter().getPublicKey(keyInfo);
                return verifySignature(data, signatureBase64, publicKey);
            } else {
                AppMessages appMessages = LocaleUtil.getAppMessage(locale);
                throw new VerifySignatureException(appMessages.verifySignatureFailed());
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logger.error("", e);
            if (e instanceof SignException e1) {
                throw e1;
            }
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            throw new VerifySignatureException(appMessages.verifySignatureFailed());
        }
    }

    // ======================== 内部实现 ========================

    /**
     * 执行签名操作
     *
     * @param data       原始数据
     * @param privateKey 私钥
     * @return Base64 编码的签名
     */
    private static String signData(String data, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(SIGN_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] digitalSignature = signature.sign();
        return Base64.getEncoder().encodeToString(digitalSignature);
    }

    /**
     * 执行验证操作
     *
     * @param data           原始数据
     * @param signatureBase64 Base64 编码的签名
     * @param publicKey      公钥
     * @return 验证是否通过
     */
    private static boolean verifySignature(String data, String signatureBase64, PublicKey publicKey) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(SIGN_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        return signature.verify(signatureBytes);
    }

    /**
     * RSA 解密
     *
     * @param cipherText 密文
     * @param privateKey 私钥
     * @return 明文
     */
    private static String decrypt(String cipherText, PrivateKey privateKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        byte[] encryptedBytes = Base64.getDecoder().decode(cipherText);
        int inputLength = encryptedBytes.length;

        int offset = 0;
        byte[] buffer;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        while (inputLength - offset > 0) {
            if (inputLength - offset > MAX_DECRYPT_BLOCK) {
                buffer = cipher.doFinal(encryptedBytes, offset, MAX_DECRYPT_BLOCK);
            } else {
                buffer = cipher.doFinal(encryptedBytes, offset, inputLength - offset);
            }
            outputStream.writeBytes(buffer);
            offset += MAX_DECRYPT_BLOCK;
        }

        return outputStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * RSA 加密
     *
     * @param plainText 明文
     * @param publicKey 公钥
     * @return 密文
     */
    private static String encrypt(String plainText, PublicKey publicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        byte[] inputBytes = plainText.getBytes(StandardCharsets.UTF_8);
        int inputLength = inputBytes.length;

        int offset = 0;
        byte[] buffer;
        byte[] encryptedBytes = new byte[0];

        while (inputLength - offset > 0) {
            if (inputLength - offset > MAX_ENCRYPT_BLOCK) {
                buffer = cipher.doFinal(inputBytes, offset, MAX_ENCRYPT_BLOCK);
            } else {
                buffer = cipher.doFinal(inputBytes, offset, inputLength - offset);
            }
            byte[] newResult = new byte[encryptedBytes.length + buffer.length];
            System.arraycopy(encryptedBytes, 0, newResult, 0, encryptedBytes.length);
            System.arraycopy(buffer, 0, newResult, encryptedBytes.length, buffer.length);
            encryptedBytes = newResult;
            offset += MAX_ENCRYPT_BLOCK;
        }

        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * 执行解密动作
     *
     * @param str              密文
     * @param locale           语言环境
     * @param privateKeyReader 私钥 Reader
     * @return 明文
     */
    private static String doDecrypt(String str, String locale, Reader privateKeyReader) {
        try {
            String pem = readAll(privateKeyReader);
            PrivateKey privateKey = parsePrivateKeyDer(extractDer(pem), pem);
            return decrypt(str, privateKey);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logger.error("Decryption failed", e);
            if (e instanceof DecryptException e1) {
                throw e1;
            }
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            throw new DecryptException(appMessages.decryptFailed());
        }
    }

    /**
     * 执行加密动作
     *
     * @param str              原文
     * @param locale           语言环境
     * @param publicKeyReader  公钥 Reader
     * @return 密文
     */
    private static String doEncrypt(String str, String locale, Reader publicKeyReader) {
        try (PEMParser pemParser = new PEMParser(publicKeyReader)) {
            Object obj = pemParser.readObject();
            if (obj instanceof SubjectPublicKeyInfo keyInfo) {
                PublicKey publicKey = new JcaPEMKeyConverter().getPublicKey(keyInfo);
                return encrypt(str, publicKey);
            } else {
                AppMessages appMessages = LocaleUtil.getAppMessage(locale);
                throw new EncryptException(appMessages.encryptFailed());
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logger.error("Encryption failed", e);
            if (e instanceof EncryptException e1) {
                throw e1;
            }
            AppMessages appMessages = LocaleUtil.getAppMessage(locale);
            throw new EncryptException(appMessages.encryptFailed());
        }
    }

    private static Reader getResourceReader(String fileName) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(fileName)) {
            if (is == null) {
                logger.error("Resource {} not found", fileName);
                throw new IllegalStateException("Classpath resource not found: " + fileName);
            }
            // 一次性读尽资源并返回 StringReader：流在 try-with-resources 内确定性关闭（G.PRM.07），
            // 读取失败抛 IllegalStateException（与资源缺失同一兜底语义）
            return new StringReader(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("Failed to read resource {}", fileName, e);
            throw new IllegalStateException("Failed to read classpath resource: " + fileName, e);
        }
    }

    private PemRsaUtils() {
        throw new IllegalStateException("工具类不能实例化");
    }
}
