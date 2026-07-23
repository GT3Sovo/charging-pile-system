package com.chargingpile.repository;

import com.chargingpile.entity.QueueRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueRecordRepository extends JpaRepository<QueueRecord, Long> {
    List<QueueRecord> findByPileIdOrderByPositionAsc(String pileId);
    List<QueueRecord> findByQueueTypeOrderByPositionAsc(String queueType);
    Optional<QueueRecord> findFirstByVehicle_Id(String vehicleId);
    void deleteByVehicleId(String vehicleId);
}
