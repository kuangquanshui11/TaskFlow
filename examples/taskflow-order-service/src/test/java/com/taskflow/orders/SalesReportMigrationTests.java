package com.taskflow.orders;

import java.time.LocalDate;
import java.util.UUID;
import com.taskflow.orders.report.dto.SalesProtocol.Generate;
import com.taskflow.orders.report.dto.SalesProtocol.Item;
import com.taskflow.orders.report.dto.SalesProtocol.Revise;
import com.taskflow.orders.report.service.ISalesReportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="spring.flyway.target=5")
@ActiveProfiles("test")
class SalesReportMigrationTests {
    @Autowired JdbcTemplate sql;
    @Autowired ISalesReportService reports;

    @Test void migrationPreservesOriginalReportIdItemsAndRequestResult() {
        String id=UUID.randomUUID().toString();
        var day=LocalDate.of(2026,10,1);
        sql.update("INSERT INTO sales_shop(id,name) VALUES(8,'Legacy')");
        sql.update("""
                INSERT INTO sales_report(id,shop_id,business_date,time_zone,order_count,total_quantity,total_amount_cents,generated_at)
                VALUES(?,8,?,'Asia/Shanghai',1,2,300,CURRENT_TIMESTAMP)
                """,id,day);
        sql.update("""
                INSERT INTO sales_report_item(report_id,sku_id,product_name,order_count,total_quantity,total_amount_cents)
                VALUES(?,1,'Original name',1,2,300)
                """,id);
        sql.update("""
                INSERT INTO sales_report_request(request_key,shop_id,requested_date,business_date,time_zone)
                VALUES('old-run',8,NULL,?,'Asia/Shanghai')
                """,day);

        Flyway.configure().dataSource(sql.getDataSource()).locations("classpath:db/migration").load().migrate();

        var original=reports.selectById(id);
        assertThat(original.report().revision()).isEqualTo(1);
        assertThat(original.items()).containsExactly(new Item(1,"Original name",1,2,300));
        var revised=reports.update(new Revise("recount",8,day,"Asia/Shanghai","Migration fixture"));
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(reports.insert(new Generate("old-run",8,null,"Asia/Shanghai"))).isEqualTo(original.report());
        assertThat(reports.selectById(id)).isEqualTo(original);
    }
}
