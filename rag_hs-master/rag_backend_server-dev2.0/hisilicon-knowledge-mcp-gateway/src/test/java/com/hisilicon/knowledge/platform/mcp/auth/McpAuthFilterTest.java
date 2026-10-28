package com.hisilicon.knowledge.platform.mcp.auth;

import com.hisilicon.knowledge.platform.mcp.registry.ToolRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MCP 入站鉴权语义测试（HTTP 层，覆盖 {@link McpAuthFilter} 的校验序）。
 *
 * <p>口径：用裸 REST 调 {@code /mcp}（JSON-RPC initialize）与 {@code /gateway/status}，
 * 断言 401/403/非 401 边界；协议语义（tools/list 等）已由 {@code McpProtocolEndpointTest}
 * 在关鉴权形态覆盖，这里不重复。</p>
 */
@QuarkusTest
@TestProfile(McpAuthTestProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpAuthFilterTest {

    /**
     * ⚠️ 用**相对**路径：RestAssured 的 basePath 已含 {@code quarkus.http.root-path}
     * （/hisilicon-knowledge-mcp），写绝对路径会变成双前缀 → 614 404/401，
     * 表现为"filter 不生效"（实测踩过，排查花了很久）。
     */
    private static final String MCP_ENDPOINT = "/mcp";

    @Inject
    ToolRegistry registry;

    @Inject
    MemoryMcpUsageStore store;

    private static String initializeBody() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18","capabilities":{},
                  "clientInfo":{"name":"auth-test","version":"0"}}}""";
    }

    /**
     * 未带 Authorization → 401（fail-closed：无 token 不能用 MCP）。
     *
     * <p>断言用稳定错误码而非文案：文案已国际化（{@link McpAuthMessages}），
     * 拿中文/英文原文做断言会让用例随语言切换而碎。</p>
     */
    @Test
    void missingTokenIsRejected() {
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(401)
                .body(containsString("MCP_TOKEN_MISSING"))
                .body(containsString("Bearer"))
                .header("WWW-Authenticate", containsString("error=\"invalid_token\""));
    }

    /** 篡改 / 伪造 token（验签失败）→ 401 */
    @Test
    void forgedTokenIsRejected() {
        String forged = McpAuthTestProfile.mintToken("alice", "j-forged", null);
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + forged + "x")
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(401);
    }

    /**
     * 过期 token → 401，且错误码与"令牌无效"可区分（用户据此知道只要重新签发即可）。
     */
    @Test
    void expiredTokenIsRejected() {
        String expired = McpAuthTestProfile.mintExpiredToken("alice", "j-expired");
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Accept-Language", "en-US")
                .header("Authorization", "Bearer " + expired)
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(401)
                .body(containsString("MCP_TOKEN_EXPIRED"))
                // 英文文案里要点明"重新签发"，否则用户只看到 expired 无从下手
                .body(containsString("expired"));
    }

    /** type 不符（access 而非 mcp）→ 401：浏览器 access token 不能拿来当 MCP 长效凭证 */
    @Test
    void wrongTypeTokenIsRejected() {
        // 现签一个 type=access 的 token（模拟平台登录态）
        String access = mintWithType("alice", "j-access", "access");
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + access)
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(401)
                .body(containsString("MCP_TOKEN_TYPE_MISMATCH"));
    }

    private static String mintWithType(String username, String jti, String type) {
        try {
            var claims = new org.jose4j.jwt.JwtClaims();
            claims.setIssuer("hisilicon-knowledge-platform");
            claims.setSubject(username);
            claims.setJwtId(jti);
            claims.setExpirationTimeMinutesInTheFuture(60);
            claims.setClaim("type", type);
            var jws = new org.jose4j.jws.JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setAlgorithmHeaderValue("RS256");
            jws.setKey(McpAuthTestProfile.privateKey());
            return jws.getCompactSerialization();
        } catch (org.jose4j.lang.JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 有效 type=mcp token → 通过鉴权（进入 MCP 协议层，不再是 401） */
    @Test
    void validMcpTokenPassesAuth() {
        String token = McpAuthTestProfile.mintToken("alice", "j-valid", null);
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(200);
    }

    /** 已吊销 token → 401（即时吊销语义） */
    @Test
    void revokedTokenIsRejected() {
        String token = McpAuthTestProfile.mintToken("bob", "j-revoked", null);
        store.revoke("j-revoked");
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(401)
                .body(containsString("MCP_TOKEN_REVOKED"));
    }

    /** 管理面：非 sys_admin → 403 */
    @Test
    void adminEndpointRejectsNonAdmin() {
        String token = McpAuthTestProfile.mintToken("carol", "j-user", "user");
        given().header("Authorization", "Bearer " + token)
                .when().get("/gateway/status")
                .then().statusCode(403);
    }

    /** 管理面：sys_admin → 200 */
    @Test
    void adminEndpointAllowsSysAdmin() {
        String token = McpAuthTestProfile.mintToken("root", "j-admin", "sys_admin,user");
        given().header("Authorization", "Bearer " + token)
                .when().get("/gateway/status")
                .then().statusCode(200);
    }

    /**
     * 超限：账号日调用次数到 0 → tools/call 不再转发，回**工具级** error
     * （文案含 quota exhausted；JSON-RPC 层不是 -32xxx 协议错误）。
     */
    @Test
    void overQuotaToolCallReturnsToolLevelError() throws Exception {
        registry.refresh().await().indefinitely();
        String token = McpAuthTestProfile.mintToken("dave", "j-quota", null);
        store.setQuota("dave", 0, Long.MAX_VALUE);

        // Streamable HTTP：先 initialize 拿会话 id
        String session = given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(200)
                .extract().header("mcp-session-id");

        String callBody = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"stub-alpha__alpha_only","arguments":{"query":"x"}}}""";
        String body = given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Accept-Language", "en-US")
                .header("Authorization", "Bearer " + token)
                .header("mcp-session-id", session)
                .body(callBody)
                .when().post(MCP_ENDPOINT)
                .then().statusCode(200)
                .extract().asString();

        // 工具级 error：isError=true + 本地化文案，而不是 JSON-RPC 协议错误码
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("quota"),
                "超限文案应在响应体中，实际: " + body);
        // 文案必须带上限值，否则用户不知道到底卡在什么额度上
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("daily limit"),
                "超限文案应含上限值，实际: " + body);
    }

    /** 计量：合法调用后 usage 累加（calls + chars）——内部状态直接断言 memory store */
    @Test
    void usageIsRecordedAfterSuccessfulCall() throws Exception {
        registry.refresh().await().indefinitely();
        String token = McpAuthTestProfile.mintToken("erin", "j-usage", null);

        McpUsageStore.UsageSnapshot before = store.usage("erin", 2000, 20_000_000)
                .await().indefinitely();
        assertEquals(0, before.calls());

        String session = given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .body(initializeBody())
                .when().post(MCP_ENDPOINT)
                .then().statusCode(200)
                .extract().header("mcp-session-id");

        String callBody = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"stub-alpha__alpha_only","arguments":{"query":"x"}}}""";
        given().header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .header("mcp-session-id", session)
                .body(callBody)
                .when().post(MCP_ENDPOINT)
                .then().statusCode(200);

        McpUsageStore.UsageSnapshot after = store.usage("erin", 2000, 20_000_000)
                .await().indefinitely();
        org.junit.jupiter.api.Assertions.assertTrue(after.calls() >= 1,
                "成功调用后 calls 应至少 +1，实际: " + after.calls());
        org.junit.jupiter.api.Assertions.assertTrue(after.chars() > 0,
                "成功调用后 chars 应大于 0，实际: " + after.chars());
    }
}
