import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 仅准备本机订单示例的独立 schema、账号和忽略配置，不导入业务样例。 */
public class ProvisionLocalSales extends ProvisionLocalExecution {
    public static void main(String[] args) throws Exception {
        Path ownFile=ROOT.resolve("config/taskflow-order-local.yml");
        Path communication=ROOT.resolve("config/taskflow-sales-local.yml");
        if(Files.exists(ownFile) && Files.exists(communication)) {
            System.out.println("Existing local sales configuration preserved.");
            return;
        }
        if(Files.exists(ownFile) || Files.exists(communication))
            throw new IllegalStateException("Partial local sales setup: reconcile existing configuration before continuing.");
        var local=load(ROOT.resolve("config/taskflow-admin-local.yml"));
        var main=load(ROOT.resolve("taskflow-admin/src/main/resources/application.yml"));
        String url=resolve(Optional.ofNullable(value(local,"spring","datasource","url")).orElse(value(main,"spring","datasource","url")));
        var address=java.net.URI.create(url.substring("jdbc:".length()));
        if(!Set.of("localhost","127.0.0.1").contains(address.getHost()) || !"/taskflow_admin".equals(address.getPath()))
            throw new IllegalStateException("Only the configured local Admin management connection is supported.");
        String username=resolve(value(local,"spring","datasource","username")), password=resolve(value(local,"spring","datasource","password"));
        String account="tf_sales_"+random(4), secret=random(24), apiToken=random(32), workerToken=random(32);
        try(var connection=DriverManager.getConnection(url,username,password);var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS taskflow_order CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='taskflow_order'")) {
                rows.next(); if(rows.getInt(1)!=0) throw new IllegalStateException("Existing nonempty order schema is not adopted automatically.");
            }
            for(String host:List.of("localhost","127.0.0.1")) {
                statement.execute("CREATE USER '"+account+"'@'"+host+"' IDENTIFIED BY '"+secret+"'");
                statement.execute("GRANT ALL PRIVILEGES ON taskflow_order.* TO '"+account+"'@'"+host+"'");
            }
        }
        String ownUrl=url.replaceFirst("/taskflow_admin(?=\\?|$)","/taskflow_order");
        write(ownFile,"spring:\n  datasource:\n    url: \""+ownUrl+"\"\n    username: \""+account+"\"\n    password: \""+secret+"\"\n"
                +"order:\n  api-token: \""+apiToken+"\"\n  worker-token: \""+workerToken+"\"\n  samples-enabled: true\n  cache-enabled: false\n");
        write(communication,"taskflow:\n  sales:\n    enabled: true\n    token: \""+workerToken+"\"\n"
                +"  order:\n    token: \""+workerToken+"\"\n");
        System.out.println("Prepared isolated order schema/account and local sales integration. Credentials remain in ignored YAML.");
    }
}
