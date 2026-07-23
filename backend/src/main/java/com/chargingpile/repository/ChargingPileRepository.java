package com.chargingpile.repository;

import com.chargingpile.entity.ChargingPile;
import com.chargingpile.entity.ChargingMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChargingPileRepository extends JpaRepository<ChargingPile, String> {
    List<ChargingPile> findByType(ChargingMode type);
}
