package com.chargingpile.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vehicles")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Vehicle {
    @Id
    @Column(name = "id", nullable = false, length = 20)
    private String id; // 车牌号

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user; // 车主

    @Column(name = "vehicle_type", nullable = false, length = 50)
    private String vehicleType; // 车辆型号

    @Column(name = "battery_capacity", nullable = false)
    private Double batteryCapacity; // 电池总容量

    @Column(name = "current_capacity", nullable = false)
    private Double currentCapacity; // 当前容量

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_mode", nullable = false, length = 10)
    private ChargingMode chargeMode; // 充电模式

    @Column(name = "requested_amount", nullable = false)
    private Double requestedAmount; // 请求充电量

    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime; // 充电请求时间

    @Column(name = "queue_num", length = 10)
    private String queueNum; // 排队号码

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleState state; // 状态

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pile_id")
    private ChargingPile currentPile; // 当前桩

    public Vehicle() {}

    public Vehicle(String id, User user, String vehicleType, Double batteryCapacity, Double currentCapacity,
                   ChargingMode chargeMode, Double requestedAmount, LocalDateTime requestTime, String queueNum,
                   VehicleState state, ChargingPile currentPile) {
        this.id = id;
        this.user = user;
        this.vehicleType = vehicleType;
        this.batteryCapacity = batteryCapacity;
        this.currentCapacity = currentCapacity;
        this.chargeMode = chargeMode;
        this.requestedAmount = requestedAmount;
        this.requestTime = requestTime;
        this.queueNum = queueNum;
        this.state = state;
        this.currentPile = currentPile;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }

    public Double getBatteryCapacity() { return batteryCapacity; }
    public void setBatteryCapacity(Double batteryCapacity) { this.batteryCapacity = batteryCapacity; }

    public Double getCurrentCapacity() { return currentCapacity; }
    public void setCurrentCapacity(Double currentCapacity) { this.currentCapacity = currentCapacity; }

    public ChargingMode getChargeMode() { return chargeMode; }
    public void setChargeMode(ChargingMode chargeMode) { this.chargeMode = chargeMode; }

    public Double getRequestedAmount() { return requestedAmount; }
    public void setRequestedAmount(Double requestedAmount) { this.requestedAmount = requestedAmount; }

    public LocalDateTime getRequestTime() { return requestTime; }
    public void setRequestTime(LocalDateTime requestTime) { this.requestTime = requestTime; }

    public String getQueueNum() { return queueNum; }
    public void setQueueNum(String queueNum) { this.queueNum = queueNum; }

    public VehicleState getState() { return state; }
    public void setState(VehicleState state) { this.state = state; }

    public ChargingPile getCurrentPile() { return currentPile; }
    public void setCurrentPile(ChargingPile currentPile) { this.currentPile = currentPile; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private User user;
        private String vehicleType;
        private Double batteryCapacity;
        private Double currentCapacity;
        private ChargingMode chargeMode;
        private Double requestedAmount;
        private LocalDateTime requestTime;
        private String queueNum;
        private VehicleState state;
        private ChargingPile currentPile;

        public Builder id(String id) { this.id = id; return this; }
        public Builder user(User user) { this.user = user; return this; }
        public Builder vehicleType(String vehicleType) { this.vehicleType = vehicleType; return this; }
        public Builder batteryCapacity(Double batteryCapacity) { this.batteryCapacity = batteryCapacity; return this; }
        public Builder currentCapacity(Double currentCapacity) { this.currentCapacity = currentCapacity; return this; }
        public Builder chargeMode(ChargingMode chargeMode) { this.chargeMode = chargeMode; return this; }
        public Builder requestedAmount(Double requestedAmount) { this.requestedAmount = requestedAmount; return this; }
        public Builder requestTime(LocalDateTime requestTime) { this.requestTime = requestTime; return this; }
        public Builder queueNum(String queueNum) { this.queueNum = queueNum; return this; }
        public Builder state(VehicleState state) { this.state = state; return this; }
        public Builder currentPile(ChargingPile currentPile) { this.currentPile = currentPile; return this; }

        public Vehicle build() {
            return new Vehicle(id, user, vehicleType, batteryCapacity, currentCapacity, chargeMode, requestedAmount, requestTime, queueNum, state, currentPile);
        }
    }
}
