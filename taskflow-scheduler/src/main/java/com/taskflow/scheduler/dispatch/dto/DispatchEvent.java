package com.taskflow.scheduler.dispatch.dto;

import java.util.UUID;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.Dispatch;

/** v1 事件标识固定为 Attempt ID；重发不改变事件或命令内容。 */
public record DispatchEvent(int version, UUID eventId, Dispatch command) { }
