package com.chargingpile.service;

import com.chargingpile.entity.*;
import com.chargingpile.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SchedulingEngine {

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final ChargingPileRepository chargingPileRepository;
    private final QueueRecordRepository queueRecordRepository;
    private final RechargeRecordRepository rechargeRecordRepository;
    private final DetailListRepository detailListRepository;
    private final BillRepository billRepository;
    private final BillingEngine billingEngine;

    public SchedulingEngine(UserRepository userRepository,
                            VehicleRepository vehicleRepository,
                            ChargingPileRepository chargingPileRepository,
                            QueueRecordRepository queueRecordRepository,
                            RechargeRecordRepository rechargeRecordRepository,
                            DetailListRepository detailListRepository,
                            BillRepository billRepository,
                            BillingEngine billingEngine) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.chargingPileRepository = chargingPileRepository;
        this.queueRecordRepository = queueRecordRepository;
        this.rechargeRecordRepository = rechargeRecordRepository;
        this.detailListRepository = detailListRepository;
        this.billRepository = billRepository;
        this.billingEngine = billingEngine;
    }

    // Configurable parameters
    private int maxWaitingAreaSize = 10; // N
    private int maxQueueLen = 3;         // M (includes the one charging, so 1 active + 2 waiting)

    private final Object lock = new Object();

    /**
     * 设置等候区容量 N 和充电桩排队队列长度 M
     */
    public void setParameters(int waitingAreaSize, int chargingQueueLen) {
        synchronized (lock) {
            this.maxWaitingAreaSize = waitingAreaSize;
            this.maxQueueLen = chargingQueueLen;
        }
    }

    /**
     * 1. 发起充电请求 (requestCharge)
     * 后置条件：
     * - 创建/更新车辆充电状态为 WAITING_IN_AREA；
     * - 生成排队号（F类或T类）；
     * - 车辆加入等候区排队队列；
     * - 尝试触发调度。
     */
    @Transactional
    public Vehicle requestCharge(String vehicleId, Long userId, String vehicleType,
                                 Double batteryCapacity, Double currentCapacity,
                                 ChargingMode mode, Double requestedAmount, LocalDateTime time) {
        synchronized (lock) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

            // 校验等候区容量（快充/慢充共用同一等候区，总容量 N）
            long currentWaitingCount = queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA").size();
            if (currentWaitingCount >= maxWaitingAreaSize) {
                throw new IllegalStateException("Waiting area is full (max " + maxWaitingAreaSize + " vehicles)");
            }

            // 获取下一个排队号码自增序列
            String queueNum = generateNextQueueNum(mode);

            Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
            if (vehicle != null) {
                VehicleState state = vehicle.getState();
                if (state == VehicleState.WAITING_IN_AREA
                        || state == VehicleState.QUEUING_IN_PILE
                        || state == VehicleState.CHARGING) {
                    throw new IllegalStateException(
                            "Vehicle " + vehicleId + " already has an active charging request. Use change or cancel instead.");
                }
            }

            if (vehicle == null) {
                vehicle = Vehicle.builder()
                        .id(vehicleId)
                        .user(user)
                        .vehicleType(vehicleType)
                        .batteryCapacity(batteryCapacity)
                        .currentCapacity(currentCapacity)
                        .chargeMode(mode)
                        .requestedAmount(requestedAmount)
                        .requestTime(time)
                        .queueNum(queueNum)
                        .state(VehicleState.WAITING_IN_AREA)
                        .build();
            } else {
                vehicle.setChargeMode(mode);
                vehicle.setRequestedAmount(requestedAmount);
                vehicle.setRequestTime(time);
                vehicle.setQueueNum(queueNum);
                vehicle.setState(VehicleState.WAITING_IN_AREA);
                vehicle.setCurrentPile(null);
            }
            vehicle = vehicleRepository.save(vehicle);

            // 创建等候区队列记录
            int nextPos = getNextQueuePosition("WAITING_AREA", null);
            QueueRecord qr = QueueRecord.builder()
                    .vehicle(vehicle)
                    .queueType("WAITING_AREA")
                    .queueNum(queueNum)
                    .position(nextPos)
                    .entryTime(time)
                    .build();
            queueRecordRepository.save(qr);

            System.out.println("[" + time + "] Vehicle " + vehicleId + " entered WAITING_AREA with queue number " + queueNum);

            // 尝试触发调度
            tryDispatch(mode, time);

            return vehicleRepository.findById(vehicleId).orElse(vehicle);
        }
    }

    /**
     * 2. 取消/中断充电请求 (cancelRecharge / endCharging)
     * 无论是等候区等待还是在桩充电，均允许取消
     */
    @Transactional
    public Vehicle cancelOrEndCharge(String vehicleId, LocalDateTime time) {
        synchronized (lock) {
            Vehicle vehicle = vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

            VehicleState oldState = vehicle.getState();
            if (oldState == VehicleState.FINISHED || oldState == VehicleState.CANCELLED) {
                return vehicle;
            }

            ChargingPile pile = vehicle.getCurrentPile();
            ChargingMode mode = vehicle.getChargeMode();
            Optional<QueueRecord> queueOpt = queueRecordRepository.findFirstByVehicle_Id(vehicleId);

            // 1. 如果在充电桩（正在充电或排队等待中）
            if (oldState == VehicleState.CHARGING || oldState == VehicleState.QUEUING_IN_PILE) {
                // 1a. 故障优先调度队列中的车辆（无物理桩绑定）
                if (pile == null && queueOpt.isPresent() && "FAULT_QUEUE".equals(queueOpt.get().getQueueType())) {
                    queueRecordRepository.deleteByVehicleId(vehicleId);
                    vehicle.setState(VehicleState.CANCELLED);
                    vehicle.setCurrentPile(null);
                    vehicleRepository.save(vehicle);
                    System.out.println("[" + time + "] Vehicle " + vehicleId + " cancelled from fault dispatch queue.");
                    reorderFaultQueue();
                    tryDispatch(mode, time);
                    return vehicle;
                }

                if (pile == null) {
                    pile = queueOpt.map(QueueRecord::getPile).orElse(null);
                }
                if (pile != null) {
                    boolean wasCharging = (oldState == VehicleState.CHARGING);
                    
                    // 如果正在充电中，执行停止充电，并计算费用生成详单
                    if (wasCharging) {
                        stopChargingAndGenerateDetail(vehicle, pile, time);
                    }

                    // 从物理队列和数据库排队记录中移除
                    queueRecordRepository.deleteByVehicleId(vehicleId);
                    vehicle.setState(VehicleState.FINISHED);
                    vehicle.setCurrentPile(null);
                    vehicleRepository.save(vehicle);

                    System.out.println("[" + time + "] Vehicle " + vehicleId + " left pile " + pile.getId() + " (state: " + oldState + ")");

                    // 重新整理该充电桩物理队列的位置
                    reorderPileQueue(pile.getId());

                    // 启动下一辆车充电 (如果队列中还有车)
                    startNextVehicleInPile(pile, time);

                    // 释放了一个空位，尝试为该类型调度新车
                    tryDispatch(mode, time);
                }
            } 
            // 2. 如果仍在等候区（或故障优先队列中）
            else if (oldState == VehicleState.WAITING_IN_AREA) {
                queueRecordRepository.deleteByVehicleId(vehicleId);
                vehicle.setState(VehicleState.CANCELLED);
                vehicleRepository.save(vehicle);
                System.out.println("[" + time + "] Vehicle " + vehicleId + " cancelled from waiting area.");
                
                // 重新理顺等候区排位
                reorderWaitingAreaQueue();
            }

            return vehicle;
        }
    }

    /**
     * 仅供仿真秒推调用：结束充电并生成详单，但不触发调度。
     * 调用方在批量结束后再统一 tryDispatch，保证同 tick 内多个空位同时可见。
     */
    @Transactional
    public void finishChargingNoDispatch(String vehicleId, LocalDateTime time) {
        synchronized (lock) {
            Vehicle vehicle = vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

            VehicleState oldState = vehicle.getState();
            if (oldState != VehicleState.CHARGING) {
                return;
            }
            ChargingPile pile = vehicle.getCurrentPile();
            if (pile == null) {
                return;
            }

            stopChargingAndGenerateDetail(vehicle, pile, time);

            queueRecordRepository.deleteByVehicleId(vehicleId);
            vehicle.setState(VehicleState.FINISHED);
            vehicle.setCurrentPile(null);
            vehicleRepository.save(vehicle);

            System.out.println("[" + time + "] Vehicle " + vehicleId + " left pile " + pile.getId() + " (fully charged, dispatch deferred)");

            reorderPileQueue(pile.getId());
            startNextVehicleInPile(pile, time);
        }
    }

    /**
     * 3. 改变充电请求 (changeRequest)
     * 支持在等候区修改模式（重新排队生成新号）或仅修改充电量
     */
    @Transactional
    public Vehicle changeRequest(String vehicleId, ChargingMode newMode, Double newAmount, LocalDateTime time) {
        synchronized (lock) {
            Vehicle vehicle = vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

            VehicleState state = vehicle.getState();
            if (state == VehicleState.FINISHED || state == VehicleState.CANCELLED) {
                throw new IllegalStateException("Change request is not allowed for finished/cancelled vehicles.");
            }

            ChargingMode oldMode = vehicle.getChargeMode();

            if (state == VehicleState.WAITING_IN_AREA) {
                if (newMode != null && newMode != oldMode) {
                    // 1. 改变了充电模式：需要从旧模式队列中移除，重新排入新模式等候区末尾，且生成新号
                    queueRecordRepository.deleteByVehicleId(vehicleId);
                    reorderWaitingAreaQueue();

                    String newQueueNum = generateNextQueueNum(newMode);
                    vehicle.setChargeMode(newMode);
                    vehicle.setQueueNum(newQueueNum);
                    if (newAmount != null && newAmount > 0) {
                        applyNewRequestedAmount(vehicle, newAmount);
                    }
                    vehicleRepository.save(vehicle);

                    int nextPos = getNextQueuePosition("WAITING_AREA", null);
                    QueueRecord qr = QueueRecord.builder()
                            .vehicle(vehicle)
                            .queueType("WAITING_AREA")
                            .queueNum(newQueueNum)
                            .position(nextPos)
                            .entryTime(time)
                            .build();
                    queueRecordRepository.save(qr);

                    System.out.println("[" + time + "] Vehicle " + vehicleId + " changed mode from " + oldMode + " to " + newMode + ", new Queue No: " + newQueueNum);

                    // 两边都尝试调度
                    tryDispatch(oldMode, time);
                    tryDispatch(newMode, time);
                } else {
                    // 2. 仅修改充电量，或同类型下仅改电量
                    if (newMode != null && newMode == oldMode) {
                        throw new IllegalStateException("当前已是" + (oldMode == ChargingMode.FAST ? "快充" : "慢充") + "模式，如需变更请选择另一种类型，或仅修改目标度数。");
                    }
                    if (newAmount != null && newAmount > 0) {
                        applyNewRequestedAmount(vehicle, newAmount);
                        vehicleRepository.save(vehicle);
                        System.out.println("[" + time + "] Vehicle " + vehicleId + " changed requested amount to " + newAmount);
                        tryDispatch(oldMode, time);
                    }
                }
            } else {
                // state is QUEUING_IN_PILE or CHARGING
                if (newMode != null && newMode != oldMode) {
                    throw new IllegalStateException("Changing charging mode is not allowed once dispatched to pile.");
                }
                if (newAmount != null && newAmount > 0) {
                    applyNewRequestedAmount(vehicle, newAmount);
                    vehicleRepository.save(vehicle);
                    System.out.println("[" + time + "] Vehicle " + vehicleId + " (in pile) changed requested amount to " + newAmount);
                }
            }

            return vehicle;
        }
    }

    /** 新目标度数不得小于各充电记录已累计电量之和（含进行中会话） */
    private void applyNewRequestedAmount(Vehicle vehicle, double newAmount) {
        double chargedSoFar = getAccumulatedChargedAmount(vehicle.getId());
        if (newAmount < chargedSoFar - 1e-9) {
            throw new IllegalStateException(
                    String.format("修改度数不能小于已充电量（当前已充 %.2f 度）", chargedSoFar));
        }
        vehicle.setRequestedAmount(newAmount);
    }

    private double getAccumulatedChargedAmount(String vehicleId) {
        return rechargeRecordRepository.findByVehicleId(vehicleId).stream()
                .mapToDouble(r -> r.getChargedAmount() != null ? r.getChargedAmount() : 0.0)
                .sum();
    }

    /**
     * 4. 充电桩故障发生 (breakdown)
     * 后置条件（策略 A）：
     * - 暂停等候区对应类型的叫号服务（冻结）；
     * - 正在该充电桩充电的车辆强制停止充电，停止计费并保存已充部分的详单；
     * - 驱逐该充电桩物理队列中的其余等待车辆，将这些被驱逐的车辆全部加入“故障优先调度队列” (FAULT_QUEUE)；
     * - 优先为其提供调度（到其他同类型非故障充电桩），直至全部调度完毕才解冻等候区叫号。
     */
    @Transactional
    public void reportBreakdown(String pileId, LocalDateTime time) {
        synchronized (lock) {
            ChargingPile pile = chargingPileRepository.findById(pileId)
                    .orElseThrow(() -> new IllegalArgumentException("Charging pile not found: " + pileId));

            if (pile.getState() == PileState.FAULT) {
                return;
            }

            pile.setState(PileState.FAULT);
            chargingPileRepository.save(pile);
            System.out.println("[" + time + "] Breakdown: Charging pile " + pileId + " is broken.");

            ChargingMode mode = pile.getType();

            // 获取该充电桩的所有排队记录 (包含正在充电的和排队等待的)
            List<QueueRecord> pileQueue = queueRecordRepository.findByPileIdOrderByPositionAsc(pileId);
            List<Vehicle> evictedVehicles = new ArrayList<>();

            for (QueueRecord qr : pileQueue) {
                Vehicle v = qr.getVehicle();
                evictedVehicles.add(v);

                // 1. 如果是正在充电的车辆，强制结算
                if (v.getState() == VehicleState.CHARGING) {
                    stopChargingAndGenerateDetail(v, pile, time);
                }

                // 2. 解除与故障桩的物理绑定
                v.setState(VehicleState.QUEUING_IN_PILE); // 逻辑置为故障待调度
                v.setCurrentPile(null);
                vehicleRepository.save(v);
            }

            // 彻底清理该故障桩的排队记录
            for (QueueRecord qr : pileQueue) {
                queueRecordRepository.delete(qr);
            }

            if (!evictedVehicles.isEmpty()) {
                System.out.println("[" + time + "] Evicted vehicles from broken pile " + pileId + ": " + evictedVehicles.size() + " cars.");
                
                // 将被驱逐车辆全部按顺序写入 FAULT_QUEUE 故障优先等待调度表
                for (int i = 0; i < evictedVehicles.size(); i++) {
                    Vehicle ev = evictedVehicles.get(i);
                    int nextPos = getNextQueuePosition("FAULT_QUEUE", null);
                    QueueRecord fqr = QueueRecord.builder()
                            .vehicle(ev)
                            .queueType("FAULT_QUEUE")
                            .queueNum(ev.getQueueNum())
                            .position(nextPos)
                            .entryTime(time)
                            .build();
                    queueRecordRepository.save(fqr);
                }

                // 执行故障调度 A
                tryDispatch(mode, time);
            }
        }
    }

    /**
     * 管理员关闭充电桩（OFFLINE）：桩上车辆停充/退队后回到主等候区，再尝试同类型重调度。
     */
    @Transactional
    public void shutdownPile(String pileId, LocalDateTime time) {
        synchronized (lock) {
            ChargingPile pile = chargingPileRepository.findById(pileId)
                    .orElseThrow(() -> new IllegalArgumentException("Charging pile not found: " + pileId));

            if (pile.getState() != PileState.RUNNING) {
                throw new IllegalStateException("Only RUNNING piles can be shut down: " + pileId);
            }

            pile.setState(PileState.OFFLINE);
            chargingPileRepository.save(pile);
            System.out.println("[" + time + "] Shutdown: Charging pile " + pileId + " is now OFFLINE.");

            ChargingMode mode = pile.getType();
            List<QueueRecord> pileQueue = new ArrayList<>(queueRecordRepository.findByPileIdOrderByPositionAsc(pileId));

            for (QueueRecord qr : pileQueue) {
                Vehicle v = qr.getVehicle();
                if (v.getState() == VehicleState.CHARGING) {
                    stopChargingAndGenerateDetail(v, pile, time);
                }
                queueRecordRepository.delete(qr);
                returnVehicleToWaitingArea(v, time);
            }

            reorderWaitingAreaQueue();
            tryDispatch(mode, time);
        }
    }

    /**
     * 管理员启动已关闭的充电桩（OFFLINE → RUNNING）。
     */
    @Transactional
    public void startPile(String pileId, LocalDateTime time) {
        synchronized (lock) {
            ChargingPile pile = chargingPileRepository.findById(pileId)
                    .orElseThrow(() -> new IllegalArgumentException("Charging pile not found: " + pileId));

            if (pile.getState() != PileState.OFFLINE) {
                throw new IllegalStateException("Only OFFLINE piles can be started: " + pileId);
            }

            pile.setState(PileState.RUNNING);
            chargingPileRepository.save(pile);
            System.out.println("[" + time + "] Start: Charging pile " + pileId + " is back to RUNNING.");
            tryDispatch(pile.getType(), time);
        }
    }

    private void returnVehicleToWaitingArea(Vehicle vehicle, LocalDateTime time) {
        vehicle.setState(VehicleState.WAITING_IN_AREA);
        vehicle.setCurrentPile(null);
        vehicleRepository.save(vehicle);

        int nextPos = getNextQueuePosition("WAITING_AREA", null);
        QueueRecord qr = QueueRecord.builder()
                .vehicle(vehicle)
                .queueType("WAITING_AREA")
                .queueNum(vehicle.getQueueNum())
                .position(nextPos)
                .entryTime(time)
                .build();
        queueRecordRepository.save(qr);
        System.out.println("[" + time + "] Vehicle " + vehicle.getId() + " returned to WAITING_AREA after pile shutdown.");
    }

    /**
     * 5. 核心调度算法逻辑 (tryDispatch)
     */
    public void tryDispatch(ChargingMode mode, LocalDateTime time) {
        synchronized (lock) {
            // 首先判断该类型下是否有故障队列中的车辆 (FAULT_QUEUE) 需要优先调度
            List<QueueRecord> faultQueue = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE");
            List<QueueRecord> matchingFaults = faultQueue.stream()
                    .filter(qr -> qr.getVehicle().getChargeMode() == mode)
                    .toList();

            // 1. 如果有故障优先调度的车辆
            if (!matchingFaults.isEmpty()) {
                System.out.println("[" + time + "] Dispatch: matching faults found for mode " + mode + ": " + matchingFaults.size() + " cars.");
                for (QueueRecord fqr : matchingFaults) {
                    // 寻找该模式下，未故障且队列不满 (数量 < M) 的充电桩
                    List<ChargingPile> availPiles = getAvailablePiles(mode);
                    if (availPiles.isEmpty()) {
                        System.out.println("[" + time + "] Dispatch: No available piles of type " + mode + " to receive fault vehicle. Waiting area remains FROZEN.");
                        return; // 其他正常桩全满，叫号继续冻结
                    }

                    // 选择最优充电桩：能让该车 (等待时间 + 自己充电时间) 最短的桩
                    ChargingPile bestPile = selectBestPile(availPiles, fqr.getVehicle(), time);

                    // 将车从故障队列中出队
                    queueRecordRepository.delete(fqr);

                    // 调度进最优充电桩
                    dispatchVehicleToPile(fqr.getVehicle(), bestPile, time);
                }

                // 递归再检查一下是否已经完全调空故障队列了
                reorderFaultQueue();
            }

            // 2. 如果故障队列完全为空，则可以解冻，并从等候区 (WAITING_AREA) 叫号
            List<QueueRecord> remainingFaults = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE").stream()
                    .filter(qr -> qr.getVehicle().getChargeMode() == mode)
                    .toList();

            if (remainingFaults.isEmpty()) {
                dispatchWaitingArea(mode, time);
            }
        }
    }

    /**
     * 故障恢复后：合并同类型各桩未开充车辆与故障队列，按排队号重调度，再进入 tryDispatch。
     */
    @Transactional
    public void redispatchAfterRecovery(ChargingMode mode, LocalDateTime time) {
        synchronized (lock) {
            System.out.println("[" + time + "] Recovery merge redispatch for mode " + mode);

            List<Vehicle> candidates = new ArrayList<>();

            List<QueueRecord> faultQueue = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE");
            for (QueueRecord qr : faultQueue) {
                if (qr.getVehicle().getChargeMode() == mode) {
                    candidates.add(qr.getVehicle());
                }
            }

            List<ChargingPile> modePiles = chargingPileRepository.findByType(mode);
            for (ChargingPile pile : modePiles) {
                if (pile.getState() != PileState.RUNNING) {
                    continue;
                }
                List<QueueRecord> pQueue = queueRecordRepository.findByPileIdOrderByPositionAsc(pile.getId());
                for (int i = 1; i < pQueue.size(); i++) {
                    candidates.add(pQueue.get(i).getVehicle());
                }
            }

            LinkedHashMap<String, Vehicle> unique = new LinkedHashMap<>();
            for (Vehicle v : candidates) {
                unique.putIfAbsent(v.getId(), v);
            }
            List<Vehicle> sorted = new ArrayList<>(unique.values());
            sorted.sort((a, b) -> compareQueueNum(a.getQueueNum(), b.getQueueNum()));

            for (Vehicle v : sorted) {
                queueRecordRepository.deleteByVehicleId(v.getId());
                v.setCurrentPile(null);
                v.setState(VehicleState.QUEUING_IN_PILE);
                vehicleRepository.save(v);
            }

            for (Vehicle v : sorted) {
                List<ChargingPile> availPiles = getAvailablePiles(mode);
                if (availPiles.isEmpty()) {
                    enqueueFaultQueue(v, time);
                    continue;
                }
                ChargingPile bestPile = selectBestPile(availPiles, v, time);
                dispatchVehicleToPile(v, bestPile, time);
            }

            reorderFaultQueue();
            for (ChargingPile pile : modePiles) {
                reorderPileQueue(pile.getId());
            }

            tryDispatch(mode, time);
        }
    }

    private void enqueueFaultQueue(Vehicle vehicle, LocalDateTime time) {
        vehicle.setCurrentPile(null);
        vehicle.setState(VehicleState.QUEUING_IN_PILE);
        vehicleRepository.save(vehicle);
        int nextPos = getNextQueuePosition("FAULT_QUEUE", null);
        QueueRecord fqr = QueueRecord.builder()
                .vehicle(vehicle)
                .queueType("FAULT_QUEUE")
                .queueNum(vehicle.getQueueNum())
                .position(nextPos)
                .entryTime(time)
                .build();
        queueRecordRepository.save(fqr);
    }

    /**
     * 等候区叫号：空位≥2 且等候车≥2 时走扩展①批量最优，否则逐车贪心 S 策略。
     */
    private void dispatchWaitingArea(ChargingMode mode, LocalDateTime time) {
        List<QueueRecord> matchingWaiters = queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA").stream()
                .filter(qr -> qr.getVehicle().getChargeMode() == mode)
                .toList();
        if (matchingWaiters.isEmpty()) {
            return;
        }

        int totalFreeSlots = countTotalFreeSlots(mode);
        if (totalFreeSlots >= 2 && matchingWaiters.size() >= 2) {
            List<Vehicle> allCandidates = matchingWaiters.stream().map(QueueRecord::getVehicle).toList();
            int batchSize = Math.min(totalFreeSlots, allCandidates.size());

            Map<Vehicle, ChargingPile> bestAssignment = Collections.emptyMap();
            double bestTotalCost = Double.MAX_VALUE;

            // 枚举所有 size=batchSize 的子集，对各子集做最优分配，选总时间最短的
            List<List<Vehicle>> subsets = enumerateSubsets(allCandidates, batchSize);
            for (List<Vehicle> subset : subsets) {
                Map<Vehicle, ChargingPile> candidate = assignBatchMinTotalTime(subset, mode, time);
                if (candidate.isEmpty()) {
                    continue;
                }
                double totalCost = 0.0;
                for (Vehicle v : subset) {
                    ChargingPile p = candidate.get(v);
                    if (p != null) {
                        totalCost += computeVehiclePileCost(p, v, 0.0, time);
                    }
                }
                if (totalCost < bestTotalCost - 1e-9) {
                    bestTotalCost = totalCost;
                    bestAssignment = candidate;
                }
            }

            if (!bestAssignment.isEmpty()) {
                System.out.println("[" + time + "] Extension-1 batch dispatch for mode " + mode + ": " + allCandidates.size() + " candidates, " + bestAssignment.size() + " assigned. Best cost: " + String.format("%.2f", bestTotalCost) + "h");
                java.util.Set<String> assignedIds = new java.util.HashSet<>();
                for (Map.Entry<Vehicle, ChargingPile> entry : bestAssignment.entrySet()) {
                    Vehicle v = entry.getKey();
                    ChargingPile pile = entry.getValue();
                    queueRecordRepository.deleteByVehicleId(v.getId());
                    dispatchVehicleToPile(v, pile, time);
                    assignedIds.add(v.getId());
                }
                if (!assignedIds.isEmpty()) {
                    matchingWaiters = new java.util.ArrayList<>(matchingWaiters);
                    matchingWaiters.removeIf(qr -> assignedIds.contains(qr.getVehicle().getId()));
                }
            }
        }

        dispatchWaitingAreaGreedy(mode, time);
        reorderWaitingAreaQueue();
    }

    private void dispatchWaitingAreaGreedy(ChargingMode mode, LocalDateTime time) {
        List<QueueRecord> matchingWaiters = queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA").stream()
                .filter(qr -> qr.getVehicle().getChargeMode() == mode)
                .toList();

        for (QueueRecord waqr : matchingWaiters) {
            List<ChargingPile> availPiles = getAvailablePiles(mode);
            if (availPiles.isEmpty()) {
                break;
            }
            ChargingPile bestPile = selectBestPile(availPiles, waqr.getVehicle(), time);
            queueRecordRepository.delete(waqr);
            dispatchVehicleToPile(waqr.getVehicle(), bestPile, time);
        }
    }

    private int countTotalFreeSlots(ChargingMode mode) {
        int total = 0;
        for (ChargingPile p : chargingPileRepository.findByType(mode)) {
            if (p.getState() == PileState.RUNNING) {
                int used = queueRecordRepository.findByPileIdOrderByPositionAsc(p.getId()).size();
                total += Math.max(0, maxQueueLen - used);
            }
        }
        return total;
    }

    private Map<Vehicle, ChargingPile> assignBatchMinTotalTime(List<Vehicle> vehicles, ChargingMode mode, LocalDateTime time) {
        Map<String, ChargingPile> pileById = new LinkedHashMap<>();
        Map<String, Integer> remainingSlots = new LinkedHashMap<>();
        for (ChargingPile p : chargingPileRepository.findByType(mode)) {
            if (p.getState() != PileState.RUNNING) {
                continue;
            }
            int free = maxQueueLen - queueRecordRepository.findByPileIdOrderByPositionAsc(p.getId()).size();
            if (free > 0) {
                pileById.put(p.getId(), p);
                remainingSlots.put(p.getId(), free);
            }
        }

        Map<Vehicle, ChargingPile> bestAssignment = new HashMap<>();
        Map<Vehicle, ChargingPile> currentAssignment = new HashMap<>();
        Map<String, Double> virtualWaitHours = new HashMap<>();
        for (String pileId : remainingSlots.keySet()) {
            virtualWaitHours.put(pileId, 0.0);
        }
        double[] bestCost = {Double.MAX_VALUE};

        assignBatchRecursive(vehicles, 0, remainingSlots, pileById, virtualWaitHours, time,
                0.0, currentAssignment, bestAssignment, bestCost);

        return bestCost[0] == Double.MAX_VALUE ? Collections.emptyMap() : bestAssignment;
    }

    private void assignBatchRecursive(List<Vehicle> vehicles, int index,
                                    Map<String, Integer> remainingSlots,
                                    Map<String, ChargingPile> pileById,
                                    Map<String, Double> virtualWaitHours,
                                    LocalDateTime time,
                                    double currentCost,
                                    Map<Vehicle, ChargingPile> currentAssignment,
                                    Map<Vehicle, ChargingPile> bestAssignment,
                                    double[] bestCost) {
        if (index == vehicles.size()) {
            if (currentCost < bestCost[0] - 1e-9) {
                bestCost[0] = currentCost;
                bestAssignment.clear();
                bestAssignment.putAll(currentAssignment);
            }
            return;
        }

        Vehicle vehicle = vehicles.get(index);
        for (Map.Entry<String, Integer> entry : remainingSlots.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            String pileId = entry.getKey();
            ChargingPile pile = pileById.get(pileId);
            double virtualWait = virtualWaitHours.get(pileId);
            double assignmentCost = computeVehiclePileCost(pile, vehicle, virtualWait, time);

            entry.setValue(entry.getValue() - 1);
            virtualWaitHours.put(pileId, virtualWait + vehicle.getRequestedAmount() / pile.getPower());
            currentAssignment.put(vehicle, pile);

            assignBatchRecursive(vehicles, index + 1, remainingSlots, pileById, virtualWaitHours, time,
                    currentCost + assignmentCost, currentAssignment, bestAssignment, bestCost);

            currentAssignment.remove(vehicle);
            virtualWaitHours.put(pileId, virtualWait);
            entry.setValue(entry.getValue() + 1);
        }
    }

    private double computeVehiclePileCost(ChargingPile pile, Vehicle vehicle, double virtualWaitHours, LocalDateTime time) {
        double waitTimeHours = virtualWaitHours;
        List<QueueRecord> pQueue = queueRecordRepository.findByPileIdOrderByPositionAsc(pile.getId());
        for (QueueRecord qr : pQueue) {
            Vehicle qv = qr.getVehicle();
            double remAmount = qv.getRequestedAmount() - getCurrentlyChargedAmountAtTime(qv, time);
            waitTimeHours += remAmount / pile.getPower();
        }
        double selfTimeHours = vehicle.getRequestedAmount() / pile.getPower();
        return waitTimeHours + selfTimeHours;
    }

    /**
     * 枚举列表中所有大小为 k 的子集（组合 C(n,k)）。
     */
    private <T> List<List<T>> enumerateSubsets(List<T> items, int k) {
        List<List<T>> result = new ArrayList<>();
        if (k <= 0 || k > items.size()) {
            return result;
        }
        backtrackSubsets(items, 0, k, new ArrayList<>(), result);
        return result;
    }

    private <T> void backtrackSubsets(List<T> items, int start, int k,
                                      List<T> current, List<List<T>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i <= items.size() - (k - current.size()); i++) {
            current.add(items.get(i));
            backtrackSubsets(items, i + 1, k, current, result);
            current.remove(current.size() - 1);
        }
    }

    private int compareQueueNum(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        if (a.length() < 2 || b.length() < 2) {
            return a.compareTo(b);
        }
        char prefixA = a.charAt(0);
        char prefixB = b.charAt(0);
        if (prefixA != prefixB) {
            return Character.compare(prefixA, prefixB);
        }
        try {
            int numA = Integer.parseInt(a.substring(1));
            int numB = Integer.parseInt(b.substring(1));
            return Integer.compare(numA, numB);
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private List<ChargingPile> getAvailablePiles(ChargingMode mode) {
        List<ChargingPile> piles = chargingPileRepository.findByType(mode);
        List<ChargingPile> avail = new ArrayList<>();
        for (ChargingPile p : piles) {
            if (p.getState() == PileState.RUNNING) {
                long currentCount = queueRecordRepository.findByPileIdOrderByPositionAsc(p.getId()).size();
                if (currentCount < maxQueueLen) {
                    avail.add(p);
                }
            }
        }
        return avail;
    }

    private ChargingPile selectBestPile(List<ChargingPile> availPiles, Vehicle vehicle, LocalDateTime time) {
        ChargingPile best = null;
        double minTotalTime = Double.MAX_VALUE;

        for (ChargingPile p : availPiles) {
            double totalTime = computeVehiclePileCost(p, vehicle, 0.0, time);
            // 采用 S 策略，若时间相同，按 pile id 字母升序防歧义
            if (totalTime < minTotalTime || (Math.abs(totalTime - minTotalTime) < 1e-9 && (best == null || p.getId().compareTo(best.getId()) < 0))) {
                minTotalTime = totalTime;
                best = p;
            }
        }
        return best;
    }

    private double getCurrentlyChargedAmount(Vehicle vehicle) {
        return getCurrentlyChargedAmountAtTime(vehicle, null);
    }

    private double getCurrentlyChargedAmountAtTime(Vehicle vehicle, LocalDateTime currentTime) {
        List<RechargeRecord> recs = rechargeRecordRepository.findByVehicleId(vehicle.getId());
        double total = 0.0;
        for (RechargeRecord r : recs) {
            if (r.getEndTime() != null) {
                total += r.getChargedAmount();
            } else if (currentTime != null) {
                long durationSec = Duration.between(r.getStartTime(), currentTime).getSeconds();
                double powerPerSec = r.getPile().getPower() / 3600.0;
                double currentSessionCharged = durationSec * powerPerSec;
                
                double alreadyChargedBeforeThis = 0.0;
                for (RechargeRecord prior : recs) {
                    if (prior.getEndTime() != null) {
                        alreadyChargedBeforeThis += prior.getChargedAmount();
                    }
                }
                double remainingToCharge = vehicle.getRequestedAmount() - alreadyChargedBeforeThis;
                if (currentSessionCharged > remainingToCharge) {
                    currentSessionCharged = remainingToCharge;
                }
                total += currentSessionCharged;
            }
        }
        return total;
    }

    private void dispatchVehicleToPile(Vehicle vehicle, ChargingPile pile, LocalDateTime time) {
        vehicle.setCurrentPile(pile);
        vehicle.setState(VehicleState.QUEUING_IN_PILE);
        vehicleRepository.save(vehicle);

        List<QueueRecord> pQueue = queueRecordRepository.findByPileIdOrderByPositionAsc(pile.getId());
        int nextPos = pQueue.size();

        QueueRecord qr = QueueRecord.builder()
                .vehicle(vehicle)
                .queueType("PILE_QUEUE")
                .pile(pile)
                .queueNum(vehicle.getQueueNum())
                .position(nextPos)
                .entryTime(time)
                .build();
        queueRecordRepository.save(qr);

        System.out.println("[" + time + "] Dispatch: Vehicle " + vehicle.getId() + " (Queue No: " + vehicle.getQueueNum() + ") dispatched to pile " + pile.getId());

        // 如果物理队列该车是排第一位，立刻唤醒并启动充电
        if (nextPos == 0) {
            startCharging(vehicle, pile, time);
        }
    }

    private void startCharging(Vehicle vehicle, ChargingPile pile, LocalDateTime time) {
        vehicle.setState(VehicleState.CHARGING);
        vehicleRepository.save(vehicle);

        RechargeRecord record = RechargeRecord.builder()
                .vehicle(vehicle)
                .pile(pile)
                .startTime(time)
                .chargedAmount(0.0)
                .chargeCost(0.0)
                .serviceCost(0.0)
                .totalCost(0.0)
                .build();
        rechargeRecordRepository.save(record);

        System.out.println("[" + time + "] Start Recharge: Vehicle " + vehicle.getId() + " starts charging on pile " + pile.getId());
    }

    private void stopChargingAndGenerateDetail(Vehicle vehicle, ChargingPile pile, LocalDateTime time) {
        // 寻找该车当前的充电记录
        List<RechargeRecord> recs = rechargeRecordRepository.findByVehicleId(vehicle.getId());
        RechargeRecord record = recs.stream()
                .filter(r -> r.getEndTime() == null && r.getPile().getId().equals(pile.getId()))
                .findFirst()
                .orElse(null);

        if (record != null) {
            record.setEndTime(time);

            // 根据物理仿真中设定的该车被充入的实际电量来结算 (通常由仿真事件引擎或者物理反馈累加修改)
            // 在这里我们按业务假设从 startTime 到 endTime 期间匀速充入了 chargedAmount
            long durationSec = Duration.between(record.getStartTime(), time).getSeconds();
            double powerPerSec = pile.getPower() / 3600.0;
            double chargedAmount = durationSec * powerPerSec;
            // 充电量不能多于请求量
            double remToCharge = vehicle.getRequestedAmount() - getCurrentlyChargedAmount(vehicle) + record.getChargedAmount();
            if (chargedAmount > remToCharge) {
                chargedAmount = remToCharge;
            }

            // 调用计费引擎进行时段分拆高精度计费
            BillingEngine.BillingResult br = billingEngine.calculate(record.getStartTime(), time, chargedAmount);

            record.setChargedAmount(br.getChargedAmount());
            record.setChargeCost(br.getTotalChargeCost());
            record.setServiceCost(br.getTotalServiceCost());
            record.setTotalCost(br.getTotalCost());
            rechargeRecordRepository.save(record);

            // 1. 生成详单 DetailList
            String detailNo = "DL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            DetailList detail = DetailList.builder()
                    .detailNo(detailNo)
                    .createdAt(time)
                    .pile(pile)
                    .vehicle(vehicle)
                    .chargedAmount(br.getChargedAmount())
                    .chargeDuration(br.getTotalDurationSeconds())
                    .startTime(record.getStartTime())
                    .endTime(time)
                    .chargeCost(br.getTotalChargeCost())
                    .serviceCost(br.getTotalServiceCost())
                    .totalCost(br.getTotalCost())
                    .build();
            detailListRepository.save(detail);

            // 2. 级联更新充电桩的数据统计
            pile.setChargeCount(pile.getChargeCount() + 1);
            pile.setTotalChargeTime(pile.getTotalChargeTime() + br.getTotalDurationSeconds());
            pile.setTotalChargeAmount(pile.getTotalChargeAmount() + br.getChargedAmount());
            pile.setTotalChargeCost(pile.getTotalChargeCost() + br.getPeakChargeCost() + br.getRegularChargeCost() + br.getOffPeakChargeCost());
            pile.setTotalServiceCost(pile.getTotalServiceCost() + br.getTotalServiceCost());
            pile.setTotalCost(pile.getTotalCost() + br.getTotalCost());
            chargingPileRepository.save(pile);

            // 3. 关联或累加总体账单 Bill（同一车辆多次充电/故障中断共用一个未支付账单）
            Bill bill = billRepository.findFirstByVehicleIdAndIsPaidFalse(vehicle.getId()).orElse(null);
            if (bill == null) {
                String billNo = "B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                bill = Bill.builder()
                        .billNo(billNo)
                        .vehicle(vehicle)
                        .station_name("智能调度充电站")
                        .createdAt(time)
                        .chargedAmount(br.getChargedAmount())
                        .chargeCost(br.getTotalChargeCost())
                        .serviceCost(br.getTotalServiceCost())
                        .parkingFee(0.0)
                        .totalCost(br.getTotalCost())
                        .isPaid(false)
                        .build();
            } else {
                bill.setChargedAmount(bill.getChargedAmount() + br.getChargedAmount());
                bill.setChargeCost(bill.getChargeCost() + br.getTotalChargeCost());
                bill.setServiceCost(bill.getServiceCost() + br.getTotalServiceCost());
                bill.setTotalCost(bill.getTotalCost() + br.getTotalCost());
                bill.setCreatedAt(time);
            }
            billRepository.save(bill);

            detail.setBill(bill);
            detailListRepository.save(detail);

            System.out.println("[" + time + "] Stop Recharge: Vehicle " + vehicle.getId() + " finished this session. DetailNo: " + detailNo + ", BillNo: " + bill.getBillNo());
        }
    }

    private void startNextVehicleInPile(ChargingPile pile, LocalDateTime time) {
        List<QueueRecord> pQueue = queueRecordRepository.findByPileIdOrderByPositionAsc(pile.getId());
        if (!pQueue.isEmpty()) {
            Vehicle nextV = pQueue.get(0).getVehicle();
            if (nextV.getState() == VehicleState.QUEUING_IN_PILE) {
                startCharging(nextV, pile, time);
            }
        }
    }

    private String generateNextQueueNum(ChargingMode mode) {
        String prefix = (mode == ChargingMode.FAST) ? "F" : "T";
        // 查找等候区和桩队列里，目前所有带有该 prefix 的最高序号自增
        List<QueueRecord> qrs = queueRecordRepository.findAll();
        int maxSeq = 0;
        for (QueueRecord qr : qrs) {
            String qn = qr.getQueueNum();
            if (qn != null && qn.startsWith(prefix)) {
                try {
                    int seq = Integer.parseInt(qn.substring(1));
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return prefix + (maxSeq + 1);
    }

    private int getNextQueuePosition(String queueType, String pileId) {
        if ("WAITING_AREA".equals(queueType) || "FAULT_QUEUE".equals(queueType)) {
            return queueRecordRepository.findByQueueTypeOrderByPositionAsc(queueType).size();
        } else {
            return queueRecordRepository.findByPileIdOrderByPositionAsc(pileId).size();
        }
    }

    private void reorderWaitingAreaQueue() {
        List<QueueRecord> qrs = queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA");
        for (int i = 0; i < qrs.size(); i++) {
            qrs.get(i).setPosition(i);
            queueRecordRepository.save(qrs.get(i));
        }
    }

    private void reorderFaultQueue() {
        List<QueueRecord> qrs = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE");
        for (int i = 0; i < qrs.size(); i++) {
            qrs.get(i).setPosition(i);
            queueRecordRepository.save(qrs.get(i));
        }
    }

    private void reorderPileQueue(String pileId) {
        List<QueueRecord> qrs = queueRecordRepository.findByPileIdOrderByPositionAsc(pileId);
        for (int i = 0; i < qrs.size(); i++) {
            qrs.get(i).setPosition(i);
            queueRecordRepository.save(qrs.get(i));
        }
    }
}
