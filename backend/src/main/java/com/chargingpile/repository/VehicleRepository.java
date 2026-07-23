package com.chargingpile.repository;

import com.chargingpile.entity.Vehicle;
import com.chargingpile.entity.VehicleState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    List<Vehicle> findByState(VehicleState state);
    List<Vehicle> findByCurrentPileId(String pileId);
    List<Vehicle> findByUserIdOrderByIdAsc(Long userId);
}
