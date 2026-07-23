package com.chargingpile.service;

import com.chargingpile.entity.*;
import com.chargingpile.repository.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TestSimulator {

    private final SchedulingEngine schedulingEngine;
    private final UserRepository userRepository;
    private final ChargingPileRepository chargingPileRepository;
    private final VehicleRepository vehicleRepository;
    private final QueueRecordRepository queueRecordRepository;
    private final DetailListRepository detailListRepository;
    private final BillRepository billRepository;
    private final RechargeRecordRepository rechargeRecordRepository;

    public TestSimulator(SchedulingEngine schedulingEngine,
                         UserRepository userRepository,
                         ChargingPileRepository chargingPileRepository,
                         VehicleRepository vehicleRepository,
                         QueueRecordRepository queueRecordRepository,
                         DetailListRepository detailListRepository,
                         BillRepository billRepository,
                         RechargeRecordRepository rechargeRecordRepository) {
        this.schedulingEngine = schedulingEngine;
        this.userRepository = userRepository;
        this.chargingPileRepository = chargingPileRepository;
        this.vehicleRepository = vehicleRepository;
        this.queueRecordRepository = queueRecordRepository;
        this.detailListRepository = detailListRepository;
        this.billRepository = billRepository;
        this.rechargeRecordRepository = rechargeRecordRepository;
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static class SimEvent {
        private final String timeStr;
        private final LocalDateTime time;
        private final String type;       // A (Add), C (Change), B (Breakdown)
        private final String targetId;   // Vehicle ID or Pile ID
        private final String chargeType; // T (Trickle), F (Fast), O (Other/Breakdown)
        private final double value;      // Charging amount, change amount, or breakdown duration

        public SimEvent(String timeStr, LocalDateTime dateBasis, String type, String targetId, String chargeType, double value) {
            this.timeStr = timeStr;
            this.type = type;
            this.targetId = targetId;
            this.chargeType = chargeType;
            this.value = value;
            
            String[] parts = timeStr.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int s = Integer.parseInt(parts[2]);
            this.time = dateBasis.withHour(h).withMinute(m).withSecond(s).withNano(0);
        }

        public String getTimeStr() { return timeStr; }
        public LocalDateTime getTime() { return time; }
        public String getType() { return type; }
        public String getTargetId() { return targetId; }
        public String getChargeType() { return chargeType; }
        public double getValue() { return value; }

        @Override
        public String toString() {
            return String.format("(%s, %s, %s, %s, %.1f)", timeStr, type, targetId, chargeType, value);
        }
    }

    public static class SimVehicleProgress {
        private final String vehicleId;
        private double chargedAmount = 0.0;
        private final LocalDateTime requestTime;

        public SimVehicleProgress(String vehicleId, LocalDateTime requestTime) {
            this.vehicleId = vehicleId;
            this.requestTime = requestTime;
        }

        public String getVehicleId() { return vehicleId; }
        public double getChargedAmount() { return chargedAmount; }
        public void addChargedAmount(double amt) { this.chargedAmount += amt; }
        public LocalDateTime getRequestTime() { return requestTime; }
    }

    /**
     * 执行完整的仿真运行
     * @param filePath Excel 文件路径，若为 null/空/未找到则自动使用内置的标准 32 个验收用例事件进行模拟
     */
    @Transactional
    public void runSimulation(String filePath, int waitingAreaSize, int queueLen) {
        System.out.println("\n========================================================");
        System.out.println("   STARTING DISCRETE EVENT SIMULATION RUNNER (TESTSIMULATOR) ");
        System.out.println("========================================================\n");

        LocalDateTime dateBasis = LocalDateTime.of(2026, 6, 12, 0, 0, 0);

        // 1. 初始化数据库及默认测试车主
        cleanDatabase();
        userRepository.insertUserWithId(1L, "system_driver", "driver123", "CUSTOMER", dateBasis);

        // 2. 初始化充电桩
        initializePiles();
        schedulingEngine.setParameters(waitingAreaSize, queueLen);

        // 3. 读取事件 (自适应 Excel 或内置用例)
        List<SimEvent> eventList = loadEvents(filePath, dateBasis);
        System.out.println("Loaded " + eventList.size() + " simulation events successfully.");

        // 按时间升序排序
        eventList.sort(Comparator.comparing(SimEvent::getTime));

        // 4. 模拟时间线主循环
        LocalDateTime startTime = dateBasis.withHour(6).withMinute(0).withSecond(0);
        LocalDateTime endTime = dateBasis.withHour(11).withMinute(0).withSecond(0);

        // 内存中跟踪各个车辆的实际物理充电进度
        Map<String, SimVehicleProgress> vehicleProgressMap = new HashMap<>();
        
        // 跟踪充电桩的故障恢复时间 (时间 -> 充电桩编号)
        Map<LocalDateTime, List<String>> pileRecoveries = new HashMap<>();

        LocalDateTime currentSimTime = startTime;
        int eventIdx = 0;

        while (currentSimTime.isBefore(endTime) || currentSimTime.isEqual(endTime)) {
            // A. 处理充电桩故障恢复
            if (pileRecoveries.containsKey(currentSimTime)) {
                List<String> recoveredPiles = pileRecoveries.get(currentSimTime);
                for (String pId : recoveredPiles) {
                    ChargingPile pile = chargingPileRepository.findById(pId).orElse(null);
                    if (pile != null && pile.getState() == PileState.FAULT) {
                        pile.setState(PileState.RUNNING);
                        chargingPileRepository.save(pile);
                        System.out.println("\n>>> [" + currentSimTime.format(TIME_FORMATTER) + "] Pile Recovery: Pile " + pId + " recovered back to RUNNING!");
                        
                        // 触发恢复后调度
                        schedulingEngine.redispatchAfterRecovery(pile.getType(), currentSimTime);
                    }
                }
            }

            // B. 物理流逝 1 秒：对正在充电的车辆累加充电量，检测是否充完
            List<ChargingPile> runningPiles = chargingPileRepository.findAll();
            for (ChargingPile pile : runningPiles) {
                if (pile.getState() == PileState.RUNNING) {
                    // 查询该桩内排在第 0 位的车辆 (代表正在物理充电中)
                    List<QueueRecord> qrList = queueRecordRepository.findByPileIdOrderByPositionAsc(pile.getId());
                    if (!qrList.isEmpty()) {
                        QueueRecord activeQr = qrList.get(0);
                        Vehicle activeVehicle = activeQr.getVehicle();
                        
                        // 确保车辆状态是 CHARGING
                        if (activeVehicle.getState() != VehicleState.CHARGING) {
                            activeVehicle.setState(VehicleState.CHARGING);
                            vehicleRepository.save(activeVehicle);
                        }

                        SimVehicleProgress progress = vehicleProgressMap.get(activeVehicle.getId());
                        if (progress == null) {
                            progress = new SimVehicleProgress(activeVehicle.getId(), activeVehicle.getRequestTime());
                            vehicleProgressMap.put(activeVehicle.getId(), progress);
                        }

                        // 1秒充入度数 = 功率 / 3600
                        double deltaQ = pile.getPower() / 3600.0;
                        double remainingToCharge = activeVehicle.getRequestedAmount() - progress.getChargedAmount();
                        
                        if (deltaQ > remainingToCharge) {
                            deltaQ = remainingToCharge;
                        }

                        if (deltaQ > 0) {
                            progress.addChargedAmount(deltaQ);
                        }

                        // 如果充完电了，立刻触发结束逻辑
                        if (progress.getChargedAmount() >= activeVehicle.getRequestedAmount() - 1e-9) {
                            System.out.println("\n>>> [" + currentSimTime.format(TIME_FORMATTER) + "] Finished Charging: Vehicle " 
                                    + activeVehicle.getId() + " completed charging on pile " + pile.getId() 
                                    + ". Charged: " + formatFloat(progress.getChargedAmount()) + " deg.");
                            
                            schedulingEngine.cancelOrEndCharge(activeVehicle.getId(), currentSimTime);
                        }
                    }
                }
            }

            // C. 触发该秒对应的事件
            while (eventIdx < eventList.size() && !eventList.get(eventIdx).getTime().isAfter(currentSimTime)) {
                SimEvent evt = eventList.get(eventIdx);
                processEvent(evt, 1L, currentSimTime, vehicleProgressMap, pileRecoveries);
                eventIdx++;
            }

            // 时间推移 1 秒
            currentSimTime = currentSimTime.plusSeconds(1);
        }

        // 5. 仿真结束，输出详细的汇总日志与账单
        printSimulationSummary(vehicleProgressMap);
    }

    private void processEvent(SimEvent evt, Long userId, LocalDateTime currentSimTime,
                              Map<String, SimVehicleProgress> vehicleProgressMap,
                              Map<LocalDateTime, List<String>> pileRecoveries) {
        
        System.out.println("\n>>> [" + currentSimTime.format(TIME_FORMATTER) + "] Event Occurred: Type=" + evt.getType() 
                + ", Target=" + evt.getTargetId() + ", ChargeType=" + evt.getChargeType() + ", Value=" + evt.getValue());

        String type = evt.getType();
        String targetId = evt.getTargetId();
        String chargeType = evt.getChargeType();
        double value = evt.getValue();

        // 识别取消操作 (A或C事件，数值为0，或充电类型为O代表取消)
        boolean isCancel = (value == 0.0) || "O".equals(chargeType) && !"B".equals(type);

        if (isCancel) {
            System.out.println("[" + currentSimTime.format(TIME_FORMATTER) + "] Cancellation Triggered for vehicle: " + targetId);
            schedulingEngine.cancelOrEndCharge(targetId, currentSimTime);
        } else if ("A".equals(type)) {
            // 正常发起充电申请
            ChargingMode mode = "F".equals(chargeType) ? ChargingMode.FAST : ChargingMode.TRICKLE;
            double capacity = "F".equals(chargeType) ? 60.0 : 60.0; // 模拟电池参数
            double currentCap = 10.0;

            vehicleProgressMap.put(targetId, new SimVehicleProgress(targetId, currentSimTime));
            
            schedulingEngine.requestCharge(targetId, userId, "EV-Model", capacity, currentCap, mode, value, currentSimTime);

        } else if ("C".equals(type)) {
            // 变更充电请求
            if (vehicleProgressMap.containsKey(targetId)) {
                // 如果是变更为新充电模式
                if ("F".equals(chargeType) || "T".equals(chargeType)) {
                    ChargingMode newMode = "F".equals(chargeType) ? ChargingMode.FAST : ChargingMode.TRICKLE;
                    schedulingEngine.changeRequest(targetId, newMode, value, currentSimTime);
                } else {
                    // 仅修改充电电量
                    schedulingEngine.changeRequest(targetId, null, value, currentSimTime);
                }
            } else {
                System.out.println("Warning: Received change request for non-existent vehicle: " + targetId);
            }
        } else if ("B".equals(type)) {
            // 充电桩故障发生
            String mappedPileId;
            if (targetId.startsWith("T")) {
                mappedPileId = "慢充" + targetId.substring(1);
            } else if (targetId.startsWith("F")) {
                mappedPileId = "快充" + targetId.substring(1);
            } else {
                mappedPileId = targetId;
            }

            schedulingEngine.reportBreakdown(mappedPileId, currentSimTime);

            // 注册故障恢复时间
            long durationMinutes = (long) value;
            LocalDateTime recoverTime = currentSimTime.plusMinutes(durationMinutes);
            pileRecoveries.computeIfAbsent(recoverTime, k -> new ArrayList<>()).add(mappedPileId);
            System.out.println("[" + currentSimTime.format(TIME_FORMATTER) + "] Pile " + mappedPileId 
                    + " will recover at " + recoverTime.format(TIME_FORMATTER) + " (" + durationMinutes + " mins breakdown)");
        }
    }

    private List<SimEvent> loadEvents(String filePath, LocalDateTime dateBasis) {
        List<SimEvent> events = new ArrayList<>();
        File file = filePath != null ? new File(filePath) : null;

        // 如果未指定文件路径，或指定了但文件不存在，则自适应使用官方 32 条标准验收数据集
        if (file == null || !file.exists()) {
            System.out.println("No excel file found at: " + filePath + ". Loading built-in default 32 events.");
            return getFallbackEvents(dateBasis);
        }

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            Sheet sheet = workbook.getSheet("事件定义");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0); // 默认读第一个sheet
            }

            System.out.println("Reading events from sheet: " + sheet.getSheetName());
            
            // 假设事件列表在特定的行列结构中，一般从第二或三行开始
            // 我们可以智能扫描，寻找形如 "06:00:00"、"A, V1, T, 40" 样式的行列
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Cell timeCell = row.getCell(0);
                Cell eventCell = row.getCell(1); // 可能是 "A, V1, T, 40" 样式的整行，也可能是分列

                if (timeCell == null) continue;
                String timeStr = getCellStringValue(timeCell);
                if (timeStr == null || !timeStr.matches("\\d{1,2}:\\d{2}:\\d{2}")) continue;

                if (eventCell != null) {
                    String eventText = getCellStringValue(eventCell).trim();
                    // 处理 A, V1, T, 40 逗号隔开的格式
                    String[] parts = eventText.split("[,，]");
                    if (parts.length >= 4) {
                        String type = parts[0].trim();
                        String id = parts[1].trim();
                        String chargeType = parts[2].trim();
                        double val = Double.parseDouble(parts[3].trim());
                        events.add(new SimEvent(timeStr, dateBasis, type, id, chargeType, val));
                    }
                }
            }

            // 如果解析到的事件量太少，说明 Excel 版式不是逗号隔开的，可能是分列保存的
            if (events.isEmpty()) {
                System.out.println("Excel rows were not comma-separated event string. Trying multi-column mode...");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    Cell timeCell = row.getCell(0);
                    Cell typeCell = row.getCell(1);
                    Cell idCell = row.getCell(2);
                    Cell modeCell = row.getCell(3);
                    Cell valCell = row.getCell(4);

                    if (timeCell == null || typeCell == null || idCell == null || modeCell == null || valCell == null) continue;

                    String timeStr = getCellStringValue(timeCell);
                    String type = getCellStringValue(typeCell);
                    String id = getCellStringValue(idCell);
                    String mode = getCellStringValue(modeCell);
                    double val = getCellDoubleValue(valCell);

                    if (timeStr != null && timeStr.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
                        events.add(new SimEvent(timeStr, dateBasis, type, id, mode, val));
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to parse events from Excel: " + e.getMessage() + ". Fallback to default built-in events.");
            return getFallbackEvents(dateBasis);
        }

        if (events.isEmpty()) {
            System.out.println("Excel sheet contains no matching rows. Fallback to default built-in events.");
            return getFallbackEvents(dateBasis);
        }

        return events;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        } else if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                // 如果是日期格式
                return cell.getLocalDateTimeCellValue().toLocalTime().toString();
            }
            return String.valueOf((int) cell.getNumericCellValue());
        }
        return cell.toString();
    }

    private double getCellDoubleValue(Cell cell) {
        if (cell == null) return 0.0;
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        } else if (cell.getCellType() == CellType.STRING) {
            try {
                return Double.parseDouble(cell.getStringCellValue().trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private List<SimEvent> getFallbackEvents(LocalDateTime dateBasis) {
        List<SimEvent> events = new ArrayList<>();
        events.add(new SimEvent("06:00:00", dateBasis, "A", "V1", "T", 40.0));
        events.add(new SimEvent("06:05:00", dateBasis, "A", "V2", "T", 30.0));
        events.add(new SimEvent("06:10:00", dateBasis, "A", "V3", "F", 60.0));
        events.add(new SimEvent("06:20:00", dateBasis, "A", "V2", "O", 0.0)); // V2 取消
        events.add(new SimEvent("06:25:00", dateBasis, "A", "V4", "T", 20.0));
        events.add(new SimEvent("06:30:00", dateBasis, "A", "V5", "T", 20.0));
        events.add(new SimEvent("06:40:00", dateBasis, "A", "V6", "T", 20.0));
        events.add(new SimEvent("06:50:00", dateBasis, "A", "V7", "T", 10.0));
        events.add(new SimEvent("07:00:00", dateBasis, "A", "V8", "F", 90.0));
        events.add(new SimEvent("07:10:00", dateBasis, "A", "V9", "F", 30.0));
        events.add(new SimEvent("07:15:00", dateBasis, "A", "V10", "T", 10.0));
        events.add(new SimEvent("07:20:00", dateBasis, "A", "V11", "F", 60.0));
        events.add(new SimEvent("07:25:00", dateBasis, "A", "V12", "T", 10.0));
        events.add(new SimEvent("07:30:00", dateBasis, "A", "V13", "T", 7.5));
        events.add(new SimEvent("07:35:00", dateBasis, "A", "V14", "F", 75.0));
        events.add(new SimEvent("07:40:00", dateBasis, "A", "V15", "F", 45.0));
        events.add(new SimEvent("08:00:00", dateBasis, "A", "V16", "T", 5.0));
        events.add(new SimEvent("08:20:00", dateBasis, "A", "V17", "T", 15.0));
        events.add(new SimEvent("08:30:00", dateBasis, "A", "V18", "T", 20.0));
        events.add(new SimEvent("08:35:00", dateBasis, "A", "V19", "T", 25.0));
        events.add(new SimEvent("09:00:00", dateBasis, "A", "V20", "F", 30.0));
        events.add(new SimEvent("09:10:00", dateBasis, "A", "V7", "O", 0.0)); // V7 取消
        events.add(new SimEvent("09:20:00", dateBasis, "A", "V11", "O", 0.0)); // V11 取消
        events.add(new SimEvent("09:30:00", dateBasis, "A", "V18", "O", 0.0)); // V18 取消
        events.add(new SimEvent("09:35:00", dateBasis, "A", "V20", "O", 0.0)); // V20 取消
        events.add(new SimEvent("09:50:00", dateBasis, "A", "V21", "F", 30.0));
        events.add(new SimEvent("10:00:00", dateBasis, "A", "V22", "T", 10.0));
        events.add(new SimEvent("10:05:00", dateBasis, "C", "V19", "F", 25.0)); // V19 变更为快充 25
        events.add(new SimEvent("10:10:00", dateBasis, "C", "V21", "F", 10.0)); // V21 变更为充电 10
        events.add(new SimEvent("10:20:00", dateBasis, "C", "V22", "F", 10.0)); // V22 变更为快充 10
        events.add(new SimEvent("10:30:00", dateBasis, "B", "T1", "O", 60.0)); // T1 故障 60 分钟
        events.add(new SimEvent("10:50:00", dateBasis, "B", "F1", "O", 120.0)); // F1 故障 120 分钟
        return events;
    }

    private void printSimulationSummary(Map<String, SimVehicleProgress> progressMap) {
        System.out.println("\n\n========================================================");
        System.out.println("   SIMULATION RUN COMPLETE - VEHICLES LIFECYCLE SUMMARY ");
        System.out.println("========================================================");

        List<Vehicle> sortedVehicles = vehicleRepository.findAll();
        sortedVehicles.sort(Comparator.comparingInt(v -> Integer.parseInt(v.getId().substring(1))));

        for (Vehicle v : sortedVehicles) {
            SimVehicleProgress p = progressMap.get(v.getId());
            double memoryCharged = p != null ? p.getChargedAmount() : 0.0;
            
            // 汇总计算此车的财务详单
            List<DetailList> dlList = detailListRepository.findByVehicleId(v.getId());
            double elecCost = 0.0;
            double serviceCost = 0.0;
            double totalCost = 0.0;
            double dbCharged = 0.0;

            for (DetailList dl : dlList) {
                elecCost += dl.getChargeCost();
                serviceCost += dl.getServiceCost();
                totalCost += dl.getTotalCost();
                dbCharged += dl.getChargedAmount();
            }

            System.out.println(String.format("Vehicle %s: State=%s, Req=%s, Charged=%s deg (DB=%s), Cost=%s元 (Elec=%s, Serv=%s)",
                    v.getId(), v.getState(), formatFloat(v.getRequestedAmount()), 
                    formatFloat(memoryCharged), formatFloat(dbCharged),
                    formatFloat(totalCost), formatFloat(elecCost), formatFloat(serviceCost)));
            
            if (v.getCurrentPile() != null) {
                System.out.println("   -> Currently Queuing/Charging on Pile: " + v.getCurrentPile().getId() + " (QueueNum=" + v.getQueueNum() + ")");
            }

            // 输出此车涉及到的详单分项记录
            if (!dlList.isEmpty()) {
                System.out.println("      详单明细记录:");
                for (DetailList dl : dlList) {
                    System.out.println(String.format("       * %s | Pile: %s | Time: [%s ~ %s] | Amt: %s deg | Cost: %s元 (Elec:%s, Serv:%s)",
                            dl.getDetailNo(), dl.getPile().getId(),
                            dl.getStartTime().format(TIME_FORMATTER), dl.getEndTime().format(TIME_FORMATTER),
                            formatFloat(dl.getChargedAmount()), formatFloat(dl.getTotalCost()),
                            formatFloat(dl.getChargeCost()), formatFloat(dl.getServiceCost())));
                }
            }
        }

        System.out.println("\n========================================================");
        System.out.println("   SIMULATION RUN COMPLETE - CHARGING PILES FINAL STATUS ");
        System.out.println("========================================================");

        List<ChargingPile> piles = chargingPileRepository.findAll();
        for (ChargingPile pile : piles) {
            double minutes = pile.getTotalChargeTime() / 60.0;
            System.out.println(String.format("Pile %s: Type=%s, State=%s, ChargeCount=%d, Duration=%s mins, Elec=%s deg, Cost=%s元 (Elec=%s, Serv=%s)",
                    pile.getId(), pile.getType(), pile.getState(), pile.getChargeCount(),
                    formatFloat(minutes), formatFloat(pile.getTotalChargeAmount()),
                    formatFloat(pile.getTotalCost()), formatFloat(pile.getTotalChargeCost()),
                    formatFloat(pile.getTotalServiceCost())));
        }
        System.out.println("========================================================\n");
    }

    private void cleanDatabase() {
        detailListRepository.deleteAll();
        billRepository.deleteAll();
        rechargeRecordRepository.deleteAll();
        queueRecordRepository.deleteAll();
        vehicleRepository.deleteAll();
        chargingPileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void initializePiles() {
        chargingPileRepository.save(ChargingPile.builder().id("快充1").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("快充2").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("快充3").type(ChargingMode.FAST).power(30.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("慢充1").type(ChargingMode.TRICKLE).power(10.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
        chargingPileRepository.save(ChargingPile.builder().id("慢充2").type(ChargingMode.TRICKLE).power(10.0).state(PileState.RUNNING).chargeCount(0).totalChargeTime(0L).totalChargeAmount(0.0).totalChargeCost(0.0).totalServiceCost(0.0).totalCost(0.0).build());
    }

    private String formatFloat(double val) {
        String s = String.format(Locale.US, "%.2f", val);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }
}
