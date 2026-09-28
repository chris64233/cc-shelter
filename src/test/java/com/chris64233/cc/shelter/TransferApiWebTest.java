package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TransferApiWebTest {

    @Autowired
    MockMvc mockMvc;

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private long createShelter() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/shelters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"s\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(result, "shelterId");
    }

    private void addRoom(long shelterId, int number, int beds, boolean accessible) throws Exception {
        mockMvc.perform(post("/api/shelters/{id}/rooms", shelterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomNumber\":%d,\"bedCount\":%d,\"accessible\":%s}"
                                .formatted(number, beds, accessible)))
                .andExpect(status().isCreated());
    }

    private String registerFormal(String no, String... identities) throws Exception {
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < identities.length; i++) {
            if (i > 0) {
                members.append(',');
            }
            members.append("{\"identityNo\":\"%s\",\"age\":30}".formatted(identities[i]));
        }
        mockMvc.perform(post("/api/households")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"householdNo\":\"%s\",\"members\":[%s]}".formatted(no, members)))
                .andExpect(status().isCreated());
        return no;
    }

    private long checkIn(String householdNo, long shelterId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(unique("idem"), householdNo, shelterId)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(result, "stayId");
    }

    private long jsonLong(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        String marker = "\"" + field + "\":";
        int start = body.indexOf(marker) + marker.length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    private String jsonString(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    @Test
    void fullTransferLifecycleOverHttp() throws Exception {
        long origin = createShelter();
        long target = createShelter();
        addRoom(origin, 1, 4, false);
        addRoom(target, 1, 4, false);
        String householdNo = registerFormal(unique("f-web"), "id-web-1", "id-web-2");
        long originStayId = checkIn(householdNo, origin);
        String transferNo = unique("TRF-WEB");
        String plannedArrival = "2026-12-31T08:00:00Z";

        // 阶段一：创建申请 201 REQUESTED
        mockMvc.perform(post("/api/transfer-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"transferNo":"%s","householdNo":"%s","targetShelterId":%d,
                                 "plannedArrivalAt":"%s","externalBusinessNo":"EXT-WEB"}""")
                                .formatted(transferNo, householdNo, target, plannedArrival)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.frozenMemberCount").value(2))
                .andExpect(jsonPath("$.externalBusinessNo").value("EXT-WEB"));

        // 进度查询
        mockMvc.perform(get("/api/transfer-applications/{transferNo}", transferNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originShelterId").value(origin))
                .andExpect(jsonPath("$.targetShelterId").value(target))
                .andExpect(jsonPath("$.frozenMembers[0].identityNo").value("id-web-1"));

        // 阶段二：接受 200 ACCEPTED
        mockMvc.perform(post("/api/transfer-applications/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"transferNo\":\"%s\"}"
                                .formatted(unique("idem"), transferNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.reservedRoomId").exists());

        // 两端房间占用：来源仍占 2，目标预留 2
        mockMvc.perform(get("/api/transfer-applications/{transferNo}/rooms", transferNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin.occupied").value(2))
                .andExpect(jsonPath("$.target.occupied").value(0))
                .andExpect(jsonPath("$.target.reservedBeds").value(2))
                .andExpect(jsonPath("$.target.availableBeds").value(2));

        // 交接清单：待交接
        mockMvc.perform(get("/api/transfer-applications/{transferNo}/handover", transferNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].handoverStatus").value("FROZEN"));

        // 未携带期望入住或携带错误值 → 409 STALE_STATE
        mockMvc.perform(post("/api/transfer-applications/arrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoverNo\":\"%s\",\"transferNo\":\"%s\",\"expectedOriginStayId\":%d}"
                                .formatted(unique("HOV"), transferNo, originStayId + 999)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_STATE"));

        // 阶段三：到达确认 200 ARRIVED
        String handoverNo = unique("HOV-WEB");
        MvcResult arrived = mockMvc.perform(post("/api/transfer-applications/arrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoverNo\":\"%s\",\"transferNo\":\"%s\",\"expectedOriginStayId\":%d}"
                                .formatted(handoverNo, transferNo, originStayId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.shelterId").value(target))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andReturn();
        long newStayId = jsonLong(arrived, "stayId");
        assertEquals(handoverNo, jsonString(arrived, "handoverNo"));

        // 相同交接事件号重放 → 首次结果
        mockMvc.perform(post("/api/transfer-applications/arrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoverNo\":\"%s\",\"transferNo\":\"%s\",\"expectedOriginStayId\":%d}"
                                .formatted(handoverNo, transferNo, originStayId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stayId").value(newStayId));

        // 已完成不能取消
        mockMvc.perform(post("/api/transfer-applications/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"transferNo\":\"%s\"}"
                                .formatted(unique("idem"), transferNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_ALREADY_COMPLETED"));

        // 交接清单：全部已交接
        mockMvc.perform(get("/api/transfer-applications/{transferNo}/handover", transferNo))
                .andExpect(jsonPath("$.members[0].handoverStatus").value("HANDED_OVER"))
                .andExpect(jsonPath("$.members[0].targetStayId").value(newStayId));

        // 跨安置点时间线
        mockMvc.perform(get("/api/transfer-applications/households/{no}/timeline", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$[1].fromShelterId").value(origin))
                .andExpect(jsonPath("$[1].toShelterId").value(target))
                .andExpect(jsonPath("$[1].transferNo").value(transferNo));

        // 按家庭查转移列表
        mockMvc.perform(get("/api/transfer-applications/households/{no}", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resultStayId").value(newStayId));

        // 目标房间实际占 2，来源房间 0
        mockMvc.perform(get("/api/shelters/{id}/rooms", target))
                .andExpect(jsonPath("$[0].occupied").value(2));
        mockMvc.perform(get("/api/shelters/{id}/rooms", origin))
                .andExpect(jsonPath("$[0].occupied").value(0));
    }

    @Test
    void rejectedTransferReleasesReservationAndKeepsOriginStayOverHttp() throws Exception {
        long origin = createShelter();
        long target = createShelter();
        addRoom(origin, 1, 4, false);
        addRoom(target, 1, 4, false);
        String householdNo = registerFormal(unique("f-rj"), "id-rj-1");
        checkIn(householdNo, origin);
        String transferNo = unique("TRF-RJ");

        mockMvc.perform(post("/api/transfer-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"transferNo":"%s","householdNo":"%s","targetShelterId":%d,
                                 "plannedArrivalAt":"2026-12-31T08:00:00Z","externalBusinessNo":"E"}""")
                                .formatted(transferNo, householdNo, target)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/transfer-applications/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"transferNo\":\"%s\"}"
                                .formatted(unique("idem"), transferNo)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/transfer-applications/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"transferNo\":\"%s\",\"reason\":\"满员\"}"
                                .formatted(unique("idem"), transferNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("满员"));

        // 来源入住仍有效，目标房间未被占用/预留
        mockMvc.perform(get("/api/households/{no}/stay", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelterId").value(origin));
        mockMvc.perform(get("/api/shelters/{id}/rooms", target))
                .andExpect(jsonPath("$[0].occupied").value(0));
    }

    @Test
    void createTransferNotFoundAndValidationErrors() throws Exception {
        long origin = createShelter();
        addRoom(origin, 1, 4, false);
        // 家庭不存在
        mockMvc.perform(post("/api/transfer-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("""
                                {"transferNo":"%s","householdNo":"NO-SUCH-FAMILY","targetShelterId":%d,
                                 "plannedArrivalAt":"2026-12-31T08:00:00Z","externalBusinessNo":"E"}""")
                                .formatted(unique("TRF"), origin + 99999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOUSEHOLD_NOT_FOUND"));
        // 参数校验：缺少必填字段
        mockMvc.perform(post("/api/transfer-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferNo\":\"\",\"householdNo\":\"f\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
