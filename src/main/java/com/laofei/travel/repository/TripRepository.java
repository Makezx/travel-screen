package com.laofei.travel.repository;

import com.laofei.travel.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findByStatus(String status);

    List<Trip> findByTeamId(Long teamId);

    List<Trip> findByTeamIdIsNull();

    /** 合并后：某顶层行程（活动）下的全部子行程 */
    List<Trip> findByParentId(Long parentId);

    /** 顶层行程（活动）：parent_id 为空 */
    List<Trip> findByParentIdIsNull();

    @Query("select count(t) from Trip t where t.parentId is null")
    long countTopLevel();

    @Query("select coalesce(sum(t.total),0) from Trip t where t.status = 'done' and t.total is not null")
    double sumDoneTotal();
}
