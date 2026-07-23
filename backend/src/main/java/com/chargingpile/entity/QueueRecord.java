package com.chargingpile.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "queue_records")
public class QueueRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pile_id")
    private ChargingPile pile; // 如果在等候区，则充电桩为空

    @Column(name = "queue_type", nullable = false, length = 20)
    private String queueType; // WAITING_AREA, PILE_QUEUE, FAULT_QUEUE

    @Column(name = "queue_num", nullable = false, length = 10)
    private String queueNum; // 排队号码

    @Column(name = "position", nullable = false)
    private Integer position; // 队列排位次序

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime; // 进入该队列的时间

    public QueueRecord() {}

    public QueueRecord(Long id, Vehicle vehicle, ChargingPile pile, String queueType, String queueNum, Integer position, LocalDateTime entryTime) {
        this.id = id;
        this.vehicle = vehicle;
        this.pile = pile;
        this.queueType = queueType;
        this.queueNum = queueNum;
        this.position = position;
        this.entryTime = entryTime;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }

    public ChargingPile getPile() { return pile; }
    public void setPile(ChargingPile pile) { this.pile = pile; }

    public String getQueueType() { return queueType; }
    public void setQueueType(String queueType) { this.queueType = queueType; }

    public String getQueueNum() { return queueNum; }
    public void setQueueNum(String queueNum) { this.queueNum = queueNum; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public LocalDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalDateTime entryTime) { this.entryTime = entryTime; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private Vehicle vehicle;
        private ChargingPile pile;
        private String queueType;
        private String queueNum;
        private Integer position;
        private LocalDateTime entryTime;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder vehicle(Vehicle vehicle) { this.vehicle = vehicle; return this; }
        public Builder pile(ChargingPile pile) { this.pile = pile; return this; }
        public Builder queueType(String queueType) { this.queueType = queueType; return this; }
        public Builder queueNum(String queueNum) { this.queueNum = queueNum; return this; }
        public Builder position(Integer position) { this.position = position; return this; }
        public Builder entryTime(LocalDateTime entryTime) { this.entryTime = entryTime; return this; }

        public QueueRecord build() {
            return new QueueRecord(id, vehicle, pile, queueType, queueNum, position, entryTime);
        }
    }
}
