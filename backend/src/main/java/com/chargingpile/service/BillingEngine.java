package com.chargingpile.service;

import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class BillingEngine {

    public static final double PEAK_PRICE = 1.0;
    public static final double REGULAR_PRICE = 0.7;
    public static final double OFF_PEAK_PRICE = 0.4;
    public static final double SERVICE_FEE_PRICE = 0.8;

    public static class BillingResult {
        private long totalDurationSeconds; // 充电时长 (秒)
        private double chargedAmount;       // 充电量 (度)
        private double peakAmount;          // 峰时电量
        private double regularAmount;       // 平时电量
        private double offPeakAmount;       // 谷时电量
        private double peakChargeCost;      // 峰时电费 (元)
        private double regularChargeCost;   // 平时电费 (元)
        private double offPeakChargeCost;   // 谷时电费 (元)
        private double totalChargeCost;     // 总电费 (元)
        private double totalServiceCost;    // 总服务费 (元)
        private double totalCost;           // 总费用 (元)

        public BillingResult() {}

        public BillingResult(long totalDurationSeconds, double chargedAmount, double peakAmount, double regularAmount,
                             double offPeakAmount, double peakChargeCost, double regularChargeCost, double offPeakChargeCost,
                             double totalChargeCost, double totalServiceCost, double totalCost) {
            this.totalDurationSeconds = totalDurationSeconds;
            this.chargedAmount = chargedAmount;
            this.peakAmount = peakAmount;
            this.regularAmount = regularAmount;
            this.offPeakAmount = offPeakAmount;
            this.peakChargeCost = peakChargeCost;
            this.regularChargeCost = regularChargeCost;
            this.offPeakChargeCost = offPeakChargeCost;
            this.totalChargeCost = totalChargeCost;
            this.totalServiceCost = totalServiceCost;
            this.totalCost = totalCost;
        }

        // Getters and Setters
        public long getTotalDurationSeconds() { return totalDurationSeconds; }
        public void setTotalDurationSeconds(long totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }

        public double getChargedAmount() { return chargedAmount; }
        public void setChargedAmount(double chargedAmount) { this.chargedAmount = chargedAmount; }

        public double getPeakAmount() { return peakAmount; }
        public void setPeakAmount(double peakAmount) { this.peakAmount = peakAmount; }

        public double getRegularAmount() { return regularAmount; }
        public void setRegularAmount(double regularAmount) { this.regularAmount = regularAmount; }

        public double getOffPeakAmount() { return offPeakAmount; }
        public void setOffPeakAmount(double offPeakAmount) { this.offPeakAmount = offPeakAmount; }

        public double getPeakChargeCost() { return peakChargeCost; }
        public void setPeakChargeCost(double peakChargeCost) { this.peakChargeCost = peakChargeCost; }

        public double getRegularChargeCost() { return regularChargeCost; }
        public void setRegularChargeCost(double regularChargeCost) { this.regularChargeCost = regularChargeCost; }

        public double getOffPeakChargeCost() { return offPeakChargeCost; }
        public void setOffPeakChargeCost(double offPeakChargeCost) { this.offPeakChargeCost = offPeakChargeCost; }

        public double getTotalChargeCost() { return totalChargeCost; }
        public void setTotalChargeCost(double totalChargeCost) { this.totalChargeCost = totalChargeCost; }

        public double getTotalServiceCost() { return totalServiceCost; }
        public void setTotalServiceCost(double totalServiceCost) { this.totalServiceCost = totalServiceCost; }

        public double getTotalCost() { return totalCost; }
        public void setTotalCost(double totalCost) { this.totalCost = totalCost; }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private long totalDurationSeconds;
            private double chargedAmount;
            private double peakAmount;
            private double regularAmount;
            private double offPeakAmount;
            private double peakChargeCost;
            private double regularChargeCost;
            private double offPeakChargeCost;
            private double totalChargeCost;
            private double totalServiceCost;
            private double totalCost;

            public Builder totalDurationSeconds(long val) { this.totalDurationSeconds = val; return this; }
            public Builder chargedAmount(double val) { this.chargedAmount = val; return this; }
            public Builder peakAmount(double val) { this.peakAmount = val; return this; }
            public Builder regularAmount(double val) { this.regularAmount = val; return this; }
            public Builder offPeakAmount(double val) { this.offPeakAmount = val; return this; }
            public Builder peakChargeCost(double val) { this.peakChargeCost = val; return this; }
            public Builder regularChargeCost(double val) { this.regularChargeCost = val; return this; }
            public Builder offPeakChargeCost(double val) { this.offPeakChargeCost = val; return this; }
            public Builder totalChargeCost(double val) { this.totalChargeCost = val; return this; }
            public Builder totalServiceCost(double val) { this.totalServiceCost = val; return this; }
            public Builder totalCost(double val) { this.totalCost = val; return this; }

            public BillingResult build() {
                return new BillingResult(totalDurationSeconds, chargedAmount, peakAmount, regularAmount, offPeakAmount,
                        peakChargeCost, regularChargeCost, offPeakChargeCost, totalChargeCost, totalServiceCost, totalCost);
            }
        }
    }

    private static class TimeWindow {
        private final int startSecond; // 距离当天 00:00:00 的秒数
        private final int endSecond;   // 距离当天 00:00:00 的秒数
        private final double rate;     // 电价 (元/度)

        public TimeWindow(int startSecond, int endSecond, double rate) {
            this.startSecond = startSecond;
            this.endSecond = endSecond;
            this.rate = rate;
        }

        public int getStartSecond() { return startSecond; }
        public int getEndSecond() { return endSecond; }
        public double getRate() { return rate; }
    }

    private static final List<TimeWindow> TARIFF_WINDOWS = new ArrayList<>();

    static {
        // Peak hours (10:00 ~ 15:00, 18:00 ~ 21:00)
        TARIFF_WINDOWS.add(new TimeWindow(10 * 3600, 15 * 3600, PEAK_PRICE));
        TARIFF_WINDOWS.add(new TimeWindow(18 * 3600, 21 * 3600, PEAK_PRICE));

        // Regular hours (07:00 ~ 10:00, 15:00 ~ 18:00, 21:00 ~ 23:00)
        TARIFF_WINDOWS.add(new TimeWindow(7 * 3600, 10 * 3600, REGULAR_PRICE));
        TARIFF_WINDOWS.add(new TimeWindow(15 * 3600, 18 * 3600, REGULAR_PRICE));
        TARIFF_WINDOWS.add(new TimeWindow(21 * 3600, 23 * 3600, REGULAR_PRICE));

        // Off-peak hours (23:00 ~ 24:00, 00:00 ~ 07:00)
        TARIFF_WINDOWS.add(new TimeWindow(0, 7 * 3600, OFF_PEAK_PRICE));
        TARIFF_WINDOWS.add(new TimeWindow(23 * 3600, 24 * 3600, OFF_PEAK_PRICE));
    }

    /**
     * 根据充电的启动时间、停止时间和实际充电电量，计算出各项费用的高精度详单结果。
     *
     * @param startTime     充电开始时间
     * @param endTime       充电结束时间
     * @param chargedAmount 实际充电度数 (度)
     * @return 计费结果 BillingResult
     */
    public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime, double chargedAmount) {
        if (startTime == null || endTime == null || endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("Invalid start or end time.");
        }

        long totalDurationSeconds = Duration.between(startTime, endTime).getSeconds();
        if (totalDurationSeconds == 0 || chargedAmount <= 0) {
            return BillingResult.builder()
                    .totalDurationSeconds(totalDurationSeconds)
                    .chargedAmount(0.0)
                    .peakAmount(0.0)
                    .regularAmount(0.0)
                    .offPeakAmount(0.0)
                    .peakChargeCost(0.0)
                    .regularChargeCost(0.0)
                    .offPeakChargeCost(0.0)
                    .totalChargeCost(0.0)
                    .totalServiceCost(0.0)
                    .totalCost(0.0)
                    .build();
        }

        // 统计各个费率时段对应的时长 (秒)
        double peakSeconds = 0;
        double regularSeconds = 0;
        double offPeakSeconds = 0;

        LocalDate startDate = startTime.toLocalDate();
        LocalDate endDate = endTime.toLocalDate();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            // 计算当前日期在 [startTime, endTime] 之间的重叠段
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

            LocalDateTime overlapStart = startTime.isAfter(dayStart) ? startTime : dayStart;
            LocalDateTime overlapEnd = endTime.isBefore(dayEnd) ? endTime : dayEnd;

            if (overlapStart.isBefore(overlapEnd)) {
                // 转换为距离当天 00:00:00 的秒数
                int secStart = (int) Duration.between(dayStart, overlapStart).getSeconds();
                int secEnd = (int) Duration.between(dayStart, overlapEnd).getSeconds();

                // 拆分出当前日期重叠段中，各个电价时段的占比
                for (TimeWindow window : TARIFF_WINDOWS) {
                    int overlapWindowStart = Math.max(secStart, window.getStartSecond());
                    int overlapWindowEnd = Math.min(secEnd, window.getEndSecond());

                    if (overlapWindowStart < overlapWindowEnd) {
                        long overlapWindowSeconds = overlapWindowEnd - overlapWindowStart;
                        if (Math.abs(window.getRate() - PEAK_PRICE) < 1e-9) {
                            peakSeconds += overlapWindowSeconds;
                        } else if (Math.abs(window.getRate() - REGULAR_PRICE) < 1e-9) {
                            regularSeconds += overlapWindowSeconds;
                        } else if (Math.abs(window.getRate() - OFF_PEAK_PRICE) < 1e-9) {
                            offPeakSeconds += overlapWindowSeconds;
                        }
                    }
                }
            }
        }

        // 根据匀速充电假设，各时段所占用的电量等于 总电量 * (该时段时长 / 总时长)
        double peakAmount = chargedAmount * (peakSeconds / totalDurationSeconds);
        double regularAmount = chargedAmount * (regularSeconds / totalDurationSeconds);
        double offPeakAmount = chargedAmount * (offPeakSeconds / totalDurationSeconds);

        // 计算对应的电费
        double peakChargeCost = peakAmount * PEAK_PRICE;
        double regularChargeCost = regularAmount * REGULAR_PRICE;
        double offPeakChargeCost = offPeakAmount * OFF_PEAK_PRICE;

        double totalChargeCost = peakChargeCost + regularChargeCost + offPeakChargeCost;
        double totalServiceCost = chargedAmount * SERVICE_FEE_PRICE;
        double totalCost = totalChargeCost + totalServiceCost;

        // 浮点数保留合理精度精度
        return BillingResult.builder()
                .totalDurationSeconds(totalDurationSeconds)
                .chargedAmount(round(chargedAmount))
                .peakAmount(round(peakAmount))
                .regularAmount(round(regularAmount))
                .offPeakAmount(round(offPeakAmount))
                .peakChargeCost(round(peakChargeCost))
                .regularChargeCost(round(regularChargeCost))
                .offPeakChargeCost(round(offPeakChargeCost))
                .totalChargeCost(round(totalChargeCost))
                .totalServiceCost(round(totalServiceCost))
                .totalCost(round(totalCost))
                .build();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0; // 保留 4 位小数
    }
}
