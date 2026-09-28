package com.chris64233.cc.shelter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"s\"}"))
                .andExpect(status().isCreated()).andReturn();
        return jsonLong(result, "shelterId");
    }

    private void addRoom(long shelterId, int number, int beds, boolean accessible) throws Exception {
        mockMvc.perform(post("/api/shelters/{id}/rooms", shelterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomNumber\":%d,\"bedCount\":%d,\"accessible\":%s}"
                                .formatted(number, beds, accessible)))
                .andExpect(status().isCreated());
    }

    private void registerFormal(String no, String... identities) throws Exception {
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
    }

    private long checkIn(String householdNo, long shelterId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(unique("k"), householdNo, shelterId))
                ).andExpect(status().isCreated()).andReturn();
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

    @Test
    void fullTwoStageTransferFlowOverHttp() throws Exception {
        long origin = createShelter();
        addRoom(origin, 1, 4, false);
        long target = createShelter();
        addRoom(target, 1, 4, false);
        addRoom(target, 2, 2, false);

        String householdNo = unique("F");
        registerFormal(householdNo, unique("a"), unique("b"));
        long originStayId = checkIn(householdNo, origin);

        String transferNo = unique("TRF");
        Instant arrival = Instant.now().plus(1, ChronoUnit.DAYS);
        String createBody = ("{\"transferNo\":\"%s\",\"householdNo\":\"%s\",\"targetShelterId\":%d,"
                + "\"plannedArrivalAt\":\"%s\"}").formatted(transferNo, householdNo, target, arrival);
        // 创建申请（业务号幂等）
        mockMvc.perform(post("/api/transfer-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.members.length()").value(2));
        mockMvc.perform(post("/api/transfer-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        // 接受：预留房间（选中剩余床位最少的 2 床房），来源床位不释放
        String acceptBody = "{\"transferNo\":\"%s\"}".formatted(transferNo);
        mockMvc.perform(post("/api/transfer-requests/accept")
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.targetRoomNumber").value(2))
                .andExpect(jsonPath("$.targetReservedBeds").value(2));
        mockMvc.perform(get("/api/shelters/{id}/rooms", target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].occupied").value(0))
                .andExpect(jsonPath("$[1].reservedBeds").value(2));
        mockMvc.perform(get("/api/shelters/{id}/rooms", origin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occupied").value(2));

        // 未接受校验：不存在的申请到达返回 404
        // 错误的期望来源入住
        String handoverNo = unique("HND");
        mockMvc.perform(post("/api/transfer-requests/arrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferNo\":\"%s\",\"handoverNo\":\"%s\",\"expectedOriginStayId\":%d}"
                                .formatted(transferNo, handoverNo, originStayId + 999)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_STATE"));

        // 到达确认
        String arriveBody = "{\"transferNo\":\"%s\",\"handoverNo\":\"%s\",\"expectedOriginStayId\":%d}"
                .formatted(transferNo, handoverNo, originStayId);
        mockMvc.perform(post("/api/transfer-requests/arrive")
                        .contentType(MediaType.APPLICATION_JSON).content(arriveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.shelterId").value(target))
                .andExpect(jsonPath("$.roomNumber").value(2))
                .andExpect(jsonPath("$.memberCount").value(2));
        // 交接事件号幂等重放
        mockMvc.perform(post("/api/transfer-requests/arrive")
                        .contentType(MediaType.APPLICATION_JSON).content(arriveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 进度查询为终态
        mockMvc.perform(get("/api/transfer-requests/{no}", transferNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.handoverNo").value(handoverNo))
                .andExpect(jsonPath("$.originOccupied").value(0))
                .andExpect(jsonPath("$.targetOccupied").value(2))
                .andExpect(jsonPath("$.targetReservedBeds").value(0));

        // 成员交接清单
        mockMvc.perform(get("/api/transfer-requests/{no}/handover", transferNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.members[0].currentlyStaying").value(true));

        // 跨安置点入住时间线
        mockMvc.perform(get("/api/households/{no}/timeline", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CHECK_IN"))
                .andExpect(jsonPath("$[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$[1].transferNo").value(transferNo))
                .andExpect(jsonPath("$[1].handoverNo").value(handoverNo));

        // 完成后拒绝返回 409
        mockMvc.perform(post("/api/transfer-requests/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferNo\":\"%s\",\"reason\":\"x\"}".formatted(transferNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_ALREADY_COMPLETED"));
    }

    @Test
    void rejectFlowKeepsOriginStayOverHttp() throws Exception {
        long origin = createShelter();
        addRoom(origin, 1, 4, false);
        long target = createShelter();
        addRoom(target, 1, 1, false); // 容不下 2 人家庭

        String householdNo = unique("F");
        registerFormal(householdNo, unique("a"), unique("b"));
        long originStayId = checkIn(householdNo, origin);

        String transferNo = unique("TRF");
        mockMvc.perform(post("/api/transfer-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferNo\":\"%s\",\"householdNo\":\"%s\",\"targetShelterId\":%d,\"plannedArrivalAt\":\"%s\"}"
                                .formatted(transferNo, householdNo, target,
                                        Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isCreated());

        // 目标无合适房间，接受失败但来源入住不变
        mockMvc.perform(post("/api/transfer-requests/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferNo\":\"%s\"}".formatted(transferNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_ROOM"));
        mockMvc.perform(get("/api/households/{no}/stay", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stayId").value(originStayId));

        // 目标拒绝（申请尚在 REQUESTED），来源入住仍有效
        mockMvc.perform(post("/api/transfer-requests/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferNo\":\"%s\",\"reason\":\"full\"}".formatted(transferNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(get("/api/households/{no}/stay", householdNo))
                .andExpect(status().isOk());
    }
}
