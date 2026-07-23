package com.chargingpile.repository;

import com.chargingpile.entity.DetailList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DetailListRepository extends JpaRepository<DetailList, String> {
    List<DetailList> findByVehicleId(String vehicleId);
    List<DetailList> findByPileId(String pileId);
    List<DetailList> findByBillBillNo(String billNo);
    List<DetailList> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
