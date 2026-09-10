package com.laofei.travel.web;

/** 已登录但无权限（映射为 HTTP 403） */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("forbidden");
    }

    public ForbiddenException(String message) {
        super(message);
    }
}
