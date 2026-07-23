package com.chargingpile.service;

import com.chargingpile.entity.*;
import com.chargingpile.repository.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SimulationService {

    private final SchedulingEngine schedulingEngine;
    private final UserRepository userRepository;
    private final ChargingPileRepository chargingPileRepository;
    private final VehicleRepository vehicleRepository;
    private final QueueRecordRepository queueRecordRepository;
    private final RechargeRecordRepository rechargeRecordRepository;
    private final DetailListRepository detailListRepository;
    private final BillRepository billRepository;
    private final PasswordEncoder passwordEncoder;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private int currentSec = 21600; // Starts at 06:00:00
    private final LocalDateTime dateBasis = LocalDateTime.of(2026, 6, 12, 0, 0, 0);
    private boolean isInitialized = false;
    private boolean builtinEventsEnabled = true;
    private boolean initialEventsPending = false;
    /** 重置后允许切换模式；一旦开始推进仿真或手动操作则锁定 */
    private boolean modeChangeAllowed = false;
    private final Object simLock = new Object();

    // Track pile breakdown end times (pileId -> recoverySec)
    private final Map<String, Integer> pileRecoveryTimes = new HashMap<>();

    // Built-in 32 official event templates (master list, always populated)
    private final List<SimEventTemplate> eventTemplates = new ArrayList<>();

    public static class SimEventTemplate {
        public String timeStr;
        public int timeSec;
        public String type;
        public String targetId;
        public String chargeType;
        public double value;
        public boolean executed = false;

        public SimEventTemplate(String timeStr, String type, String targetId, String chargeType, double value) {
            this.timeStr = timeStr;
            String[] parts = timeStr.split(":");
            this.timeSec = Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
            this.type = type;
            this.targetId = targetId;
            this.chargeType = chargeType;
            this.value = value;
        }
    }

    public SimulationService(SchedulingEngine schedulingEngine,
                             UserRepository userRepository,
                             ChargingPileRepository chargingPileRepository,
                             VehicleRepository vehicleRepository,
                             QueueRecordRepository queueRecordRepository,
                             RechargeRecordRepository rechargeRecordRepository,
                             DetailListRepository detailListRepository,
                             BillRepository billRepository,
                             PasswordEncoder passwordEncoder) {
        this.schedulingEngine = schedulingEngine;
        this.userRepository = userRepository;
        this.chargingPileRepository = chargingPileRepository;
        this.vehicleRepository = vehicleRepository;
        this.queueRecordRepository = queueRecordRepository;
        this.rechargeRecordRepository = rechargeRecordRepository;
        this.detailListRepository = detailListRepository;
        this.billRepository = billRepository;
        this.passwordEncoder = passwordEncoder;
        initEventTemplates();
    }

    private void initEventTemplates() {
        eventTemplates.clear();
        eventTemplates.add(new SimEventTemplate("06:00:00", "A", "V1", "T", 40.0));
        eventTemplates.add(new SimEventTemplate("06:05:00", "A", "V2", "T", 30.0));
        eventTemplates.add(new SimEventTemplate("06:10:00", "A", "V3", "F", 60.0));
        eventTemplates.add(new SimEventTemplate("06:20:00", "A", "V2", "O", 0.0)); // Cancel V2
        eventTemplates.add(new SimEventTemplate("06:25:00", "A", "V4", "T", 20.0));
        eventTemplates.add(new SimEventTemplate("06:30:00", "A", "V5", "T", 20.0));
        eventTemplates.add(new SimEventTemplate("06:40:00", "A", "V6", "T", 20.0));
        eventTemplates.add(new SimEventTemplate("06:50:00", "A", "V7", "T", 10.0));
        eventTemplates.add(new SimEventTemplate("07:00:00", "A", "V8", "F", 90.0));
        eventTemplates.add(new SimEventTemplate("07:10:00", "A", "V9", "F", 30.0));
        eventTemplates.add(new SimEventTemplate("07:15:00", "A", "V10", "T", 10.0));
        eventTemplates.add(new SimEventTemplate("07:20:00", "A", "V11", "F", 60.0));
        eventTemplates.add(new SimEventTemplate("07:25:00", "A", "V12", "T", 10.0));
        eventTemplates.add(new SimEventTemplate("07:30:00", "A", "V13", "T", 7.5));
        eventTemplates.add(new SimEventTemplate("07:35:00", "A", "V14", "F", 75.0));
        eventTemplates.add(new SimEventTemplate("07:40:00", "A", "V15", "F", 45.0));
        eventTemplates.add(new SimEventTemplate("08:00:00", "A", "V16", "T", 5.0));
        eventTemplates.add(new SimEventTemplate("08:20:00", "A", "V17", "T", 15.0));
        eventTemplates.add(new SimEventTemplate("08:30:00", "A", "V18", "T", 20.0));
        eventTemplates.add(new SimEventTemplate("08:35:00", "A", "V19", "T", 25.0));
        eventTemplates.add(new SimEventTemplate("09:00:00", "A", "V20", "F", 30.0));
        eventTemplates.add(new SimEventTemplate("09:10:00", "A", "V7", "O", 0.0)); // Cancel V7
        eventTemplates.add(new SimEventTemplate("09:20:00", "A", "V11", "O", 0.0)); // Cancel V11
        eventTemplates.add(new SimEventTemplate("09:30:00", "A", "V18", "O", 0.0)); // Cancel V18
        eventTemplates.add(new SimEventTemplate("09:35:00", "A", "V20", "O", 0.0)); // Cancel V20
        eventTemplates.add(new SimEventTemplate("09:50:00", "A", "V21", "F", 30.0));
        eventTemplates.add(new SimEventTemplate("10:00:00", "A", "V22", "T", 10.0));
        eventTemplates.add(new SimEventTemplate("10:05:00", "C", "V19", "F", 25.0)); // V19 to F, 25
        eventTemplates.add(new SimEventTemplate("10:10:00", "C", "V21", "F", 10.0)); // V21 to 10
        eventTemplates.add(new SimEventTemplate("10:20:00", "C", "V22", "F", 10.0)); // V22 to F, 10
        eventTemplates.add(new SimEventTemplate("10:30:00", "B", "T1", "O", 60.0)); // T1 breakdown 60 mins
        eventTemplates.add(new SimEventTemplate("10:50:00", "B", "F1", "O", 120.0)); // F1 breakdown 120 mins
    }

    public int getCurrentSec() {
        return currentSec;
    }

    public LocalDateTime getCurrentDateTime() {
        return dateBasis.plusSeconds(currentSec);
    }

    public String getCurrentTimeStr() {
        int h = currentSec / 3600;
        int m = (currentSec % 3600) / 60;
        int s = currentSec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public boolean isBuiltinEventsEnabled() {
        return builtinEventsEnabled;
    }

    public boolean isModeChangeAllowed() {
        return modeChangeAllowed;
    }

    public List<SimEventTemplate> getEventTemplates() {
        if (!builtinEventsEnabled) {
            return Collections.emptyList();
        }
        return eventTemplates;
    }

    @Transactional
    public void reset() {
        synchronized (simLock) {
            builtinEventsEnabled = true;
            doResetCore();
            modeChangeAllowed = true;
            initialEventsPending = true;
            System.out.println("Simulation Reset Successful. Mode change allowed.");
        }
    }

    @Transactional
    public void setEventMode(boolean enableBuiltin) {
        synchronized (simLock) {
            if (!modeChangeAllowed) {
                throw new IllegalStateException("请先点击「重置系统」后再切换模式");
            }
            builtinEventsEnabled = enableBuiltin;
            doResetCore();
            initialEventsPending = enableBuiltin;
            System.out.println("Event mode switched to: " + (enableBuiltin ? "BUILTIN" : "FREE"));
        }
    }

    public void lockMode() {
        modeChangeAllowed = false;
    }

    public void ensureInitialized() {
        synchronized (simLock) {
            if (!isInitialized) {
                reset();
                lockMode();
            }
        }
    }

    private void doResetCore() {
        detailListRepository.deleteAll();
        billRepository.deleteAll();
        rechargeRecordRepository.deleteAll();
        queueRecordRepository.deleteAll();

        // Keep registered customer vehicle profiles across simulation resets.
        for (Vehicle vehicle : vehicleRepository.findAll()) {
            if (vehicle.getUser() != null && Long.valueOf(1L).equals(vehicle.getUser().getId())) {
                vehicleRepository.delete(vehicle);
                continue;
            }
            vehicle.setState(VehicleState.CANCELLED);
            vehicle.setCurrentPile(null);
            vehicle.setQueueNum(null);
            vehicle.setRequestedAmount(0.0);
            vehicle.setRequestTime(dateBasis.plusSeconds(21600));
            vehicleRepository.save(vehicle);
        }
        chargingPileRepository.deleteAll();

        seedUser(1L, "system_driver", "driver123", "CUSTOMER");
        seedUser(2L, "admin", "admin123", "ADMIN");
        seedUser(3L, "manager", "manager123", "MANAGER");

        chargingPileRepository.save(ChargingPile.builder().id("快充1").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("快充2").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("快充3").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("慢充1").type(ChargingMode.TRICKLE).power(10.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("慢充2").type(ChargingMode.TRICKLE).power(10.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());

        schedulingEngine.setParameters(10, 3);

        currentSec = 21600;
        pileRecoveryTimes.clear();

        for (SimEventTemplate t : eventTemplates) {
            t.executed = false;
        }

        isInitialized = true;
        initialEventsPending = false;
    }

    private void seedUser(Long id, String username, String rawPassword, String role) {
        if (userRepository.findById(id).isEmpty() && userRepository.findByUsername(username).isEmpty()) {
            userRepository.insertUserWithId(id, username, passwordEncoder.encode(rawPassword), role, dateBasis);
        }
    }

    private void processInitialEventsIfPending() {
        if (builtinEventsEnabled && initialEventsPending) {
            processEventsAtSec(dateBasis.plusSeconds(currentSec));
            initialEventsPending = false;
        }
    }

    /**
     * Advance the simulation by X seconds
     */
    @Transactional
    public void tick(int seconds) {
        synchronized (simLock) {
            if (!isInitialized) {
                reset();
            }
            lockMode();
            processInitialEventsIfPending();
            doTick(seconds);
        }
    }

    private void doTick(int seconds) {
        for (int step = 0; step < seconds; step++) {
            currentSec++;
            LocalDateTime stepTime = dateBasis.plusSeconds(currentSec);
            processRecoveries(stepTime);
            processChargingSec(stepTime);
            processEventsAtSec(stepTime);
        }
    }

    private void processRecoveries(LocalDateTime stepTime) {
        List<String> recovered = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : pileRecoveryTimes.entrySet()) {
            if (currentSec >= entry.getValue()) {
                recovered.add(entry.getKey());
            }
        }

        for (String pId : recovered) {
            pileRecoveryTimes.remove(pId);
            ChargingPile pile = chargingPileRepository.findById(pId).orElse(null);
            if (pile != null && pile.getState() == PileState.FAULT) {
                pile.setState(PileState.RUNNING);
                chargingPileRepository.save(pile);
                System.out.println("[" + stepTime + "] Simulation: Pile " + pId + " recovered to RUNNING.");
                // Trigger scheduling for recovered pile
                schedulingEngine.redispatchAfterRecovery(pile.getType(), stepTime);
            }
        }
    }

    private void processChargingSec(LocalDateTime stepTime) {
        List<ChargingPile> piles = chargingPileRepository.findAll();
        List<String> finishedVehicleIds = new ArrayList<>();
        Set<ChargingMode> affectedModes = new LinkedHashSet<>();
        for (ChargingPile pile : piles) {
            if (pile.getState() == PileState.RUNNING) {
                List<QueueRecord> qrList = queueRecordRepository.findByPileIdOrderByPositionAsc(pile.getId());
                if (!qrList.isEmpty()) {
                    QueueRecord activeQr = qrList.get(0);
                    Vehicle activeVehicle = activeQr.getVehicle();

                    // If not currently marked as charging, start it
                    if (activeVehicle.getState() != VehicleState.CHARGING) {
                        activeVehicle.setState(VehicleState.CHARGING);
                        vehicleRepository.save(activeVehicle);
                    }

                    // Look for current unended RechargeRecord
                    List<RechargeRecord> recs = rechargeRecordRepository.findByVehicleId(activeVehicle.getId());
                    RechargeRecord record = recs.stream()
                            .filter(r -> r.getEndTime() == null && r.getPile().getId().equals(pile.getId()))
                            .findFirst()
                            .orElse(null);

                    if (record == null) {
                        // Create RechargeRecord if it doesn't exist
                        record = RechargeRecord.builder()
                                .vehicle(activeVehicle)
                                .pile(pile)
                                .startTime(stepTime.minusSeconds(1)) // starts at prior step
                                .chargedAmount(0.0)
                                .chargeCost(0.0)
                                .serviceCost(0.0)
                                .totalCost(0.0)
                                .build();
                        rechargeRecordRepository.save(record);
                    }

                    // Increment in-memory/in-db charged amount for this second
                    double powerPerSec = pile.getPower() / 3600.0;
                    double alreadyChargedBeforeThisSession = 0.0;
                    for (RechargeRecord r : recs) {
                        if (r.getEndTime() != null) {
                            alreadyChargedBeforeThisSession += r.getChargedAmount();
                        }
                    }

                    double remToCharge = activeVehicle.getRequestedAmount() - alreadyChargedBeforeThisSession - record.getChargedAmount();
                    double deltaQ = Math.min(powerPerSec, remToCharge);

                    if (deltaQ > 0) {
                        record.setChargedAmount(record.getChargedAmount() + deltaQ);
                        rechargeRecordRepository.save(record);
                    }

                    // Check if fully charged
                    double totalCharged = alreadyChargedBeforeThisSession + record.getChargedAmount();
                    if (totalCharged >= activeVehicle.getRequestedAmount() - 1e-9) {
                        System.out.println("[" + stepTime + "] Simulation: Vehicle " + activeVehicle.getId() + " fully charged. Deferring dispatch.");
                        finishedVehicleIds.add(activeVehicle.getId());
                        affectedModes.add(pile.getType());
                    }
                }
            }
        }

        for (String vid : finishedVehicleIds) {
            schedulingEngine.finishChargingNoDispatch(vid, stepTime);
        }
        for (ChargingMode mode : affectedModes) {
            schedulingEngine.tryDispatch(mode, stepTime);
        }
    }

    private void processEventsAtSec(LocalDateTime stepTime) {
        if (!builtinEventsEnabled) {
            return;
        }
        for (SimEventTemplate t : eventTemplates) {
            if (!t.executed && t.timeSec == currentSec) {
                t.executed = true;
                executeTemplate(t, stepTime);
            }
        }
    }

    private void executeTemplate(SimEventTemplate t, LocalDateTime stepTime) {
        System.out.println("Executing Simulation Event: (" + t.type + ", " + t.targetId + ", " + t.chargeType + ", " + t.value + ") at " + t.timeStr);
        
        boolean isCancel = (t.value == 0.0) || "O".equals(t.chargeType) && !"B".equals(t.type);

        if (isCancel) {
            schedulingEngine.cancelOrEndCharge(t.targetId, stepTime);
        } else if ("A".equals(t.type)) {
            ChargingMode mode = "F".equals(t.chargeType) ? ChargingMode.FAST : ChargingMode.TRICKLE;
            schedulingEngine.requestCharge(t.targetId, 1L, "EV-Model", 60.0, 10.0, mode, t.value, stepTime);
        } else if ("C".equals(t.type)) {
            if ("F".equals(t.chargeType) || "T".equals(t.chargeType)) {
                ChargingMode newMode = "F".equals(t.chargeType) ? ChargingMode.FAST : ChargingMode.TRICKLE;
                schedulingEngine.changeRequest(t.targetId, newMode, t.value, stepTime);
            } else {
                schedulingEngine.changeRequest(t.targetId, null, t.value, stepTime);
            }
        } else if ("B".equals(t.type)) {
            String mappedPileId = t.targetId.startsWith("T") ? "慢充" + t.targetId.substring(1) : "快充" + t.targetId.substring(1);
            schedulingEngine.reportBreakdown(mappedPileId, stepTime);
            pileRecoveryTimes.put(mappedPileId, currentSec + (int) (t.value * 60));
        }
    }

    /**
     * Skip simulation time straight to the next scheduled unexecuted event
     */
    @Transactional
    public void nextEvent() {
        synchronized (simLock) {
            if (!isInitialized) {
                reset();
            }
            lockMode();

            if (!builtinEventsEnabled) {
                return;
            }

            processInitialEventsIfPending();

            SimEventTemplate next = eventTemplates.stream()
                    .filter(t -> !t.executed && t.timeSec > currentSec)
                    .min(Comparator.comparingInt(t -> t.timeSec))
                    .orElse(null);

            if (next != null) {
                int secondsToAdvance = next.timeSec - currentSec;
                doTick(secondsToAdvance);
            } else {
                int targetSec = 11 * 3600;
                if (currentSec < targetSec) {
                    doTick(targetSec - currentSec);
                }
            }
        }
    }
}
