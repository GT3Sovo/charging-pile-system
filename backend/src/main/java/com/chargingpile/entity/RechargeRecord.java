package com.chargingpile.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "recharge_records")
public class RechargeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pile_id", nullable = false)
    private ChargingPile pile;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime; // 充电启动时间

    @Column(name = "end_time")
    private LocalDateTime endTime; // 充电停止时间

    @Column(name = "charged_amount", nullable = false)
    private Double chargedAmount; // 充电电量

    @Column(name = "charge_cost", nullable = false)
    private Double chargeCost; // 充电电费

    @Column(name = "service_cost", nullable = false)
    private Double serviceCost; // 服务费

    @Column(name = "total_cost", nullable = false)
    private Double totalCost; // 总费用

    public RechargeRecord() {}

    public RechargeRecord(Long id, Vehicle vehicle, ChargingPile pile, LocalDateTime startTime, LocalDateTime endTime,
                          Double chargedAmount, Double chargeCost, Double serviceCost, Double totalCost) {
        this.id = id;
        this.vehicle = vehicle;
        this.pile = pile;
        this.startTime = startTime;
        this.endTime = endTime;
        this.chargedAmount = chargedAmount;
        this.chargeCost = chargeCost;
        this.serviceCost = serviceCost;
        this.totalCost = totalCost;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public ChargingPile getPile() { return pile; }
    public void setPile(ChargingPile pile) { this.pile = pile; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public Double getChargedAmount() { return chargedAmount; }
    public void setChargedAmount(Double chargedAmount) { this.chargedAmount = chargedAmount; }

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
        private Long id;
        private Vehicle vehicle;
        private ChargingPile pile;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Double chargedAmount;
        private Double chargeCost;
        private Double serviceCost;
        private Double totalCost;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder vehicle(Vehicle vehicle) { this.vehicle = vehicle; return this; }
        public Builder pile(ChargingPile pile) { this.pile = pile; return this; }
        public Builder startTime(LocalDateTime startTime) { this.startTime = startTime; return this; }
        public Builder endTime(LocalDateTime endTime) { this.endTime = endTime; return this; }
        public Builder chargedAmount(Double chargedAmount) { this.chargedAmount = chargedAmount; return this; }
        public Builder chargeCost(Double chargeCost) { this.chargeCost = chargeCost; return this; }
        public Builder serviceCost(Double serviceCost) { this.serviceCost = serviceCost; return this; }
        public Builder totalCost(Double totalCost) { this.totalCost = totalCost; return this; }

        public RechargeRecord build() {
            return new RechargeRecord(id, vehicle, pile, startTime, endTime, chargedAmount, chargeCost, serviceCost, totalCost);
        }
    }
}
