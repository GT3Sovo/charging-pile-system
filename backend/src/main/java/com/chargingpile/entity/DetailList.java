package com.chargingpile.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "detail_lists")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class DetailList {
    @Id
    @Column(name = "detail_no", nullable = false, length = 50)
    private String detailNo; // 详单编号

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // 详单生成时间

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pile_id", nullable = false)
    private ChargingPile pile; // 充电桩关联

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle; // 车辆关联

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_no")
    @JsonIgnore
    private Bill bill; // 账单关联

    @Column(name = "charged_amount", nullable = false)
    private Double chargedAmount; // 充电电量

    @Column(name = "charge_duration", nullable = false)
    private Long chargeDuration; // 充电时长

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime; // 启动时间

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime; // 停止时间

    @Column(name = "charge_cost", nullable = false)
    private Double chargeCost; // 充电费用

    @Column(name = "service_cost", nullable = false)
    private Double serviceCost; // 服务费用

    @Column(name = "total_cost", nullable = false)
    private Double totalCost; // 总费用

    public DetailList() {}

    public DetailList(String detailNo, LocalDateTime createdAt, ChargingPile pile, Vehicle vehicle, Bill bill,
                      Double chargedAmount, Long chargeDuration, LocalDateTime startTime, LocalDateTime endTime,
                      Double chargeCost, Double serviceCost, Double totalCost) {
        this.detailNo = detailNo;
        this.createdAt = createdAt;
        this.pile = pile;
        this.vehicle = vehicle;
        this.bill = bill;
        this.chargedAmount = chargedAmount;
        this.chargeDuration = chargeDuration;
        this.startTime = startTime;
        this.endTime = endTime;
        this.chargeCost = chargeCost;
        this.serviceCost = serviceCost;
        this.totalCost = totalCost;
    }

    public String getDetailNo() { return detailNo; }
    public void setDetailNo(String detailNo) { this.detailNo = detailNo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public ChargingPile getPile() { return pile; }
    public void setPile(ChargingPile pile) { this.pile = pile; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public Bill getBill() { return bill; }
    public void setBill(Bill bill) { this.bill = bill; }

    public Double getChargedAmount() { return chargedAmount; }
    public void setChargedAmount(Double chargedAmount) { this.chargedAmount = chargedAmount; }

    public Long getChargeDuration() { return chargeDuration; }
    public void setChargeDuration(Long chargeDuration) { this.chargeDuration = chargeDuration; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public Double getChargeCost() { return chargeCost; }
    public void setChargeCost(Double chargeCost) { this.chargeCost = chargeCost; }

    public Double getServiceCost() { return serviceCost; }
    public void setServiceCost(Double serviceCost) { this.serviceCost = serviceCost; }

    public Double getTotalCost() { return totalCost; }
    public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String detailNo;
        private LocalDateTime createdAt;
        private ChargingPile pile;
        private Vehicle vehicle;
        private Bill bill;
        private Double chargedAmount;
        private Long chargeDuration;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Double chargeCost;
        private Double serviceCost;
        private Double totalCost;

        public Builder detailNo(String detailNo) { this.detailNo = detailNo; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder pile(ChargingPile pile) { this.pile = pile; return this; }
        public Builder vehicle(Vehicle vehicle) { this.vehicle = vehicle; return this; }
        public Builder bill(Bill bill) { this.bill = bill; return this; }
        public Builder chargedAmount(Double chargedAmount) { this.chargedAmount = chargedAmount; return this; }
        public Builder chargeDuration(Long chargeDuration) { this.chargeDuration = chargeDuration; return this; }
        public Builder startTime(LocalDateTime startTime) { this.startTime = startTime; return this; }
        public Builder endTime(LocalDateTime endTime) { this.endTime = endTime; return this; }
        public Builder chargeCost(Double chargeCost) { this.chargeCost = chargeCost; return this; }
        public Builder serviceCost(Double serviceCost) { this.serviceCost = serviceCost; return this; }
        public Builder totalCost(Double totalCost) { this.totalCost = totalCost; return this; }

        public DetailList build() {
            return new DetailList(detailNo, createdAt, pile, vehicle, bill, chargedAmount, chargeDuration, startTime, endTime, chargeCost, serviceCost, totalCost);
        }
    }
}
