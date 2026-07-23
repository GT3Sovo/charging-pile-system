package com.chargingpile.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

@Entity
@Table(name = "charging_piles")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChargingPile {
    @Id
    @Column(name = "id", nullable = false, length = 20)
    private String id; // 充电桩编号

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private ChargingMode type; // 充电桩类型

    @Column(name = "power", nullable = false)
    private Double power; // 充电功率

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private PileState state; // 当前状态

    @Column(name = "charge_count", nullable = false)
    private Integer chargeCount; // 系统启动后累计充电次数

    @Column(name = "total_charge_time", nullable = false)
    private Long totalChargeTime; // 充电总时长 (秒)

    @Column(name = "total_charge_amount", nullable = false)
    private Double totalChargeAmount; // 充电总电量 (度)

    @Column(name = "total_charge_cost", nullable = false)
    private Double totalChargeCost; // 累计充电费用

    @Column(name = "total_service_cost", nullable = false)
    private Double totalServiceCost; // 累计服务费用

    @Column(name = "total_cost", nullable = false)
    private Double totalCost; // 累计总费用

    public ChargingPile() {}

    public ChargingPile(String id, ChargingMode type, Double power, PileState state, Integer chargeCount,
                        Long totalChargeTime, Double totalChargeAmount, Double totalChargeCost,
                        Double totalServiceCost, Double totalCost) {
        this.id = id;
        this.type = type;
        this.power = power;
        this.state = state;
        this.chargeCount = chargeCount;
        this.totalChargeTime = totalChargeTime;
        this.totalChargeAmount = totalChargeAmount;
        this.totalChargeCost = totalChargeCost;
        this.totalServiceCost = totalServiceCost;
        this.totalCost = totalCost;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ChargingMode getType() { return type; }
    public void setType(ChargingMode type) { this.type = type; }

    public Double getPower() { return power; }
    public void setPower(Double power) { this.power = power; }

    public PileState getState() { return state; }
    public void setState(PileState state) { this.state = state; }

    public Integer getChargeCount() { return chargeCount; }
    public void setChargeCount(Integer chargeCount) { this.chargeCount = chargeCount; }

    public Long getTotalChargeTime() { return totalChargeTime; }
    public void setTotalChargeTime(Long totalChargeTime) { this.totalChargeTime = totalChargeTime; }

    public Double getTotalChargeAmount() { return totalChargeAmount; }
    public void setTotalChargeAmount(Double totalChargeAmount) { this.totalChargeAmount = totalChargeAmount; }

    public Double getTotalChargeCost() { return totalChargeCost; }
    public void setTotalChargeCost(Double totalChargeCost) { this.totalChargeCost = totalChargeCost; }

    public Double getTotalServiceCost() { return totalServiceCost; }
    public void setTotalServiceCost(Double totalServiceCost) { this.totalServiceCost = totalServiceCost; }

    public Double getTotalCost() { return totalCost; }
    public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private ChargingMode type;
        private Double power;
        private PileState state;
        private Integer chargeCount;
        private Long totalChargeTime;
        private Double totalChargeAmount;
        private Double totalChargeCost;
        private Double totalServiceCost;
        private Double totalCost;

        public Builder id(String id) { this.id = id; return this; }
        public Builder type(ChargingMode type) { this.type = type; return this; }
        public Builder power(Double power) { this.power = power; return this; }
        public Builder state(PileState state) { this.state = state; return this; }
        public Builder chargeCount(Integer chargeCount) { this.chargeCount = chargeCount; return this; }
        public Builder totalChargeTime(Long totalChargeTime) { this.totalChargeTime = totalChargeTime; return this; }
        public Builder totalChargeAmount(Double totalChargeAmount) { this.totalChargeAmount = totalChargeAmount; return this; }
        public Builder totalChargeCost(Double totalChargeCost) { this.totalChargeCost = totalChargeCost; return this; }
        public Builder totalServiceCost(Double totalServiceCost) { this.totalServiceCost = totalServiceCost; return this; }
        public Builder totalCost(Double totalCost) { this.totalCost = totalCost; return this; }

        public ChargingPile build() {
            return new ChargingPile(id, type, power, state, chargeCount, totalChargeTime, totalChargeAmount, totalChargeCost, totalServiceCost, totalCost);
        }
    }
}
