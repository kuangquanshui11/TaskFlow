package com.taskflow.admin.sales.exception;
public class SalesReportException extends RuntimeException {
    private final int status;
    public SalesReportException(int status,String message) { super(message); this.status=status; }
    public int status() { return status; }
}
