package com.chargingpile.service;

import com.chargingpile.entity.*;
import com.chargingpile.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class SchedulingEngineTest {

    @Autowired
    private SchedulingEngine schedulingEngine;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChargingPileRepository chargingPileRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private QueueRecordRepository queueRecordRepository;

    @Autowired
    private DetailListRepository detailListRepository;

    @Autowired
    private RechargeRecordRepository rechargeRecordRepository;

    private User testUser;

    @BeforeEach
    public void setUp() {
        rechargeRecordRepository.deleteAll();
        queueRecordRepository.deleteAll();
        vehicleRepository.deleteAll();
        chargingPileRepository.deleteAll();
        userRepository.deleteAll();

        // 1. 初始化测试用户
        testUser = User.builder()
                .username("test_driver")
                .password("pass123")
                .role(Role.CUSTOMER)
                .createdAt(LocalDateTime.now())
                .build();
        testUser = userRepository.save(testUser);

        // 2. 初始化 5 个充电桩 (3快，2慢)
        chargingPileRepository.save(ChargingPile.builder().id("快充1").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("快充2").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("快充3").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("慢充1").type(ChargingMode.TRICKLE).power(10.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("慢充2").type(ChargingMode.TRICKLE).power(10.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());

        schedulingEngine.setParameters(10, 3);
    }

    @Test
    public void testRequestCharge_shouldDispatchImmediatelyToEmptyPile() {
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 6, 0, 0);
        
        // V1 申请 40度 慢充
        Vehicle v1 = schedulingEngine.requestCharge("V1", testUser.getId(), "ModelY", 60.0, 20.0, ChargingMode.TRICKLE, 40.0, time);
        
        // 应该生成排队号 T1 且直接分配到 慢充1 并转为 CHARGING 状态
        assertEquals("T1", v1.getQueueNum());
        assertEquals(VehicleState.CHARGING, v1.getState());
        assertNotNull(v1.getCurrentPile());
        assertEquals("慢充1", v1.getCurrentPile().getId());

        // 数据库中的排队排位应该在 慢充1 的第 0 位
        List<QueueRecord> qrList = queueRecordRepository.findByPileIdOrderByPositionAsc("慢充1");
        assertEquals(1, qrList.size());
        assertEquals(0, qrList.get(0).getPosition());
        assertEquals("T1", qrList.get(0).getQueueNum());
    }

    @Test
    public void testRequestCharge_duplicateActiveRequest_shouldReject() {
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 6, 0, 0);
        schedulingEngine.requestCharge("V1", testUser.getId(), "ModelY", 60.0, 20.0, ChargingMode.TRICKLE, 40.0, time);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                schedulingEngine.requestCharge("V1", testUser.getId(), "ModelY", 60.0, 20.0, ChargingMode.TRICKLE, 30.0, time.plusMinutes(5))
        );
        assertTrue(ex.getMessage().contains("already has an active charging request"));

        // 取消后应允许再次提交
        schedulingEngine.cancelOrEndCharge("V1", time.plusMinutes(20));
        Vehicle v1Again = schedulingEngine.requestCharge("V1", testUser.getId(), "ModelY", 60.0, 20.0, ChargingMode.TRICKLE, 30.0, time.plusMinutes(25));
        assertEquals(VehicleState.CHARGING, v1Again.getState());
    }

    @Test
    public void testChangeRequest_amountLessThanCharged_shouldReject() {
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 6, 0, 0);
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type1", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, time);

        RechargeRecord active = rechargeRecordRepository.findByVehicleId("V1").stream()
                .filter(r -> r.getEndTime() == null)
                .findFirst()
                .orElseThrow();
        active.setChargedAmount(20.0);
        rechargeRecordRepository.save(active);

        Vehicle updated = schedulingEngine.changeRequest("V1", null, 25.0, time.plusHours(1));
        assertEquals(25.0, updated.getRequestedAmount(), 1e-9);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                schedulingEngine.changeRequest("V1", null, 10.0, time.plusHours(1))
        );
        assertTrue(ex.getMessage().contains("不能小于已充电量"));
    }

    @Test
    public void testRequestCharge_multipleVehicles_S_StrategyScheduling() {
        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 6, 0, 0);

        // 1. 慢充1 进 V1 (40度)
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type1", 60.0, 10.0, ChargingMode.TRICKLE, 40.0, time);
        // 2. 慢充2 进 V2 (30度)
        schedulingEngine.requestCharge("V2", testUser.getId(), "Type2", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, time.plusMinutes(5));

        // 3. V4 申请 20度：此时 慢充1 (在充剩 40度) 与 慢充2 (在充剩 30度) 都有车。
        // 等待时长对比：
        // - 分配到 慢充1：等待时间 = 40/10 = 4.0 小时
        // - 分配到 慢充2：等待时间 = 30/10 = 3.0 小时
        // 选择 慢充2 耗时最短。
        Vehicle v4 = schedulingEngine.requestCharge("V4", testUser.getId(), "Type4", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, time.plusMinutes(25));

        assertEquals("慢充2", v4.getCurrentPile().getId());
        assertEquals(VehicleState.QUEUING_IN_PILE, v4.getState());

        List<QueueRecord> qrList = queueRecordRepository.findByPileIdOrderByPositionAsc("慢充2");
        assertEquals(2, qrList.size());
        assertEquals("V2", qrList.get(0).getVehicle().getId());
        assertEquals("V4", qrList.get(1).getVehicle().getId()); // V4 在慢充2排队
    }

    @Test
    public void testBreakdown_StrategyA_PriorityDispatch() {
        // 我们在此完美重现官方验收 XLSX 用例在 10:30:00 慢充1 故障的全部队列状态！
        LocalDateTime t0 = LocalDateTime.of(2026, 6, 12, 6, 0, 0);

        // 06:00:00 V1 申请 40度 T (慢充1 在充)
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 40.0, t0);
        // 06:05:00 V2 申请 30度 T (慢充2 在充)
        schedulingEngine.requestCharge("V2", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, t0.plusMinutes(5));
        
        // 06:20:00 V2 取消充电 (慢充2 空闲)
        schedulingEngine.cancelOrEndCharge("V2", t0.plusMinutes(20));

        // 06:25:00 V4 申请 20度 T (慢充2 在充)
        schedulingEngine.requestCharge("V4", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(25));
        // 06:30:00 V5 申请 20度 T (慢充2 排队1)
        schedulingEngine.requestCharge("V5", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(30));
        // 06:40:00 V6 申请 20度 T (慢充1 排队1)
        schedulingEngine.requestCharge("V6", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(40));
        // 06:50:00 V7 申请 10度 T (慢充2 排队2)
        schedulingEngine.requestCharge("V7", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 10.0, t0.plusMinutes(50));
        // 07:15:00 V10 申请 10度 T (慢充1 排队2)
        schedulingEngine.requestCharge("V10", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 10.0, t0.plusMinutes(75));
        
        // 07:25:00 V12 申请 10度 T (等候区 1)
        schedulingEngine.requestCharge("V12", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 10.0, t0.plusMinutes(85));
        // 07:30:00 V13 申请 7.5度 T (等候区 2)
        schedulingEngine.requestCharge("V13", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 7.5, t0.plusMinutes(90));
        // 08:00:00 V16 申请 5度 T (等候区 3)
        schedulingEngine.requestCharge("V16", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 5.0, t0.plusMinutes(120));
        // 08:20:00 V17 申请 15度 T (等候区 4)
        schedulingEngine.requestCharge("V17", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 15.0, t0.plusMinutes(140));

        // 08:25:00 V4 充电满 (2小时) 结算出局。触发：V5转在充，等候区V12进入慢充2排队
        schedulingEngine.cancelOrEndCharge("V4", t0.plusHours(2).plusMinutes(25));

        // 09:10:00 V7 取消。触发：等候区V13进入慢充2排队
        schedulingEngine.cancelOrEndCharge("V7", t0.plusHours(3).plusMinutes(10));

        // 10:00:00 V1 充电满 (4小时) 结算出局。触发：V6转在充，等候区V16进入慢充1排队
        schedulingEngine.cancelOrEndCharge("V1", t0.plusHours(4));

        // 10:25:00 V5 充电满 (2小时) 结算出局。触发：V12转在充，等候区V17进入慢充2排队
        schedulingEngine.cancelOrEndCharge("V5", t0.plusHours(4).plusMinutes(25));

        // 此时 (10:25 ~ 10:30)，
        // 慢充1 队列：V6 (在充), V10 (排队1), V16 (排队2) -- 完全挤满
        // 慢充2 队列：V12 (在充), V13 (排队1), V17 (排队2) -- 完全挤满
        assertEquals(3, queueRecordRepository.findByPileIdOrderByPositionAsc("慢充1").size());
        assertEquals(3, queueRecordRepository.findByPileIdOrderByPositionAsc("慢充2").size());

        // 3. 10:30:00 慢充1 发生故障！
        schedulingEngine.reportBreakdown("慢充1", t0.plusHours(4).plusMinutes(30));

        // 慢充1 状态应变为 FAULT
        ChargingPile cp1 = chargingPileRepository.findById("慢充1").orElse(null);
        assertNotNull(cp1);
        assertEquals(PileState.FAULT, cp1.getState());

        // 应该生成 V6 被强行中断的详单记录
        List<DetailList> dlList = detailListRepository.findByVehicleId("V6");
        assertFalse(dlList.isEmpty());
        // V6 从 10:00 充到 10:30，共 30分钟，应该充满 5度
        assertEquals(5.0, dlList.get(0).getChargedAmount(), 1e-4);

        // 被赶出来的 V6、V10、V16 应该按原本在 慢充1 的顺序被存入 FAULT_QUEUE 故障重调度优先队列
        List<QueueRecord> fq = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE");
        assertEquals(3, fq.size());
        assertEquals("V6", fq.get(0).getVehicle().getId());
        assertEquals("V10", fq.get(1).getVehicle().getId());
        assertEquals("V16", fq.get(2).getVehicle().getId());

        // 慢充2 目前依然是满的 (V12, V13, V17)，所以无法调度，FAULT_QUEUE 应该保持挂起，叫号冻结。
        assertEquals(3, queueRecordRepository.findByPileIdOrderByPositionAsc("慢充2").size());
    }

    @Test
    public void testCancelFromFaultQueue_shouldRemoveVehicleAndTriggerDispatch() {
        LocalDateTime t0 = LocalDateTime.of(2026, 6, 12, 6, 0, 0);
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 40.0, t0);
        schedulingEngine.requestCharge("V2", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, t0.plusMinutes(5));
        schedulingEngine.requestCharge("V3", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(10));
        schedulingEngine.requestCharge("V4", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(15));
        schedulingEngine.requestCharge("V5", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(20));

        // 此时慢充1: V1 在充 + V5 排队；慢充2: V2 在充 + V3/V4 排队（已满）
        assertEquals(2, queueRecordRepository.findByPileIdOrderByPositionAsc("慢充1").size());

        schedulingEngine.reportBreakdown("慢充1", t0.plusMinutes(30));

        List<QueueRecord> fqBefore = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE");
        assertFalse(fqBefore.isEmpty());
        String cancelId = fqBefore.get(fqBefore.size() - 1).getVehicle().getId();

        Vehicle cancelled = schedulingEngine.cancelOrEndCharge(cancelId, t0.plusMinutes(35));
        assertEquals(VehicleState.CANCELLED, cancelled.getState());
        assertEquals(fqBefore.size() - 1, queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE").size());
    }

    @Test
    public void testChangeRequest_inWaitingArea_changeModeOnly_shouldRequeueWithNewNum() {
        LocalDateTime t0 = LocalDateTime.of(2026, 6, 12, 6, 0, 0);
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 40.0, t0);
        schedulingEngine.requestCharge("V2", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, t0.plusMinutes(5));
        schedulingEngine.requestCharge("V4", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(25));
        schedulingEngine.requestCharge("V5", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(30));
        schedulingEngine.requestCharge("V6", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(40));
        schedulingEngine.requestCharge("V7", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 10.0, t0.plusMinutes(50));

        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 8, 35, 0);
        schedulingEngine.requestCharge("V19", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 25.0, time);
        Vehicle before = vehicleRepository.findById("V19").orElseThrow();
        assertEquals(VehicleState.WAITING_IN_AREA, before.getState());
        assertEquals("T7", before.getQueueNum());

        Vehicle updated = schedulingEngine.changeRequest("V19", ChargingMode.FAST, null, time.plusMinutes(5));
        assertEquals(ChargingMode.FAST, updated.getChargeMode());
        assertEquals(25.0, updated.getRequestedAmount(), 1e-9);
        assertEquals("F1", updated.getQueueNum());
        assertNotEquals(VehicleState.CANCELLED, updated.getState());
        assertNotEquals(VehicleState.FINISHED, updated.getState());
    }

    @Test
    public void testChangeRequest_inWaitingArea_changeModeAndAmount() {
        LocalDateTime t0 = LocalDateTime.of(2026, 6, 12, 6, 0, 0);
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 40.0, t0);
        schedulingEngine.requestCharge("V2", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, t0.plusMinutes(5));
        schedulingEngine.requestCharge("V4", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(25));
        schedulingEngine.requestCharge("V5", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(30));
        schedulingEngine.requestCharge("V6", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t0.plusMinutes(40));
        schedulingEngine.requestCharge("V7", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 10.0, t0.plusMinutes(50));

        LocalDateTime time = LocalDateTime.of(2026, 6, 12, 8, 35, 0);
        schedulingEngine.requestCharge("V19", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 25.0, time);

        Vehicle updated = schedulingEngine.changeRequest("V19", ChargingMode.FAST, 25.0, time.plusMinutes(5));
        assertEquals(ChargingMode.FAST, updated.getChargeMode());
        assertEquals(25.0, updated.getRequestedAmount(), 1e-9);
    }

    @Test
    public void testWaitingAreaCapacity_sharedAcrossModes_notPerMode() {
        schedulingEngine.setParameters(2, 3);
        LocalDateTime t = LocalDateTime.of(2026, 6, 12, 6, 0, 0);

        // 先占满全部 5 桩物理队列（2 慢 × 3 + 3 快 × 3 = 15），避免混排时提前进入等候区
        for (int i = 1; i <= 6; i++) {
            schedulingEngine.requestCharge("S" + i, testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 20.0, t.plusSeconds(i));
        }
        for (int i = 1; i <= 9; i++) {
            schedulingEngine.requestCharge("F" + i, testUser.getId(), "Type", 60.0, 10.0, ChargingMode.FAST, 20.0, t.plusSeconds(10 + i));
        }
        assertEquals(0, queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA").size());

        schedulingEngine.requestCharge("W1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.FAST, 10.0, t.plusMinutes(20));
        schedulingEngine.requestCharge("W2", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 10.0, t.plusMinutes(21));

        long waitingCount = queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA").size();
        assertEquals(2, waitingCount);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                schedulingEngine.requestCharge("W3", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.FAST, 10.0, t.plusMinutes(22))
        );
        assertTrue(ex.getMessage().contains("Waiting area is full"));
    }

    @Test
    public void testShutdownAndStartPile_shouldReturnVehiclesToWaitingArea() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 12, 7, 0, 0);
        Vehicle v1 = schedulingEngine.requestCharge("V1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 30.0, t);
        String pileId = v1.getCurrentPile().getId();
        assertEquals(VehicleState.CHARGING, v1.getState());

        schedulingEngine.shutdownPile(pileId, t.plusMinutes(10));

        ChargingPile pile = chargingPileRepository.findById(pileId).orElseThrow();
        assertEquals(PileState.OFFLINE, pile.getState());
        assertEquals(0, queueRecordRepository.findByPileIdOrderByPositionAsc(pileId).size());

        Vehicle v1After = vehicleRepository.findById("V1").orElseThrow();
        // 关闭后车辆回等候区并触发同类型重调度，可能被分配到其它慢充桩
        if (v1After.getCurrentPile() != null) {
            assertNotEquals(pileId, v1After.getCurrentPile().getId());
        }
        assertNotEquals(VehicleState.CANCELLED, v1After.getState());

        schedulingEngine.startPile(pileId, t.plusMinutes(15));
        assertEquals(PileState.RUNNING, chargingPileRepository.findById(pileId).orElseThrow().getState());
    }

    @Test
    public void testExtension1BatchDispatch_assignsToBalanceLoad() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 12, 8, 0, 0);
        // 占满两慢充桩共 6 位，W1/W2 进入等候区；启动已关闭的慢充2 后空位≥2，触发扩展①
        for (int i = 1; i <= 6; i++) {
            schedulingEngine.requestCharge("S" + i, testUser.getId(), "Type", 60.0, 10.0,
                    ChargingMode.TRICKLE, 50.0, t.plusSeconds(i));
        }
        schedulingEngine.requestCharge("W1", testUser.getId(), "Type", 60.0, 10.0,
                ChargingMode.TRICKLE, 10.0, t.plusMinutes(5));
        schedulingEngine.requestCharge("W2", testUser.getId(), "Type", 60.0, 10.0,
                ChargingMode.TRICKLE, 10.0, t.plusMinutes(6));

        assertEquals(2, queueRecordRepository.findByQueueTypeOrderByPositionAsc("WAITING_AREA").size());

        schedulingEngine.shutdownPile("慢充2", t.plusMinutes(7));
        assertEquals(PileState.OFFLINE, chargingPileRepository.findById("慢充2").orElseThrow().getState());

        schedulingEngine.startPile("慢充2", t.plusMinutes(8));

        Vehicle w1 = vehicleRepository.findById("W1").orElseThrow();
        Vehicle w2 = vehicleRepository.findById("W2").orElseThrow();
        assertNotEquals(VehicleState.WAITING_IN_AREA, w1.getState());
        assertNotEquals(VehicleState.WAITING_IN_AREA, w2.getState());
    }

    @Test
    public void testRecoveryMerge_redispatchByQueueNum() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 12, 9, 0, 0);
        schedulingEngine.requestCharge("V1", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 80.0, t);
        schedulingEngine.requestCharge("V2", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 80.0, t.plusMinutes(5));
        schedulingEngine.requestCharge("V3", testUser.getId(), "Type", 60.0, 10.0, ChargingMode.TRICKLE, 80.0, t.plusMinutes(10));

        Vehicle v3 = vehicleRepository.findById("V3").orElseThrow();
        String pileWithV3 = v3.getCurrentPile().getId();

        schedulingEngine.reportBreakdown(pileWithV3, t.plusMinutes(20));

        ChargingPile broken = chargingPileRepository.findById(pileWithV3).orElseThrow();
        broken.setState(PileState.RUNNING);
        chargingPileRepository.save(broken);

        schedulingEngine.redispatchAfterRecovery(ChargingMode.TRICKLE, t.plusMinutes(30));

        assertEquals(PileState.RUNNING, chargingPileRepository.findById(pileWithV3).orElseThrow().getState());
        long faultRemaining = queueRecordRepository.findByQueueTypeOrderByPositionAsc("FAULT_QUEUE").stream()
                .filter(qr -> qr.getVehicle().getChargeMode() == ChargingMode.TRICKLE)
                .count();
        assertTrue(faultRemaining <= 3);
    }
}
