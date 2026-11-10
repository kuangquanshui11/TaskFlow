package com.taskflow.orders.payment.service;

import com.taskflow.orders.payment.dto.PaymentProtocol.Release;

public interface IInventoryReleaseClient {
    void release(Release release);
}
