package com.chargingpile.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
public class TestSimulatorTest {

    @Autowired
    private TestSimulator testSimulator;

    @Test
    public void runFullSimulation() {
        // 运行官方标准的用例仿真测试，等候区最大容量 N=10，充电桩排队队列长度 M=3 (即1辆在充+2辆等待)
        testSimulator.runSimulation(null, 10, 3);
    }
}
