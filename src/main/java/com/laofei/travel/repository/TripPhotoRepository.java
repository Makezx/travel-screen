package com.laofei.travel.repository;

import com.laofei.travel.model.TripPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripPhotoRepository extends JpaRepository<TripPhoto, Long> {
    List<TripPhoto> findByTripId(Long tripId);
}
