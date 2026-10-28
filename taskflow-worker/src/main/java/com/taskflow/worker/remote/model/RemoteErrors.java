package com.taskflow.worker.remote.model;
import java.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
public final class RemoteErrors {
    private RemoteErrors() { }
    public static int status(Throwable error) {
        for (int i=0; error!=null && i<16; i++,error=error.getCause())
            if (error instanceof feign.FeignException remote && remote.status()>=400) return remote.status();
        return 503;
    }
    public static Map<String,String> headers(String token,String fallbackId) {
        var id=org.slf4j.MDC.get("requestId");
        return Map.of("X-Taskflow-Token",token,"X-Request-Id",id==null?fallbackId:id);
    }
    public static Map<String,String> query(String path) {
        var query=URI.create(path).getRawQuery(); var result=new LinkedHashMap<String,String>();
        if (query!=null) for (String pair:query.split("&")) {
            var parts=pair.split("=",2);
            result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length==1?"":URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }
        return result;
    }
    public static String action(String path) {
        var value=URI.create(path).getPath();
        return value.substring(value.lastIndexOf('/')+1);
    }
}
