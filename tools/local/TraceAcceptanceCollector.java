import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.UUID;

/** 隔离验收使用的 OTLP HTTP 接收端，不是生产追踪后端。 */
public class TraceAcceptanceCollector {
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]);Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/health",exchange->{
            byte[] body="{\"status\":\"UP\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/v1/traces",exchange->{
            try {
                if(!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405,-1);return; }
                var input=exchange.getRequestBody();
                if("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) input=new java.util.zip.GZIPInputStream(input);
                byte[] bytes=input.readNBytes(4194305);
                if(bytes.length>4194304) { exchange.sendResponseHeaders(413,-1);return; }
                Files.write(output.resolve(UUID.randomUUID()+".otlp.bin"),bytes,StandardOpenOption.CREATE_NEW);
                exchange.getResponseHeaders().set("Content-Type","application/x-protobuf");exchange.sendResponseHeaders(200,-1);
            } finally { exchange.close(); }
        });
        server.start();System.out.println("OTLP acceptance collector ready on loopback.");
    }
}
