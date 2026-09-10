package com.laofei.travel.web;

/** 未登录 / 会话无效（映射为 HTTP 401） */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() {
        super("unauthorized");
    }
}
