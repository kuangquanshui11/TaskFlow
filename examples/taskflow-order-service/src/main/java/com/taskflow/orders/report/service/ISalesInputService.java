package com.taskflow.orders.report.service;

import com.taskflow.orders.report.dto.SalesInput.*;

public interface ISalesInputService {
    Shop insertShop(Shop shop);
    CatalogProduct updateProduct(CatalogProduct product);
    Backfill insertBackfill(Backfill input);
}
