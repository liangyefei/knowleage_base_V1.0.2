package com.hisilicon.knowledge.platform.knowledge.facade;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * HTTP 413（请求体超限）统一 JSON 响应测试
 * <p>测试配置 {@code quarkus.http.limits.max-body-size=1M}（见 application.properties）。
 * 请求体超限在 JAX-RS 之前被 {@code Http413LoggingHandler} 提前拦截（检查 Content-Length），
 * 返回 HTTP 413 + 统一 {@code {code, message, data}}，无需鉴权（拦截发生在鉴权之前）。</p>
 * <p>注意：必须用手拼 multipart + {@code body(byte[])} 发送——RestAssured 的 multiPart API
 * 在 Apache HttpClient 下可能走 chunked（无 Content-Length 头），拦截器检查不到。</p>
 */
@QuarkusTest
class Http413JsonResponseTest {

    private static final String UPLOAD_URL = "/kb/documents/upload";
    private static final String BOUNDARY = "----hermes-test-boundary";

    private static byte[] bytesOf(long size) {
        byte[] bytes = new byte[(int) size];
        Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    /** 手拼 multipart/form-data（两个字段：files + kbId），返回带固定长度的完整 body */
    private static byte[] multipartBody(String fileName, byte[] fileBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String b = "--" + BOUNDARY + "\r\n";
        bos.write(b.getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(fileBytes);
        bos.write(("\r\n" + b).getBytes(StandardCharsets.UTF_8));
        bos.write("Content-Disposition: form-data; name=\"kbId\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write("kb-1\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return bos.toByteArray();
    }

    @Test
    void shouldReturn413JsonWhenBodyExceedsLimit() throws Exception {
        given()
                .contentType("multipart/form-data; boundary=" + BOUNDARY)
                .body(multipartBody("big.bin", bytesOf(2 * 1024 * 1024)))
                .when().post(UPLOAD_URL)
                .then()
                .statusCode(413)
                .contentType(ContentType.JSON)
                .body("code", equalTo("A0715"))
                .body("message", containsString("上限"))
                .body("data", nullValue());
    }

    @Test
    void shouldReturnEnglishMessageWithAcceptLanguage() throws Exception {
        given()
                .header("Accept-Language", "en-US,en;q=0.9")
                .contentType("multipart/form-data; boundary=" + BOUNDARY)
                .body(multipartBody("big.bin", bytesOf(2 * 1024 * 1024)))
                .when().post(UPLOAD_URL)
                .then()
                .statusCode(413)
                .contentType(ContentType.JSON)
                .body("code", equalTo("A0715"))
                .body("message", containsString("limit"));
    }

    @Test
    void shouldNotInterceptSmallBody() {
        // 1KB 远小于 1M 限制：不被 413 拦截，继续进入业务层（无 token → 鉴权失败 A0301，HTTP 200）
        given()
                .multiPart("files", "small.txt", bytesOf(1024))
                .multiPart("kbId", "kb-1")
                .when().post(UPLOAD_URL)
                .then()
                .statusCode(not(413))
                .body("code", equalTo("A0301"));
    }
}
