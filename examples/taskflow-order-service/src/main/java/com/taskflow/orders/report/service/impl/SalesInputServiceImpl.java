package com.taskflow.orders.report.service.impl;

import com.taskflow.orders.order.mapper.OrderMapper;
import com.taskflow.orders.report.dto.SalesInput.*;
import com.taskflow.orders.report.mapper.SalesInputMapper;
import com.taskflow.orders.report.mapper.SalesMapper;
import com.taskflow.orders.report.service.ISalesInputService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SalesInputServiceImpl implements ISalesInputService {
    private final SalesInputMapper inputs;
    private final SalesMapper sales;
    private final OrderMapper orders;

    public SalesInputServiceImpl(SalesInputMapper inputs, SalesMapper sales, OrderMapper orders) {
        this.inputs = inputs;
        this.sales = sales;
        this.orders = orders;
    }

    @Override
    public Shop insertShop(Shop shop) {
        try {
            inputs.insertShop(shop);
        } catch (DuplicateKeyException duplicate) {
            if (!shop.equals(inputs.shop(shop.shopId()))) throw conflict("SHOP_CONFLICT");
        }
        return shop;
    }

    @Override
    @Transactional
    public CatalogProduct updateProduct(CatalogProduct product) {
        lockShop(product.shopId());
        var saved = inputs.product(product.skuId());
        if (saved == null) {
            inputs.insertProduct(product);
        } else if (!saved.equals(product)) {
            if (saved.shopId() != product.shopId() || !product.catalogSource().equals(saved.catalogSource())
                    || product.catalogVersion() <= saved.catalogVersion()) throw conflict("CATALOG_CONFLICT");
            inputs.updateProduct(product);
        }
        return product;
    }

    @Override
    @Transactional
    public Backfill insertBackfill(Backfill input) {
        lockShop(input.shopId());
        var order = orders.lock(input.orderId());
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        if (!"PAID".equals(order.state()) || order.paidAt() == null) throw conflict("ORDER_NOT_PAID");
        var saved = inputs.snapshot(order.id());
        if (saved != null) {
            if (!saved.equals(input)) throw conflict("SNAPSHOT_CONFLICT");
            return saved;
        }
        var product = sales.product(order.skuId());
        if (product != null && product.shopId() != input.shopId()) throw conflict("CATALOG_SHOP_MISMATCH");
        if (input.unitPriceCents() > Long.MAX_VALUE / order.quantity())
            throw new IllegalArgumentException("Sale amount exceeds supported range");
        inputs.insertSnapshot(input, order.skuId(), input.unitPriceCents() * order.quantity());
        return input;
    }

    private void lockShop(long shopId) {
        if (sales.lockShop(shopId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND");
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
