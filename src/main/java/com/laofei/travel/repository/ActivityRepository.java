package com.laofei.travel.repository;

import com.laofei.travel.model.Activity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    List<Activity> findByStatusOrderByCreatedAtDesc(String status);

    long countByTeamId(Long teamId);
}
