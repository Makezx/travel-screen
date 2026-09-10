package com.laofei.travel.repository;

import com.laofei.travel.model.ActivityMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivityMemberRepository extends JpaRepository<ActivityMember, Long> {

    List<ActivityMember> findByActivityId(Long activityId);

    List<ActivityMember> findByUserId(Long userId);

    Optional<ActivityMember> findByActivityIdAndUserId(Long activityId, Long userId);

    long countByActivityId(Long activityId);

    void deleteByActivityId(Long activityId);
}
