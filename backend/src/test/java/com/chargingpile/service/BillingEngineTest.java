package com.chargingpile.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class BillingEngineTest {

    private BillingEngine billingEngine;

    @BeforeEach
    public void setUp() {
        billingEngine = new BillingEngine();
    }

    @Test
    public void testCalculate_invalidTime_shouldThrowException() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 12, 10, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 12, 9, 0, 0); // 结束时间早于开始时间
        assertThrows(IllegalArgumentException.class, () -> billingEngine.calculate(start, end, 10.0));
    }

    @Test
    public void testCalculate_zeroDuration_shouldReturnZeros() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 12, 10, 0, 0);
        BillingEngine.BillingResult res = billingEngine.calculate(start, start, 0.0);
        assertEquals(0, res.getTotalDurationSeconds());
        assertEquals(0.0, res.getChargedAmount());
        assertEquals(0.0, res.getTotalCost());
    }

    @Test
    public void testCalculate_singleTariffPeriod_peakOnly() {
        // Peak hours (10:00 ~ 15:00)
        // V22 charged 10kWh from 10:20:00 to 10:40:00 (all inside Peak)
        LocalDateTime start = LocalDateTime.of(2026, 6, 12, 10, 20, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 12, 10, 40, 0); // 20 mins = 1200 seconds
        double amount = 10.0;

        BillingEngine.BillingResult res = billingEngine.calculate(start, end, amount);

        assertEquals(1200, res.getTotalDurationSeconds());
        assertEquals(10.0, res.getPeakAmount());
        assertEquals(0.0, res.getRegularAmount());
        assertEquals(0.0, res.getOffPeakAmount());

        // Peak price = 1.0, Service fee = 0.8 -> Total Peak price = 1.8 * 10 = 18.0
        assertEquals(10.0 * 1.0, res.getPeakChargeCost(), 1e-4);
        assertEquals(10.0 * 0.8, res.getTotalServiceCost(), 1e-4);
        assertEquals(18.0, res.getTotalCost(), 1e-4);
    }

    @Test
    public void testCalculate_boundarySwitchPoint() {
        // Starts exactly at 09:30:00 (Regular: 07:00 ~ 10:00)
        // Ends exactly at 10:30:00 (Peak: 10:00 ~ 15:00)
        // Total duration = 60 minutes = 3600 seconds
        // Regular part = 30 minutes (09:30 ~ 10:00) -> 50%
        // Peak part = 30 minutes (10:00 ~ 10:30) -> 50%
        LocalDateTime start = LocalDateTime.of(2026, 6, 12, 9, 30, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 12, 10, 30, 0);
        double amount = 20.0; // 20 degrees total

        BillingEngine.BillingResult res = billingEngine.calculate(start, end, amount);

        assertEquals(3600, res.getTotalDurationSeconds());
        assertEquals(10.0, res.getRegularAmount(), 1e-4);
        assertEquals(10.0, res.getPeakAmount(), 1e-4);
        assertEquals(0.0, res.getOffPeakAmount(), 1e-4);

        // Regular charge = 10 * 0.7 = 7.0
        // Peak charge = 10 * 1.0 = 10.0
        // Service fee = 20 * 0.8 = 16.0
        // Total cost = 7.0 + 10.0 + 16.0 = 33.0
        assertEquals(7.0, res.getRegularChargeCost(), 1e-4);
        assertEquals(10.0, res.getPeakChargeCost(), 1e-4);
        assertEquals(16.0, res.getTotalServiceCost(), 1e-4);
        assertEquals(33.0, res.getTotalCost(), 1e-4);
    }

    @Test
    public void testCalculate_crossDayPeriod() {
        // Starts at 22:30:00 on Day 1 (Regular: 21:00 ~ 23:00)
        // Ends at 01:30:00 on Day 2 (Off-peak: 23:00 ~ 07:00)
        // Total duration = 3 hours = 10800 seconds
        // Regular part = 30 mins (22:30 ~ 23:00) -> 30/180 = 16.6667%
        // Off-peak part = 2 hours 30 mins (23:00 ~ 01:30) -> 150/180 = 83.3333%
        LocalDateTime start = LocalDateTime.of(2026, 6, 12, 22, 30, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 13, 1, 30, 0);
        double amount = 36.0; // 36 degrees total

        BillingEngine.BillingResult res = billingEngine.calculate(start, end, amount);

        assertEquals(10800, res.getTotalDurationSeconds());
        assertEquals(6.0, res.getRegularAmount(), 1e-4);
        assertEquals(30.0, res.getOffPeakAmount(), 1e-4);
        assertEquals(0.0, res.getPeakAmount(), 1e-4);

        // Regular charge = 6.0 * 0.7 = 4.2
        // Off-peak charge = 30.0 * 0.4 = 12.0
        // Service fee = 36.0 * 0.8 = 28.8
        // Total cost = 4.2 + 12.0 + 28.8 = 45.0
        assertEquals(4.2, res.getRegularChargeCost(), 1e-4);
        assertEquals(12.0, res.getOffPeakChargeCost(), 1e-4);
        assertEquals(28.8, res.getTotalServiceCost(), 1e-4);
        assertEquals(45.0, res.getTotalCost(), 1e-4);
    }
}
