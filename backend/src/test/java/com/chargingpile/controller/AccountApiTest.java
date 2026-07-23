package com.chargingpile.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AccountApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerVehicleAndReset_shouldKeepCustomerProfile() throws Exception {
        mockMvc.perform(post("/api/sim/reset"))
                .andExpect(status().isOk());

        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"acceptance_user\",\"password\":\"accept123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("acceptance_user"))
                .andReturn().getRequest().getSession(false);

        mockMvc.perform(post("/api/account/vehicles")
                        .session(session)
                        .contentType("application/json")
                        .content("{\"vehicleId\":\"京A10001\",\"vehicleType\":\"测试车\",\"batteryCapacity\":72,\"currentCapacity\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("京A10001"));

        mockMvc.perform(post("/api/sim/reset"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/account/vehicles").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("京A10001"))
                .andExpect(jsonPath("$[0].state").value("CANCELLED"));
    }

    @Test
    void reportSummary_shouldSupportWeeklyAndMonthlyPeriods() throws Exception {
        mockMvc.perform(get("/api/reports/summary").param("period", "weekly").param("date", "2026-06-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("weekly"))
                .andExpect(jsonPath("$.startDate").value("2026-06-08"))
                .andExpect(jsonPath("$.endDate").value("2026-06-14"))
                .andExpect(jsonPath("$.rows.length()").value(5));

        mockMvc.perform(get("/api/reports/summary").param("period", "monthly").param("date", "2026-06-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("monthly"))
                .andExpect(jsonPath("$.startDate").value("2026-06-01"))
                .andExpect(jsonPath("$.endDate").value("2026-06-30"));
    }

    @Test
    void reset_shouldExposeClearedBusinessDataAndRunInitialEventOnFirstTick() throws Exception {
        mockMvc.perform(post("/api/sim/reset"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDataCounts.queueRecords").value(0))
                .andExpect(jsonPath("$.businessDataCounts.rechargeRecords").value(0))
                .andExpect(jsonPath("$.businessDataCounts.details").value(0))
                .andExpect(jsonPath("$.businessDataCounts.bills").value(0))
                .andExpect(jsonPath("$.events[0].executed").value(false));

        mockMvc.perform(post("/api/sim/tick").param("seconds", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDataCounts.queueRecords").value(1))
                .andExpect(jsonPath("$.businessDataCounts.rechargeRecords").value(1))
                .andExpect(jsonPath("$.events[0].executed").value(true));
    }

    @Test
    void exportCsv_shouldReturnDownloadableUtf8File() throws Exception {
        mockMvc.perform(post("/api/sim/reset"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/exports/csv")
                        .param("type", "details")
                        .param("scope", "all"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("车辆编号")))
                .andExpect(content().string(containsString("详单编号")));
    }
}
