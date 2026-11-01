package com.taskflow.orders.inventory.model;

public record Stock(long skuId, long available, long reserved, long version) { }
