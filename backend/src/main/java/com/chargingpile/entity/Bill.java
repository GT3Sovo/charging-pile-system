package com.chargingpile.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "bills")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Bill {
    @Id
    @Column(name = "bill_no", nullable = false, length = 50)
    private String billNo; // 账单编号

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle; // 车辆关联

    @Column(name = "station_name", nullable = false, length = 100)
    private String stationName; // 充电站名称

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // 账单时间

    @Column(name = "charged_amount", nullable = false)
    private Double chargedAmount; // 充电电量

    @Column(name = "charge_cost", nullable = false)
    private Double chargeCost; // 充电费用

    @Column(name = "service_cost", nullable = false)
    private Double serviceCost; // 服务费

    @Column(name = "parking_fee", nullable = false)
    private Double parkingFee; // 超市停车超时费

    @Column(name = "total_cost", nullable = false)
    private Double totalCost; // 总费用

    @Column(name = "is_paid", nullable = false)
    private Boolean isPaid; // 支付状态

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<DetailList> details; // 详单关联

    public Bill() {}

    public Bill(String billNo, Vehicle vehicle, String stationName, LocalDateTime createdAt, Double chargedAmount,
                Double chargeCost, Double serviceCost, Double parkingFee, Double totalCost, Boolean isPaid,
                List<DetailList> details) {
        this.billNo = billNo;
        this.vehicle = vehicle;
        this.stationName = stationName;
        this.createdAt = createdAt;
        this.chargedAmount = chargedAmount;
        this.chargeCost = chargeCost;
        this.serviceCost = serviceCost;
        this.parkingFee = parkingFee;
        this.totalCost = totalCost;
        this.isPaid = isPaid;
        this.details = details;
    }

    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Double getChargedAmount() { return chargedAmount; }
    public void setChargedAmount(Double chargedAmount) { this.chargedAmount = chargedAmount; }

    public Double getChargeCost() { return chargeCost; }
    public void setChargeCost(Double chargeCost) { this.chargeCost = chargeCost; }

    public Double getServiceCost() { return serviceCost; }
    public void setServiceCost(Double serviceCost) { this.serviceCost = serviceCost; }

    public Double getParkingFee() { return parkingFee; }
    public void setParkingFee(Double parkingFee) { this.parkingFee = parkingFee; }

    public Double getTotalCost() { return totalCost; }
    public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

    public Boolean getIsPaid() { return isPaid; }
    public void setIsPaid(Boolean isPaid) { this.isPaid = isPaid; }

    public List<DetailList> getDetails() { return details; }
    public void setDetails(List<DetailList> details) { this.details = details; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String billNo;
        private Vehicle vehicle;
        private String stationName;
        private LocalDateTime createdAt;
        private Double chargedAmount;
        private Double chargeCost;
        private Double serviceCost;
        private Double parkingFee;
        private Double totalCost;
        private Boolean isPaid;
        private List<DetailList> details;

        public Builder billNo(String billNo) { this.billNo = billNo; return this; }
        public Builder vehicle(Vehicle vehicle) { this.vehicle = vehicle; return this; }
        public Builder station_name(String stationName) { this.stationName = stationName; return this; } // keep this station_name for backward compatibility with Builder call
        public Builder stationName(String stationName) { this.stationName = stationName; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder chargedAmount(Double chargedAmount) { this.chargedAmount = chargedAmount; return this; }
        public Builder chargeCost(Double chargeCost) { this.chargeCost = chargeCost; return this; }
        public Builder serviceCost(Double serviceCost) { this.serviceCost = serviceCost; return this; }
        public Builder parkingFee(Double parkingFee) { this.parkingFee = parkingFee; return this; }
        public Builder totalCost(Double totalCost) { this.totalCost = totalCost; return this; }
        public Builder isPaid(Boolean isPaid) { this.isPaid = isPaid; return this; }
        public Builder details(List<DetailList> details) { this.details = details; return this; }

        public Bill build() {
            return new Bill(billNo, vehicle, stationName, createdAt, chargedAmount, chargeCost, serviceCost, parkingFee, totalCost, isPaid, details);
        }
    }
}
