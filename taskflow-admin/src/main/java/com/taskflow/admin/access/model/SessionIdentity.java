package com.taskflow.admin.access.model;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.security.oauth2.jwt.Jwt;
import com.taskflow.admin.access.exception.AccessSessionException;

public final class SessionIdentity {
    private SessionIdentity() {}
    public static String key(Jwt jwt, String prefix) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null || sid.isBlank()) sid = jwt.getClaimAsString("session_state");
        if (sid == null || sid.isBlank()) throw new AccessSessionException(401, "SESSION_EXPIRED", "登录会话缺失，请重新登录");
        try {
            String identity = jwt.getIssuer() + "\0" + jwt.getSubject() + "\0" + sid;
            return prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
