package com.laofei.travel.web;

/** 参数错误 / 业务校验失败（映射为 HTTP 400） */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
