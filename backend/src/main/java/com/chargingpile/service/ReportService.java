package com.chargingpile.service;

import com.chargingpile.dto.DailyPileReportRow;
import com.chargingpile.entity.ChargingPile;
import com.chargingpile.entity.DetailList;
import com.chargingpile.repository.ChargingPileRepository;
import com.chargingpile.repository.DetailListRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReportService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DetailListRepository detailListRepository;
    private final ChargingPileRepository chargingPileRepository;

    public ReportService(DetailListRepository detailListRepository,
                         ChargingPileRepository chargingPileRepository) {
        this.detailListRepository = detailListRepository;
        this.chargingPileRepository = chargingPileRepository;
    }

    /**
     * 按仿真日历日聚合各桩详单，生成当日报表（含零交易桩）。
     */
    public List<DailyPileReportRow> getDailyPileReport(LocalDate date) {
        return getPileReport(date, date.plusDays(1), date.format(DATE_FORMATTER));
    }

    /** Aggregate pile transactions for a half-open date range [startDate, endDate). */
    public List<DailyPileReportRow> getPileReport(LocalDate startDate, LocalDate endDate, String periodLabel) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atStartOfDay();

        List<DetailList> details = detailListRepository.findByCreatedAtBetween(start, end);
        Map<String, DailyPileReportRow> aggregated = new LinkedHashMap<>();

        List<ChargingPile> piles = chargingPileRepository.findAll();
        piles.sort(Comparator.comparing(ChargingPile::getId));
        for (ChargingPile pile : piles) {
            aggregated.put(pile.getId(), emptyRow(periodLabel, pile.getId()));
        }

        for (DetailList dl : details) {
            String pileId = dl.getPile().getId();
            DailyPileReportRow row = aggregated.computeIfAbsent(pileId, id -> emptyRow(periodLabel, id));
            row.setChargeCount(row.getChargeCount() + 1);
            row.setTotalChargeDurationSec(row.getTotalChargeDurationSec() + (dl.getChargeDuration() != null ? dl.getChargeDuration() : 0L));
            row.setTotalChargeAmount(row.getTotalChargeAmount() + (dl.getChargedAmount() != null ? dl.getChargedAmount() : 0.0));
            row.setTotalChargeCost(row.getTotalChargeCost() + (dl.getChargeCost() != null ? dl.getChargeCost() : 0.0));
            row.setTotalServiceCost(row.getTotalServiceCost() + (dl.getServiceCost() != null ? dl.getServiceCost() : 0.0));
            row.setTotalCost(row.getTotalCost() + (dl.getTotalCost() != null ? dl.getTotalCost() : 0.0));
        }

        return new ArrayList<>(aggregated.values());
    }

    private DailyPileReportRow emptyRow(String date, String pileId) {
        return new DailyPileReportRow(date, pileId, 0L, 0L, 0.0, 0.0, 0.0, 0.0);
    }
}
