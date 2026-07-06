package com.eventledger.gateway.exception;

public class DownstreamServiceUnavailableException extends RuntimeException {
    public DownstreamServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
