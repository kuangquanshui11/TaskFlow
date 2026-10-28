package com.taskflow.gateway.support;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;

/** Publicly committed test-only keys. Never trust these keys in a deployed environment. */
public final class TestTokens {
    private TestTokens() {}
    public static String admin() { return token(Map.of()); }
    public static String token(Map<String, Object> overrides) {
        try {
            var claims = new HashMap<String, Object>();
            claims.put("iss", "https://identity.test/realms/taskflow");
            claims.put("sub", "test-admin");
            claims.put("aud", List.of("taskflow-api"));
            claims.put("preferred_username", "test-admin");
            claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
            claims.put("nbf", Date.from(Instant.now().minusSeconds(10)));
            claims.put("resource_access", Map.of("taskflow-api", Map.of("roles", List.of("ADMIN"))));
            claims.putAll(overrides);
            var builder = new JWTClaimsSet.Builder();
            claims.forEach(builder::claim);
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-only").build(), builder.build());
            try (var input = TestTokens.class.getResourceAsStream("/access/test-private.pem")) {
                String pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
                byte[] bytes = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
                var key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
                jwt.sign(new RSASSASigner(key));
            }
            return jwt.serialize();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
