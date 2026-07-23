package com.chargingpile.dto;

public class DailyPileReportRow {
    private String date;
    private String pileId;
    private long chargeCount;
    private long totalChargeDurationSec;
    private double totalChargeAmount;
    private double totalChargeCost;
    private double totalServiceCost;
    private double totalCost;

    public DailyPileReportRow() {}

    public DailyPileReportRow(String date, String pileId, long chargeCount, long totalChargeDurationSec,
                              double totalChargeAmount, double totalChargeCost, double totalServiceCost, double totalCost) {
        this.date = date;
        this.pileId = pileId;
        this.chargeCount = chargeCount;
        this.totalChargeDurationSec = totalChargeDurationSec;
        this.totalChargeAmount = totalChargeAmount;
        this.totalChargeCost = totalChargeCost;
        this.totalServiceCost = totalServiceCost;
        this.totalCost = totalCost;
    }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getPileId() { return pileId; }
    public void setPileId(String pileId) { this.pileId = pileId; }

    public long getChargeCount() { return chargeCount; }
    public void setChargeCount(long chargeCount) { this.chargeCount = chargeCount; }

    public long getTotalChargeDurationSec() { return totalChargeDurationSec; }
    public void setTotalChargeDurationSec(long totalChargeDurationSec) { this.totalChargeDurationSec = totalChargeDurationSec; }

    public double getTotalChargeAmount() { return totalChargeAmount; }
    public void setTotalChargeAmount(double totalChargeAmount) { this.totalChargeAmount = totalChargeAmount; }

    public double getTotalChargeCost() { return totalChargeCost; }
    public void setTotalChargeCost(double totalChargeCost) { this.totalChargeCost = totalChargeCost; }

    public double getTotalServiceCost() { return totalServiceCost; }
    public void setTotalServiceCost(double totalServiceCost) { this.totalServiceCost = totalServiceCost; }

    public double getTotalCost() { return totalCost; }
    public void setTotalCost(double totalCost) { this.totalCost = totalCost; }
}
