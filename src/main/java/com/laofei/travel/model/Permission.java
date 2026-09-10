package com.laofei.travel.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限码常量与 JSON 序列化工具。
 * 前后端共用同一套权限码：VIEW_SCREEN / MANAGE_TRIP / MANAGE_USER / MANAGE_ROLE / MANAGE_TEAM。
 */
public final class Permission {

    public static final String VIEW_SCREEN = "VIEW_SCREEN";
    public static final String MANAGE_TRIP = "MANAGE_TRIP";
    public static final String MANAGE_USER = "MANAGE_USER";
    public static final String MANAGE_ROLE = "MANAGE_ROLE";
    public static final String MANAGE_TEAM = "MANAGE_TEAM";

    public static final Set<String> ALL =
            Set.of(VIEW_SCREEN, MANAGE_TRIP, MANAGE_USER, MANAGE_ROLE, MANAGE_TEAM);

    private Permission() {
    }

    /** 是否为合法权限码 */
    public static boolean valid(String p) {
        return p != null && ALL.contains(p);
    }

    /** 权限码集合 -> JSON 数组字符串（如 ["VIEW_SCREEN","MANAGE_TRIP"]） */
    public static String toJson(java.util.Collection<String> perms) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (String p : perms) {
            if (p == null || p.isEmpty()) continue;
            if (i++ > 0) sb.append(',');
            sb.append('"').append(p).append('"');
        }
        return sb.append("]").toString();
    }

    /** JSON 数组字符串 -> 权限码集合（容错解析，忽略空白/引号） */
    public static Set<String> fromJson(String json) {
        Set<String> s = new LinkedHashSet<>();
        if (json == null || json.isBlank()) return s;
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        for (String part : inner.split(",")) {
            part = part.trim().replace("\"", "").replace("'", "").trim();
            if (!part.isEmpty()) s.add(part);
        }
        return s;
    }

    /** 预置角色 -> 默认权限集合 */
    public static Set<String> defaultsFor(String code) {
        return switch (code) {
            case "SUPER_ADMIN" -> new LinkedHashSet<>(ALL);
            case "ADMIN" -> new LinkedHashSet<>(
                    Arrays.asList(VIEW_SCREEN, MANAGE_TRIP, MANAGE_USER, MANAGE_TEAM));
            case "EDITOR" -> new LinkedHashSet<>(Arrays.asList(VIEW_SCREEN, MANAGE_TRIP));
            case "VIEWER" -> new LinkedHashSet<>(Set.of(VIEW_SCREEN));
            default -> new LinkedHashSet<>();
        };
    }
}
