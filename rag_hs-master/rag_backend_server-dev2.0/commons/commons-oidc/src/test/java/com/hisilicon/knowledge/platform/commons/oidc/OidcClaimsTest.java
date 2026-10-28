package com.hisilicon.knowledge.platform.commons.oidc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link OidcClaims} 常量与 OIDC 标准 claim 名一致性测试。 */
class OidcClaimsTest {

    private static final String EXPECTED_SUB = "sub";
    private static final String EXPECTED_PREFERRED_USERNAME = "preferred_username";
    private static final String EXPECTED_NAME = "name";
    private static final String EXPECTED_EMAIL = "email";
    private static final String EXPECTED_PHONE = "phone";
    private static final String EXPECTED_PICTURE = "picture";

    @Test
    void claimConstantsMatchOidcStandardNames() {
        assertEquals(EXPECTED_SUB, OidcClaims.SUB);
        assertEquals(EXPECTED_PREFERRED_USERNAME, OidcClaims.PREFERRED_USERNAME);
        assertEquals(EXPECTED_NAME, OidcClaims.NAME);
        assertEquals(EXPECTED_EMAIL, OidcClaims.EMAIL);
        assertEquals(EXPECTED_PHONE, OidcClaims.PHONE);
        assertEquals(EXPECTED_PICTURE, OidcClaims.PICTURE);
    }
}
