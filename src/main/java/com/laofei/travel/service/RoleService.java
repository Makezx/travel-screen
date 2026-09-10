package com.laofei.travel.service;

import com.laofei.travel.model.AppUser;
import com.laofei.travel.model.Permission;
import com.laofei.travel.model.Role;
import com.laofei.travel.repository.AppUserRepository;
import com.laofei.travel.repository.RoleRepository;
import com.laofei.travel.web.BadRequestException;
import com.laofei.travel.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色管理：增删改查。权限以 JSON 数组存储；删除前校验是否仍被用户引用。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepo;
    private final AppUserRepository userRepo;

    public List<Map<String, Object>> listRoles() {
        return roleRepo.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String code = str(body, "code").trim();
        if (code.isBlank()) throw new BadRequestException("角色编码不能为空");
        if (roleRepo.existsByCode(code)) throw new BadRequestException("角色编码已存在");
        Role r = new Role();
        r.setCode(code);
        String name = str(body, "name");
        r.setName(name.isBlank() ? code : name);
        r.setPermissions(permsJson(body));
        return toDto(roleRepo.save(r));
    }

    public Map<String, Object> update(Long id, Map<String, Object> body) {
        Role r = roleRepo.findById(id).orElseThrow(() -> new NotFoundException("角色不存在"));
        if (body.containsKey("name")) {
            String n = str(body, "name");
            if (!n.isBlank()) r.setName(n);
        }
        if (body.containsKey("permissions")) r.setPermissions(permsJson(body));
        return toDto(roleRepo.save(r));
    }

    public void delete(Long id) {
        Role r = roleRepo.findById(id).orElseThrow(() -> new NotFoundException("角色不存在"));
        if (userRepo.countByRoles_Id(id) > 0) throw new BadRequestException("该角色仍有用户引用，无法删除");
        roleRepo.delete(r);
    }

    private String permsJson(Map<String, Object> body) {
        Object p = body.get("permissions");
        Set<String> set = new LinkedHashSet<>();
        if (p instanceof List) {
            for (Object x : (List<?>) p) {
                String s = String.valueOf(x);
                if (Permission.valid(s)) set.add(s);
            }
        }
        return Permission.toJson(set);
    }

    private Map<String, Object> toDto(Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("permissions", new ArrayList<>(r.permissionSet()));
        return m;
    }

    static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
