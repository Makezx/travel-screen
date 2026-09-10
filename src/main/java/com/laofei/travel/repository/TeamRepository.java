package com.laofei.travel.repository;

import com.laofei.travel.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByName(String name);
    boolean existsByName(String name);

    /** 最新团队 = created_at 最大者 */
    Optional<Team> findTopByOrderByCreatedAtDesc();

    /** 种子默认团队 */
    Optional<Team> findFirstByIsDefaultTrue();
}
