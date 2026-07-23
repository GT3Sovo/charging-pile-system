package com.chargingpile.entity;

public enum VehicleState {
    WAITING_IN_AREA,    // 在等候区排队
    QUEUING_IN_PILE,    // 在充电桩的物理队列中排队
    CHARGING,           // 正在充电中
    FINISHED,           // 充电已完成并支付
    CANCELLED           // 已取消充电请求
}
