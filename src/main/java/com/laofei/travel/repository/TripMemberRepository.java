package com.laofei.travel.repository;

import com.laofei.travel.model.TripMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

    List<TripMember> findByTripId(Long tripId);

    Optional<TripMember> findByTripIdAndUserId(Long tripId, Long userId);

    void deleteByTripId(Long tripId);

    long countByTripId(Long tripId);
}
