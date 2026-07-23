package com.chargingpile.service;

import com.chargingpile.dto.DailyPileReportRow;
import com.chargingpile.entity.*;
import com.chargingpile.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class ReportServiceTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private DetailListRepository detailListRepository;

    @Autowired
    private ChargingPileRepository chargingPileRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    public void setUp() {
        detailListRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();
        ensurePile("快充1", ChargingMode.FAST, 30.0);
        ensurePile("快充2", ChargingMode.FAST, 30.0);
        ensurePile("快充3", ChargingMode.FAST, 30.0);
        ensurePile("慢充1", ChargingMode.TRICKLE, 10.0);
        ensurePile("慢充2", ChargingMode.TRICKLE, 10.0);
    }

    private void ensurePile(String id, ChargingMode type, double power) {
        if (chargingPileRepository.existsById(id)) {
            return;
        }
        chargingPileRepository.save(ChargingPile.builder()
                .id(id).type(type).power(power).state(PileState.RUNNING)
                .chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0)
                .totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0)
                .build());
    }

    @Test
    public void getDailyPileReport_shouldAggregateByPileAndIncludeEmptyPiles() {
        User user = userRepository.save(User.builder()
                .username("report_user")
                .password("x")
                .role(Role.CUSTOMER)
                .createdAt(LocalDateTime.now())
                .build());

        ChargingPile pile1 = chargingPileRepository.findById("快充1").orElseThrow();
        ChargingPile pile2 = chargingPileRepository.findById("快充2").orElseThrow();

        Vehicle v1 = vehicleRepository.save(Vehicle.builder()
                .id("RV1")
                .user(user)
                .vehicleType("EV")
                .batteryCapacity(60.0)
                .currentCapacity(10.0)
                .chargeMode(ChargingMode.FAST)
                .requestedAmount(20.0)
                .requestTime(LocalDateTime.of(2026, 6, 12, 8, 0))
                .queueNum("F1")
                .state(VehicleState.FINISHED)
                .build());

        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 12, 9, 30, 0);
        detailListRepository.save(DetailList.builder()
                .detailNo("DL-TEST01")
                .createdAt(createdAt)
                .pile(pile1)
                .vehicle(v1)
                .chargedAmount(10.0)
                .chargeDuration(1200L)
                .startTime(createdAt.minusMinutes(20))
                .endTime(createdAt)
                .chargeCost(7.0)
                .serviceCost(8.0)
                .totalCost(15.0)
                .build());

        List<DailyPileReportRow> rows = reportService.getDailyPileReport(LocalDate.of(2026, 6, 12));

        assertEquals(5, rows.size());

        DailyPileReportRow fast1 = rows.stream().filter(r -> "快充1".equals(r.getPileId())).findFirst().orElseThrow();
        assertEquals(1, fast1.getChargeCount());
        assertEquals(1200L, fast1.getTotalChargeDurationSec());
        assertEquals(10.0, fast1.getTotalChargeAmount(), 1e-9);
        assertEquals(7.0, fast1.getTotalChargeCost(), 1e-9);
        assertEquals(8.0, fast1.getTotalServiceCost(), 1e-9);
        assertEquals(15.0, fast1.getTotalCost(), 1e-9);

        DailyPileReportRow fast2 = rows.stream().filter(r -> "快充2".equals(r.getPileId())).findFirst().orElseThrow();
        assertEquals(0, fast2.getChargeCount());
        assertEquals(0.0, fast2.getTotalCost(), 1e-9);
    }
}
