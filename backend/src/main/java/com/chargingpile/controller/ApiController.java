package com.chargingpile.controller;

import com.chargingpile.entity.*;
import com.chargingpile.repository.*;
import com.chargingpile.dto.DailyPileReportRow;
import com.chargingpile.service.BillingEngine;
import com.chargingpile.service.ReportService;
import com.chargingpile.service.SchedulingEngine;
import com.chargingpile.service.SimulationService;
import lombok.Data;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Enable CORS for easier frontend integration
public class ApiController {

    private final SimulationService simulationService;
    private final SchedulingEngine schedulingEngine;
    private final BillingEngine billingEngine;
    private final ChargingPileRepository chargingPileRepository;
    private final VehicleRepository vehicleRepository;
    private final QueueRecordRepository queueRecordRepository;
    private final BillRepository billRepository;
    private final DetailListRepository detailListRepository;
    private final RechargeRecordRepository rechargeRecordRepository;
    private final ReportService reportService;
    private final UserRepository userRepository;

    public ApiController(SimulationService simulationService,
                         SchedulingEngine schedulingEngine,
                         BillingEngine billingEngine,
                         ChargingPileRepository chargingPileRepository,
                         VehicleRepository vehicleRepository,
                         QueueRecordRepository queueRecordRepository,
                         BillRepository billRepository,
                         DetailListRepository detailListRepository,
                         RechargeRecordRepository rechargeRecordRepository,
                         ReportService reportService,
                         UserRepository userRepository) {
        this.simulationService = simulationService;
        this.schedulingEngine = schedulingEngine;
        this.billingEngine = billingEngine;
        this.chargingPileRepository = chargingPileRepository;
        this.vehicleRepository = vehicleRepository;
        this.queueRecordRepository = queueRecordRepository;
        this.billRepository = billRepository;
        this.detailListRepository = detailListRepository;
        this.rechargeRecordRepository = rechargeRecordRepository;
        this.reportService = reportService;
        this.userRepository = userRepository;
    }

    @GetMapping("/state")
    public Map<String, Object> getCurrentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("time", simulationService.getCurrentTimeStr());
        state.put("timeSec", simulationService.getCurrentSec());

        // Get Piles
        List<ChargingPile> piles = chargingPileRepository.findAll();
        piles.sort(Comparator.comparing(ChargingPile::getId));
        state.put("piles", piles);

        // Get Queue Records
        List<QueueRecord> allQueueRecords = queueRecordRepository.findAll();

        // Separate queues into maps of list for easy frontend reading
        List<Map<String, Object>> waitingArea = allQueueRecords.stream()
                .filter(qr -> "WAITING_AREA".equals(qr.getQueueType()))
                .sorted(Comparator.comparing(QueueRecord::getPosition))
                .map(this::mapQueueRecord)
                .collect(Collectors.toList());
        state.put("waitingArea", waitingArea);

        List<Map<String, Object>> faultQueue = allQueueRecords.stream()
                .filter(qr -> "FAULT_QUEUE".equals(qr.getQueueType()))
                .sorted(Comparator.comparing(QueueRecord::getPosition))
                .map(this::mapQueueRecord)
                .collect(Collectors.toList());
        state.put("faultQueue", faultQueue);

        // Map of pile queues: pileId -> List<QueueRecord>
        Map<String, List<Map<String, Object>>> pileQueues = new HashMap<>();
        for (ChargingPile p : piles) {
            List<Map<String, Object>> records = allQueueRecords.stream()
                    .filter(qr -> qr.getPile() != null && qr.getPile().getId().equals(p.getId()))
                    .sorted(Comparator.comparing(QueueRecord::getPosition))
                    .map(this::mapQueueRecord)
                    .collect(Collectors.toList());
            pileQueues.put(p.getId(), records);
        }
        state.put("pileQueues", pileQueues);

        // All Vehicles
        List<Vehicle> vehicles = vehicleRepository.findAll();
        vehicles.sort(Comparator.comparing(v -> {
            try {
                return Integer.parseInt(v.getId().substring(1));
            } catch (Exception e) {
                return 999;
            }
        }));
        state.put("vehicles", vehicles);

        // Bills
        List<Bill> bills = billRepository.findAll();
        bills.sort((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()));
        state.put("bills", bills);

        // Detail Lists (Receipts)
        List<DetailList> details = detailListRepository.findAll();
        details.sort((d1, d2) -> d2.getCreatedAt().compareTo(d1.getCreatedAt()));
        state.put("details", details);

        // Event status list
        state.put("events", simulationService.getEventTemplates());
        state.put("eventMode", simulationService.isBuiltinEventsEnabled() ? "builtin" : "free");
        state.put("modeChangeAllowed", simulationService.isModeChangeAllowed());

        // Authoritative per-vehicle charging progress (backend-computed, for frontend display)
        state.put("vehicleProgress", buildVehicleProgressMap());

        Map<String, Long> businessDataCounts = new LinkedHashMap<>();
        businessDataCounts.put("queueRecords", queueRecordRepository.count());
        businessDataCounts.put("rechargeRecords", rechargeRecordRepository.count());
        businessDataCounts.put("details", detailListRepository.count());
        businessDataCounts.put("bills", billRepository.count());
        state.put("businessDataCounts", businessDataCounts);

        return state;
    }

    private Map<String, Map<String, Object>> buildVehicleProgressMap() {
        Map<String, Map<String, Object>> progressMap = new HashMap<>();
        List<Vehicle> allVehicles = vehicleRepository.findAll();
        LocalDateTime simNow = LocalDateTime.of(2026, 6, 12, 0, 0, 0)
                .plusSeconds(simulationService.getCurrentSec());

        for (Vehicle vehicle : allVehicles) {
            String vehicleId = vehicle.getId();
            List<RechargeRecord> recs = rechargeRecordRepository.findByVehicleId(vehicleId);
            List<DetailList> vehicleDetails = detailListRepository.findByVehicleId(vehicleId);

            double completedCharged = vehicleDetails.stream().mapToDouble(DetailList::getChargedAmount).sum();
            double completedCost = vehicleDetails.stream().mapToDouble(DetailList::getTotalCost).sum();
            double activeCharged = 0;
            double activeCost = 0;
            for (RechargeRecord r : recs) {
                if (r.getEndTime() != null) {
                    continue;
                }
                double charged = r.getChargedAmount() != null ? r.getChargedAmount() : 0;
                activeCharged += charged;
                if (charged > 0 && r.getStartTime() != null) {
                    BillingEngine.BillingResult br = billingEngine.calculate(r.getStartTime(), simNow, charged);
                    activeCost += br.getTotalCost();
                }
            }

            Map<String, Object> m = new HashMap<>();
            m.put("chargedAmount", completedCharged + activeCharged);
            m.put("totalCost", completedCost + activeCost);
            m.put("requestedAmount", vehicle.getRequestedAmount());
            progressMap.put(vehicleId, m);
        }
        return progressMap;
    }

    private Map<String, Object> mapQueueRecord(QueueRecord qr) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", qr.getId());
        m.put("queueNum", qr.getQueueNum());
        m.put("position", qr.getPosition());
        m.put("queueType", qr.getQueueType());
        m.put("entryTime", qr.getEntryTime().toString());
        m.put("vehicleId", qr.getVehicle().getId());
        m.put("chargeMode", qr.getVehicle().getChargeMode());
        m.put("requestedAmount", qr.getVehicle().getRequestedAmount());
        m.put("vehicleState", qr.getVehicle().getState());
        User owner = qr.getVehicle().getUser();
        if (owner != null) {
            m.put("userId", owner.getId());
            m.put("username", owner.getUsername());
        }
        m.put("vehicleType", qr.getVehicle().getVehicleType());
        m.put("batteryCapacity", qr.getVehicle().getBatteryCapacity());
        m.put("currentCapacity", qr.getVehicle().getCurrentCapacity());
        long waitingSeconds = Math.max(0L, Duration.between(qr.getEntryTime(), simulationService.getCurrentDateTime()).getSeconds());
        m.put("waitingSeconds", waitingSeconds);
        m.put("aheadCount", "PILE_QUEUE".equals(qr.getQueueType()) ? Math.max(0, qr.getPosition()) : 0);
        if (qr.getPile() != null) {
            m.put("pileId", qr.getPile().getId());
        }
        return m;
    }

    // Simulation Controller Endpoints

    @PostMapping("/sim/reset")
    public Map<String, Object> resetSimulation() {
        simulationService.reset();
        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("eventMode", simulationService.isBuiltinEventsEnabled() ? "builtin" : "free");
        res.put("modeChangeAllowed", simulationService.isModeChangeAllowed());
        return res;
    }

    @PostMapping("/sim/mode")
    public Map<String, Object> setSimulationMode(@RequestParam String mode) {
        Map<String, Object> res = new HashMap<>();
        try {
            boolean enableBuiltin = "builtin".equalsIgnoreCase(mode);
            if (!enableBuiltin && !"free".equalsIgnoreCase(mode)) {
                res.put("status", "ERROR");
                res.put("message", "无效模式，请使用 builtin 或 free");
                return res;
            }
            simulationService.setEventMode(enableBuiltin);
            res.put("status", "SUCCESS");
            res.put("eventMode", simulationService.isBuiltinEventsEnabled() ? "builtin" : "free");
            res.put("modeChangeAllowed", simulationService.isModeChangeAllowed());
            return res;
        } catch (IllegalStateException e) {
            res.put("status", "ERROR");
            res.put("message", e.getMessage());
            return res;
        }
    }

    @PostMapping("/sim/tick")
    public String tickSimulation(@RequestParam(defaultValue = "1") int seconds) {
        simulationService.tick(seconds);
        return "SUCCESS";
    }

    @PostMapping("/sim/next-event")
    public String nextEvent() {
        simulationService.nextEvent();
        return "SUCCESS";
    }

    // Direct Operations for Client view interaction (can bypass simulation schedule to demonstrate manual use cases)

    public static class ChargeRequestDto {
        private String vehicleId;
        private String vehicleType;
        private double batteryCapacity;
        private double currentCapacity;
        private ChargingMode mode;
        private double requestedAmount;

        public String getVehicleId() { return vehicleId; }
        public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
        public String getVehicleType() { return vehicleType; }
        public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
        public double getBatteryCapacity() { return batteryCapacity; }
        public void setBatteryCapacity(double batteryCapacity) { this.batteryCapacity = batteryCapacity; }
        public double getCurrentCapacity() { return currentCapacity; }
        public void setCurrentCapacity(double currentCapacity) { this.currentCapacity = currentCapacity; }
        public ChargingMode getMode() { return mode; }
        public void setMode(ChargingMode mode) { this.mode = mode; }
        public double getRequestedAmount() { return requestedAmount; }
        public void setRequestedAmount(double requestedAmount) { this.requestedAmount = requestedAmount; }
    }

    @PostMapping("/charge/request")
    public Map<String, Object> requestCharge(@RequestBody ChargeRequestDto dto, HttpSession session) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 0, 0, 0)
                .plusSeconds(simulationService.getCurrentSec());
        try {
            Vehicle vehicle = schedulingEngine.requestCharge(
                    dto.getVehicleId(),
                    resolveRequestUserId(session),
                    dto.getVehicleType(),
                    dto.getBatteryCapacity(),
                    dto.getCurrentCapacity(),
                    dto.getMode(),
                    dto.getRequestedAmount(),
                    time
            );
            Map<String, Object> res = new HashMap<>();
            res.put("status", "SUCCESS");
            res.put("vehicle", vehicle);
            return res;
        } catch (IllegalStateException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("status", "ERROR");
            res.put("message", e.getMessage());
            return res;
        }
    }

    @PostMapping("/charge/cancel")
    public Map<String, Object> cancelCharge(@RequestParam String vehicleId) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 0, 0, 0)
                .plusSeconds(simulationService.getCurrentSec());
        Vehicle vehicle = schedulingEngine.cancelOrEndCharge(vehicleId, time);
        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("vehicle", vehicle);
        return res;
    }

    public static class ChangeRequestDto {
        private String vehicleId;
        private ChargingMode mode; // optional
        private Double amount;     // optional; null 或 <=0 表示不修改度数

        public String getVehicleId() { return vehicleId; }
        public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
        public ChargingMode getMode() { return mode; }
        public void setMode(ChargingMode mode) { this.mode = mode; }
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }
    }

    @PostMapping("/charge/change")
    public Map<String, Object> changeRequest(@RequestBody ChangeRequestDto dto) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 0, 0, 0)
                .plusSeconds(simulationService.getCurrentSec());
        try {
            Vehicle vehicle = schedulingEngine.changeRequest(dto.getVehicleId(), dto.getMode(), dto.getAmount(), time);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "SUCCESS");
            res.put("vehicle", vehicle);
            return res;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("status", "ERROR");
            res.put("message", e.getMessage());
            return res;
        }
    }

    @GetMapping("/reports/daily")
    public Map<String, Object> getDailyReport(@RequestParam(required = false) String date) {
        simulationService.ensureInitialized();
        LocalDate reportDate = (date != null && !date.isBlank())
                ? LocalDate.parse(date)
                : simulationService.getCurrentDateTime().toLocalDate();
        List<DailyPileReportRow> rows = reportService.getDailyPileReport(reportDate);
        Map<String, Object> res = new HashMap<>();
        res.put("date", reportDate.toString());
        res.put("rows", rows);
        return res;
    }

    @GetMapping("/reports/summary")
    public Map<String, Object> getReportSummary(@RequestParam(defaultValue = "daily") String period,
                                                 @RequestParam(required = false) String date) {
        simulationService.ensureInitialized();
        LocalDate anchor = (date != null && !date.isBlank())
                ? LocalDate.parse(date)
                : simulationService.getCurrentDateTime().toLocalDate();
        LocalDate start;
        LocalDate end;
        String normalized = period.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "weekly" -> {
                start = anchor.with(DayOfWeek.MONDAY);
                end = start.plusWeeks(1);
            }
            case "monthly" -> {
                start = anchor.withDayOfMonth(1);
                end = start.plusMonths(1);
            }
            default -> {
                normalized = "daily";
                start = anchor;
                end = start.plusDays(1);
            }
        }
        String label = end.minusDays(1).equals(start)
                ? start.toString()
                : start + " ~ " + end.minusDays(1);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("period", normalized);
        res.put("startDate", start.toString());
        res.put("endDate", end.minusDays(1).toString());
        res.put("label", label);
        res.put("rows", reportService.getPileReport(start, end, label));
        return res;
    }

    @GetMapping("/exports/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(defaultValue = "details") String type,
                                            @RequestParam(defaultValue = "all") String scope,
                                            @RequestParam(required = false) String vehicleId) {
        String normalizedType = type.toLowerCase(Locale.ROOT);
        String normalizedScope = scope.toLowerCase(Locale.ROOT);
        if (!Set.of("details", "bills").contains(normalizedType)) {
            return ResponseEntity.badRequest().body("Unsupported export type".getBytes(StandardCharsets.UTF_8));
        }
        if (!Set.of("all", "vehicle").contains(normalizedScope)) {
            return ResponseEntity.badRequest().body("Unsupported export scope".getBytes(StandardCharsets.UTF_8));
        }
        if ("vehicle".equals(normalizedScope) && (vehicleId == null || vehicleId.isBlank())) {
            return ResponseEntity.badRequest().body("vehicleId is required".getBytes(StandardCharsets.UTF_8));
        }

        String csv = "details".equals(normalizedType)
                ? buildDetailsCsv(normalizedScope, vehicleId)
                : buildBillsCsv(normalizedScope, vehicleId);
        String date = simulationService.getCurrentDateTime().toLocalDate().toString();
        String subject = "details".equals(normalizedType) ? "车辆详单" : "车辆账单";
        String range = "vehicle".equals(normalizedScope) ? "_" + vehicleId : "_全部车辆";
        String filename = "G12_" + subject + range + "_" + date + ".csv";
        byte[] content = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private String buildDetailsCsv(String scope, String vehicleId) {
        List<DetailList> rows = "vehicle".equals(scope)
                ? detailListRepository.findByVehicleId(vehicleId)
                : detailListRepository.findAll();
        rows.sort(Comparator
                .comparing((DetailList detail) -> detail.getVehicle().getId(), this::compareVehicleIds)
                .thenComparing(DetailList::getStartTime)
                .thenComparing(DetailList::getDetailNo));

        StringBuilder csv = new StringBuilder();
        csv.append("车辆编号,账单编号,详单编号,详单生成时间,充电桩编号,充电电量(度),充电时长(秒),启动时间,停止时间,充电费用(元),服务费用(元),总费用(元)\r\n");
        for (DetailList detail : rows) {
            appendCsvRow(csv,
                    detail.getVehicle().getId(),
                    detail.getBill() == null ? "" : detail.getBill().getBillNo(),
                    detail.getDetailNo(),
                    formatCsvDateTime(detail.getCreatedAt()),
                    detail.getPile().getId(),
                    formatCsvNumber(detail.getChargedAmount()),
                    String.valueOf(detail.getChargeDuration()),
                    formatCsvDateTime(detail.getStartTime()),
                    formatCsvDateTime(detail.getEndTime()),
                    formatCsvNumber(detail.getChargeCost()),
                    formatCsvNumber(detail.getServiceCost()),
                    formatCsvNumber(detail.getTotalCost()));
        }
        return csv.toString();
    }

    private String buildBillsCsv(String scope, String vehicleId) {
        List<Bill> rows = "vehicle".equals(scope)
                ? billRepository.findByVehicleId(vehicleId)
                : billRepository.findAll();
        rows.sort(Comparator
                .comparing((Bill bill) -> bill.getVehicle().getId(), this::compareVehicleIds)
                .thenComparing(Bill::getCreatedAt)
                .thenComparing(Bill::getBillNo));

        StringBuilder csv = new StringBuilder();
        csv.append("车辆编号,账单编号,充电站,账单时间,累计充电电量(度),充电费用(元),服务费用(元),总费用(元)\r\n");
        for (Bill bill : rows) {
            appendCsvRow(csv,
                    bill.getVehicle().getId(),
                    bill.getBillNo(),
                    bill.getStationName(),
                    formatCsvDateTime(bill.getCreatedAt()),
                    formatCsvNumber(bill.getChargedAmount()),
                    formatCsvNumber(bill.getChargeCost()),
                    formatCsvNumber(bill.getServiceCost()),
                    formatCsvNumber(bill.getTotalCost()));
        }
        return csv.toString();
    }

    private void appendCsvRow(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCsv(values[i]));
        }
        csv.append("\r\n");
    }

    private String escapeCsv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private String formatCsvDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String formatCsvNumber(Double value) {
        return String.format(Locale.ROOT, "%.2f", value == null ? 0.0 : value);
    }

    private int compareVehicleIds(String left, String right) {
        try {
            if (left.startsWith("V") && right.startsWith("V")) {
                return Integer.compare(Integer.parseInt(left.substring(1)), Integer.parseInt(right.substring(1)));
            }
        } catch (NumberFormatException ignored) {
            // Fall back to lexical ordering for custom license plates.
        }
        return left.compareTo(right);
    }

    private Long resolveRequestUserId(HttpSession session) {
        Object value = session.getAttribute("userId");
        if (value instanceof Long userId && userRepository.existsById(userId)) {
            return userId;
        }
        return 1L;
    }

    @PostMapping("/pile/breakdown")
    public String triggerBreakdown(@RequestParam String pileId) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 0, 0, 0)
                .plusSeconds(simulationService.getCurrentSec());
        schedulingEngine.reportBreakdown(pileId, time);
        return "SUCCESS";
    }

    @PostMapping("/pile/recover")
    public String triggerRecovery(@RequestParam String pileId) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = simulationService.getCurrentDateTime();
        ChargingPile pile = chargingPileRepository.findById(pileId).orElse(null);
        if (pile != null && pile.getState() == PileState.FAULT) {
            pile.setState(PileState.RUNNING);
            chargingPileRepository.save(pile);
            schedulingEngine.redispatchAfterRecovery(pile.getType(), time);
            return "SUCCESS";
        }
        return "NOT_FAULTY";
    }

    @PostMapping("/pile/stop")
    public Map<String, Object> stopPile(@RequestParam String pileId) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = simulationService.getCurrentDateTime();
        try {
            schedulingEngine.shutdownPile(pileId, time);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "SUCCESS");
            return res;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("status", "ERROR");
            res.put("message", e.getMessage());
            return res;
        }
    }

    @PostMapping("/pile/start")
    public Map<String, Object> startPile(@RequestParam String pileId) {
        simulationService.ensureInitialized();
        simulationService.lockMode();
        LocalDateTime time = simulationService.getCurrentDateTime();
        try {
            schedulingEngine.startPile(pileId, time);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "SUCCESS");
            return res;
        } catch (IllegalStateException | IllegalArgumentException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("status", "ERROR");
            res.put("message", e.getMessage());
            return res;
        }
    }
}
