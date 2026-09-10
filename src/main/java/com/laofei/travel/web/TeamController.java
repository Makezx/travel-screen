package com.laofei.travel.web;

import com.laofei.travel.model.Permission;
import com.laofei.travel.service.TeamService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TeamController {

    private final TeamService teamSvc;
    private final SecuritySupport sec;

    public TeamController(TeamService teamSvc, SecuritySupport sec) {
        this.teamSvc = teamSvc;
        this.sec = sec;
    }

    /** 团队列表（大屏公开，供团队选择器使用）；登录用户所属团队排前 */
    @GetMapping("/teams")
    public List<Map<String, Object>> list() {
        return teamSvc.listTeams(sec.principal());
    }

    /** 最新团队（大屏公开） */
    @GetMapping("/teams/latest")
    public Map<String, Object> latest() {
        return teamSvc.latestTeamDto();
    }

    /** 团队成员（需 MANAGE_TEAM） */
    @GetMapping("/teams/{id}/members")
    public List<Map<String, Object>> members(@PathVariable Long id) {
        sec.require(Permission.MANAGE_TEAM);
        return teamSvc.members(id);
    }

    @PostMapping("/teams")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_TEAM);
        return teamSvc.create(body);
    }

    @PutMapping("/teams/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        sec.require(Permission.MANAGE_TEAM);
        return teamSvc.update(id, body);
    }

    @DeleteMapping("/teams/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        sec.require(Permission.MANAGE_TEAM);
        teamSvc.delete(id);
        return Map.of("ok", true);
    }
}
