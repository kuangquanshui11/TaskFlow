package com.taskflow.admin.reconciliation.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ConfigFingerprint {
    private ConfigFingerprint() { }

    // 协议保留 payloadJson 的字符串内容，快照对象的字段顺序不影响摘要。
    public static String calculate(JsonMapper json, String snapshot) {
        try {
            var data = json.readTree(snapshot);
            if (!data.isObject()) return "INVALID";
            var fields = new TreeMap<String, JsonNode>();
            data.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
            var bytes = json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (tools.jackson.core.JacksonException invalid) {
            return "INVALID";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
