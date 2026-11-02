package com.taskflow.orders;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.taskflow.orders.report.dto.SalesProtocol.*;
import com.taskflow.orders.report.mapper.SalesMapper;
import com.taskflow.orders.report.service.ISalesReportService;
import com.taskflow.orders.sample.mapper.SalesSampleMapper;
import com.taskflow.orders.sample.service.ISalesSampleService;
import com.taskflow.orders.order.dto.OrderProtocol.Create;
import com.taskflow.orders.order.service.IOrderService;
import com.taskflow.orders.inventory.service.IInventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SalesReportTests {
    @Autowired ISalesReportService reports;
    @Autowired ISalesSampleService seeder;
    @Autowired SalesSampleMapper samples;
    @MockitoSpyBean SalesMapper sales;
    @Autowired IOrderService orders;
    @Autowired IInventoryService inventory;
    @Autowired JdbcTemplate sql;
    @MockitoBean(name="salesClock") Clock clock;
    private final java.util.concurrent.atomic.AtomicReference<Instant> now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
    @BeforeEach void time() {
        when(clock.instant()).thenAnswer(call->now.get());
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(now.get(),call.getArgument(0)));
    }
    private static final AtomicLong ids=new AtomicLong(700000);
    private final LocalDate date=LocalDate.of(2026,10,1);
    long shop() { long id=ids.incrementAndGet(); samples.shop(id,"测试店铺"); return id; }
    long product(long shop,long cents,String name) {
        long id=ids.incrementAndGet(); samples.product(new Product(id,shop,name,cents)); inventory.insert(id,100); return id;
    }
    void paid(long sku,int quantity,Instant at) {
        var order=orders.insert(new Create(UUID.randomUUID().toString(),sku,quantity));
        orders.updatePayment(order.id());
        samples.dates(order.id(),at.minusSeconds(60),at.plusSeconds(1800),at,null);
    }
    Generate request(long shop,LocalDate day) { return new Generate(UUID.randomUUID().toString(),shop,day,"Asia/Shanghai"); }
    @Test void paidWindowShopAmountAndCsvAreExact() {
        long shop=shop(),other=shop();
        long a=product(shop,10000,"测试商品 A"), b=product(shop,20000,"=SUM(1,2)"), c=product(other,90000,"其他店铺");
        var start=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        paid(a,1,start); paid(b,1,start.plusSeconds(86399)); paid(a,3,start.plusSeconds(86400));
        paid(c,1,start.plusSeconds(100));
        orders.insert(new Create(UUID.randomUUID().toString(),a,1)); // 未付款不计入。
        var input=request(shop,date);
        var report=reports.insert(input);
        assertThat(report.orderCount()).isEqualTo(2);
        assertThat(report.totalQuantity()).isEqualTo(2);
        assertThat(report.totalAmountCents()).isEqualTo(30000);
        assertThat(reports.selectById(report.id()).items()).hasSize(2);
        assertThat(reports.selectExport(report.id()).content()).contains("30000","'=SUM(1,2)");
        assertThat(reports.insert(input).id()).isEqualTo(report.id());
        assertThat(reports.insert(request(shop,date)).id()).isEqualTo(report.id());
        assertThatThrownBy(()->reports.insert(new Generate(input.requestKey(),other,date,input.timeZone())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(reports.select(shop,1,20).total()).isEqualTo(1);
    }
    @Test void zeroDayAndFirstAcceptedDatePersistAcrossRetries() {
        long shop=shop();
        var input=request(shop,null);
        var report=reports.insert(input);
        assertThat(report.orderCount()).isZero();
        assertThat(report.totalAmountCents()).isZero();
        assertThat(report.businessDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
        assertThat(sales.request(input.requestKey()).businessDate()).isEqualTo(report.businessDate());
        now.set(now.get().plus(Duration.ofDays(2)));
        assertThat(reports.insert(input)).isEqualTo(report);
        now.set(Instant.now());
        assertThatThrownBy(()->reports.insert(request(shop,LocalDate.now(ZoneId.of("Asia/Shanghai"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->reports.select(null,0,20)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentRunsAndLostResponseProduceOneSnapshot() throws Exception {
        long shop=shop(),sku=product(shop,1250,"并发商品");
        paid(sku,4,date.atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        try(var pool=Executors.newFixedThreadPool(6)) {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=0;i<12;i++) tasks.add(()->reports.insert(request(shop,date)).id());
            var results=pool.invokeAll(tasks);
            var distinct=new HashSet<String>();
            for(var result:results) distinct.add(result.get(15,TimeUnit.SECONDS));
            assertThat(distinct).hasSize(1);
        }
        assertThat(reports.select(shop,1,20).items().getFirst().totalAmountCents()).isEqualTo(5000);
    }
    @Test void itemFailureRollsBackHeaderAndRetryUsesStoredDate() {
        long shop=shop(),sku=product(shop,1234,"回滚商品");
        paid(sku,2,date.atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        var input=request(shop,date);
        doThrow(new IllegalStateException("injected")).when(sales).insertItem(anyString(),any(Item.class));
        try {
            assertThatThrownBy(()->reports.insert(input)).isInstanceOf(IllegalStateException.class);
            assertThat(sales.byScope(shop,date,"Asia/Shanghai")).isNull();
            assertThat(sales.request(input.requestKey())).isNotNull();
        } finally { reset(sales); }
        assertThat(reports.insert(input).totalAmountCents()).isEqualTo(2468);
    }
    @Test void sampleImportIsRepeatableAndStockLedgerConsistent() {
        var input=new Sample("sales-tests",date,3,80,20261102);
        assertThat(seeder.insert(input)).isEqualTo(input);
        var before=sql.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_key LIKE 'sales-sample:sales-tests:%'",Long.class);
        assertThat(seeder.insert(input)).isEqualTo(input);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_key LIKE 'sales-sample:sales-tests:%'",Long.class)).isEqualTo(before);
        assertThat(before).isEqualTo(80);
        assertThat(sql.queryForObject("""
                SELECT COUNT(*) FROM purchase_order o JOIN stock_reservation r ON o.id=r.order_id
                WHERE o.request_key LIKE 'sales-sample:sales-tests:%' AND
                ((o.state='PAID' AND r.state<>'CONSUMED') OR (o.state='CLOSED' AND r.state<>'RELEASED')
                 OR (o.state='PENDING' AND r.state<>'HELD'))
                """,Long.class)).isZero();
        assertThatThrownBy(()->seeder.insert(new Sample(input.dataset(),date,3,81,input.seed())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void salesSnapshotFailureRollsBackOrderAndStock() {
        long shop=shop(),sku=product(shop,300,"原子创建");
        String key=UUID.randomUUID().toString();
        doThrow(new IllegalStateException("injected snapshot failure")).when(sales).snapshot(anyString(),any(Product.class),anyLong());
        try {
            assertThatThrownBy(()->orders.insert(new Create(key,sku,2))).isInstanceOf(IllegalStateException.class);
            assertThat(inventory.select(sku).available()).isEqualTo(100);
            assertThat(inventory.select(sku).reserved()).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_key=?",Long.class,key)).isZero();
        } finally { reset(sales); }
    }
    @Test void daylightSavingDayUsesCalendarBoundariesInsteadOfTwentyFourHours() {
        long shop=shop(),sku=product(shop,100,"夏令时商品");
        var day=LocalDate.of(2026,10,25);
        var zone=ZoneId.of("Europe/Berlin");
        var start=day.atStartOfDay(zone).toInstant();
        paid(sku,1,start.plusSeconds(24*3600+1800));
        paid(sku,1,day.plusDays(1).atStartOfDay(zone).toInstant());
        var report=reports.insert(new Generate(UUID.randomUUID().toString(),shop,day,zone.getId()));
        assertThat(report.orderCount()).isEqualTo(1);
    }
}
