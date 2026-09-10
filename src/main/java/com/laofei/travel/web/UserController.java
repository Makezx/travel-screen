package com.laofei.travel.web;

import com.laofei.travel.model.Permission;
import com.laofei.travel.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userSvc;
    private final SecuritySupport sec;

    public UserController(UserService userSvc, SecuritySupport sec) {
        this.userSvc = userSvc;
        this.sec = sec;
    }

    @GetMapping("/users")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    @RequestParam(required = false) String q) {
        sec.require(Permission.MANAGE_USER);
        return userSvc.listUsers(page, size, q);
    }

    @PostMapping("/users")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_USER);
        return userSvc.create(body);
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_USER);
        return userSvc.update(id, body);
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        sec.require(Permission.MANAGE_USER);
        userSvc.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/roles")
    public Map<String, Object> setRoles(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_USER);
        return userSvc.setRoles(id, ids(body.get("roleIds")));
    }

    @PostMapping("/users/{id}/teams")
    public Map<String, Object> setTeams(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_USER);
        return userSvc.setTeams(id, ids(body.get("teamIds")));
    }

    @PostMapping("/users/batch-create-from-trips")
    public Map<String, Object> batchCreate() {
        sec.require(Permission.MANAGE_USER);
        return userSvc.batchCreateFromTrips();
    }

    @SuppressWarnings("unchecked")
    private List<Long> ids(Object o) {
        List<Long> r = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                if (x instanceof Number) r.add(((Number) x).longValue());
                else if (x != null) {
                    try {
                        r.add(Long.parseLong(x.toString()));
                    } catch (NumberFormatException ignored) {
                        // 忽略非法 id
                    }
                }
            }
        }
        return r;
    }
}
