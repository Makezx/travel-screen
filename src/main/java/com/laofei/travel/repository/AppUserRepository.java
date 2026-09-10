package com.laofei.travel.repository;

import com.laofei.travel.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findByDisplayName(String displayName);

    /** 属于某团队的用户数（用于团队列表 memberCount） */
    long countByTeams_Id(Long teamId);

    /** 拥有某角色的用户数（用于角色删除前校验） */
    long countByRoles_Id(Long roleId);

    /** 某团队的全部成员（用于删除团队时解除关联） */
    List<AppUser> findByTeams_Id(Long teamId);

    Page<AppUser> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
            String username, String displayName, Pageable pageable);
}
