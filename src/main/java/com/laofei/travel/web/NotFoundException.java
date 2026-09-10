package com.laofei.travel.web;

/** 资源不存在（映射为 HTTP 404） */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
