package com.chargingpile.repository;

import com.chargingpile.entity.RechargeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RechargeRecordRepository extends JpaRepository<RechargeRecord, Long> {
    List<RechargeRecord> findByVehicleId(String vehicleId);
    List<RechargeRecord> findByPileId(String pileId);
}
