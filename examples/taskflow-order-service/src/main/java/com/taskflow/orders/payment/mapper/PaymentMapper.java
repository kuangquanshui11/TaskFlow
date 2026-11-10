package com.taskflow.orders.payment.mapper;

import java.time.Instant;
import java.util.List;
import com.taskflow.orders.payment.model.PaymentOrder;
import com.taskflow.orders.payment.model.PaymentPrice;
import com.taskflow.orders.payment.model.PaymentWork;
import com.taskflow.orders.payment.model.PaymentWork.Kind;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PaymentMapper {
    @Select("SELECT * FROM order_payment WHERE order_id=#{id}")
    PaymentOrder find(String id);

    @Select("SELECT * FROM order_payment WHERE order_id=#{id} FOR UPDATE")
    PaymentOrder lock(String id);

    @Select("SELECT product_name description,total_amount_cents amount_cents FROM order_sale_snapshot WHERE order_id=#{orderId}")
    PaymentPrice price(String orderId);

    @Select("SELECT order_id FROM order_payment WHERE trade_no=#{tradeNo}")
    String orderId(String tradeNo);

    @Insert("""
        INSERT INTO order_payment(order_id,trade_no,app_id,merchant_id,description,amount_cents,payment_state)
        VALUES(#{orderId},#{tradeNo},#{appId},#{merchantId},#{description},#{amountCents},'PENDING')
        """)
    void insert(PaymentOrder payment);

    @Update("UPDATE order_payment SET code_url=#{url} WHERE order_id=#{id} AND payment_state='PENDING'")
    void saveCodeUrl(String id, String url);

    @Update("""
        UPDATE order_payment SET payment_state=#{state},code_url=NULL,transaction_id=#{transactionId},
            paid_at=#{paidAt},refund_state=#{refundState} WHERE order_id=#{id}
        """)
    void recordPayment(String id, String state, String transactionId, Instant paidAt, String refundState);

    @Update("UPDATE order_payment SET payment_state='CLOSED',code_url=NULL WHERE order_id=#{id} AND payment_state='PENDING'")
    void closePayment(String id);

    @Update("UPDATE order_payment SET refund_state=#{state},refund_id=#{refundId} WHERE order_id=#{id}")
    void recordRefund(String id, String state, String refundId);

    @Insert("""
        INSERT INTO order_payment_outbox(order_id,kind,state,attempts,next_at,stop_at)
        VALUES(#{id},#{kind},'READY',0,#{now},#{stopAt})
        """)
    void enqueue(String id, Kind kind, Instant now, Instant stopAt);

    @Select("SELECT * FROM order_payment_outbox WHERE order_id=#{id} AND kind=#{kind}")
    PaymentWork work(String id, Kind kind);

    @Select("""
        SELECT * FROM order_payment_outbox WHERE kind=#{kind} AND state='READY' AND next_at<=#{now}
        ORDER BY next_at,order_id LIMIT #{limit}
        """)
    List<PaymentWork> due(Kind kind, Instant now, int limit);

    @Update("""
        UPDATE order_payment_outbox SET claim_token=#{token}, next_at=#{until}, attempts=attempts+1
        WHERE order_id=#{work.orderId} AND kind=#{work.kind} AND state='READY' AND next_at<=#{now}
            AND attempts=#{work.attempts}
        """)
    int claim(PaymentWork work, String token, Instant now, Instant until);

    @Update("""
        UPDATE order_payment_outbox SET state=#{state},attempts=#{attempts},next_at=#{nextAt},
            last_error=#{error},claim_token=NULL
        WHERE order_id=#{work.orderId} AND kind=#{work.kind} AND claim_token=#{token}
        """)
    int finish(PaymentWork work, String token, String state, int attempts, Instant nextAt, String error);

    @Update("""
        UPDATE order_payment_outbox SET state='DONE',claim_token=NULL,last_error=NULL
        WHERE order_id=#{id} AND kind='PAYMENT'
        """)
    void completePayment(String id);

    @Update("""
        UPDATE order_payment_outbox SET state='READY',attempts=0,next_at=#{now},stop_at=#{stopAt},last_error=NULL
        WHERE order_id=#{id} AND kind=#{kind} AND state='REVIEW'
            AND (last_error IS NULL OR last_error NOT IN ('REFUND_CLOSED','REFUND_ABNORMAL','REFUND_MISMATCH'))
        """)
    int retry(String id, Kind kind, Instant now, Instant stopAt);
}
