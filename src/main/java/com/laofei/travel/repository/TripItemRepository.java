package com.laofei.travel.repository;

import com.laofei.travel.model.TripItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TripItemRepository extends JpaRepository<TripItem, Long> {
    List<TripItem> findByTripId(Long tripId);

    @Modifying
    @Query("delete from TripItem t where t.tripId = :tripId")
    void deleteByTripId(Long tripId);

    @Query("select coalesce(sum(i.amt),0) from TripItem i where i.tripId = :tripId")
    java.math.BigDecimal sumAmtByTripId(Long tripId);
}
