package com.laofei.travel.web;

import com.laofei.travel.model.Permission;
import com.laofei.travel.service.RoleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RoleController {

    private final RoleService roleSvc;
    private final SecuritySupport sec;

    public RoleController(RoleService roleSvc, SecuritySupport sec) {
        this.roleSvc = roleSvc;
        this.sec = sec;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> list() {
        sec.require(Permission.MANAGE_ROLE);
        return roleSvc.listRoles();
    }

    @PostMapping("/roles")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_ROLE);
        return roleSvc.create(body);
    }

    @PutMapping("/roles/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_ROLE);
        return roleSvc.update(id, body);
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        sec.require(Permission.MANAGE_ROLE);
        roleSvc.delete(id);
        return Map.of("ok", true);
    }
}
