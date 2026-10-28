package com.taskflow.scheduler.node.model;

/** 一次派发绑定的身份和路由；发布重试不得重新选择。 */
public record DispatchTarget(String workerId, String vhost, String exchange, String queue, String routingKey) { }
