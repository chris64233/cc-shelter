package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class ApiWebTest {

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

    private String registerHousehold(int size, boolean needsAccessible) throws Exception {
        String no = unique("hh");
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                members.append(',');
            }
            members.append("{\"identityNo\":\"%s\",\"age\":30,\"needsAccessible\":%s}"
                    .formatted(unique("id"), needsAccessible && i == 0));
        }
        mockMvc.perform(post("/api/households")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"householdNo\":\"%s\",\"members\":[%s]}".formatted(no, members)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(size));
        return no;
    }

    private MvcResult checkIn(String idemKey, String householdNo, long shelterId) throws Exception {
        return mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(idemKey, householdNo, shelterId)))
                .andReturn();
    }

    private long jsonLong(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        String marker = "\"" + field + "\":";
        int start = body.indexOf(marker) + marker.length();
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    @Test
    void fullCheckInAndTransferFlowOverHttp() throws Exception {
        long shelterA = createShelter();
        addRoom(shelterA, 1, 4, false);
        long shelterB = createShelter();
        addRoom(shelterB, 1, 4, true);

        String householdNo = registerHousehold(2, true);

        // 无障碍家庭不能进入没有无障碍房间的安置点
        mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(unique("k"), householdNo, shelterA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_ROOM"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        String idemKey = unique("k");
        MvcResult checkedIn = checkIn(idemKey, householdNo, shelterB);
        assertEquals(201, checkedIn.getResponse().getStatus());
        long stayId = jsonLong(checkedIn, "stayId");

        // 相同幂等键重放返回原结果
        MvcResult replay = checkIn(idemKey, householdNo, shelterB);
        assertEquals(checkedIn.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        // 相同幂等键不同内容返回冲突
        mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(idemKey, householdNo, shelterA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 查询当前入住
        mockMvc.perform(get("/api/households/{no}/stay", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stayId").value(stayId))
                .andExpect(jsonPath("$.shelterId").value(shelterB))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 查询房间占用
        mockMvc.perform(get("/api/shelters/{id}/rooms", shelterB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].occupied").value(2))
                .andExpect(jsonPath("$[0].remaining").value(2));

        // 整体转移到另一个安置点
        long shelterC = createShelter();
        addRoom(shelterC, 1, 4, true);
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\","
                                        + "\"targetShelterId\":%d,\"expectedStayId\":%d}")
                                .formatted(unique("k"), householdNo, shelterC, stayId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shelterId").value(shelterC));

        mockMvc.perform(get("/api/shelters/{id}/rooms", shelterB))
                .andExpect(jsonPath("$[0].occupied").value(0));
        mockMvc.perform(get("/api/shelters/{id}/rooms", shelterC))
                .andExpect(jsonPath("$[0].occupied").value(2));

        // 不可变事件流：CHECK_IN + TRANSFER
        mockMvc.perform(get("/api/households/{no}/events", householdNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CHECK_IN"))
                .andExpect(jsonPath("$[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$[1].fromShelterId").value(shelterB))
                .andExpect(jsonPath("$[1].toShelterId").value(shelterC));
    }

    @Test
    void validationErrorUsesUniformErrorJson() throws Exception {
        mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"householdNo\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void duplicateHouseholdNoRejectedByUniqueConstraint() throws Exception {
        String no = registerHousehold(1, false);
        mockMvc.perform(post("/api/households")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"householdNo\":\"%s\",\"members\":[{\"identityNo\":\"%s\",\"age\":1}]}"
                                .formatted(no, unique("id"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_KEY"));
    }

    @Test
    void unknownHouseholdReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/households/{no}/stay", unique("missing")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOUSEHOLD_NOT_FOUND"));
    }

    @Test
    void reunificationFlowOverHttp() throws Exception {
        long shelter = createShelter();
        addRoom(shelter, 1, 4, false);
        addRoom(shelter, 2, 1, false);
        String familyNo = registerHousehold(2, false);
        String identityNo = unique("lost");

        // 正式家庭先整体入住
        checkIn(unique("k"), familyNo, shelter);

        // 走散成员以临时家庭身份入住
        mockMvc.perform(post("/api/temporary-check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"idempotencyKey\":\"%s\",\"identityNo\":\"%s\",\"age\":12,"
                                        + "\"declaredHouseholdNo\":\"%s\",\"shelterId\":%d}")
                                .formatted(unique("k"), identityNo, familyNo, shelter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.householdNo").value("TMP-" + identityNo))
                .andExpect(jsonPath("$.memberCount").value(1));

        // 临时家庭成员初始为未核验
        mockMvc.perform(get("/api/households/{no}/members", "TMP-" + identityNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$[0].declaredHouseholdNo").value(familyNo));

        // 未核验不能并入正式家庭
        mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"idempotencyKey\":\"%s\",\"targetHouseholdNo\":\"%s\","
                                        + "\"temporaryHouseholdNos\":[\"TMP-%s\"]}")
                                .formatted(unique("k"), familyNo, identityNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNVERIFIED_MEMBER"));

        // 身份核验（幂等）
        String verifyKey = unique("k");
        String verifyBody = "{\"idempotencyKey\":\"%s\",\"identityNo\":\"%s\"}"
                .formatted(verifyKey, identityNo);
        MvcResult verified = mockMvc.perform(post("/api/identity-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("VERIFIED"))
                .andReturn();
        mockMvc.perform(post("/api/identity-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isCreated())
                .andExpect(result ->
                        assertEquals(verified.getResponse().getContentAsString(),
                                result.getResponse().getContentAsString()));

        // 合并（幂等）
        String mergeKey = unique("k");
        String mergeBody = ("{\"idempotencyKey\":\"%s\",\"targetHouseholdNo\":\"%s\","
                + "\"temporaryHouseholdNos\":[\"TMP-%s\"]}").formatted(mergeKey, familyNo, identityNo);
        MvcResult merged = mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.roomChanged").value(false))
                .andReturn();
        mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody))
                .andExpect(status().isCreated())
                .andExpect(result ->
                        assertEquals(merged.getResponse().getContentAsString(),
                                result.getResponse().getContentAsString()));

        // 家庭关系变化：成员进入正式家庭，临时家庭清空
        mockMvc.perform(get("/api/households/{no}/members", familyNo))
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/households/{no}/members", "TMP-" + identityNo))
                .andExpect(jsonPath("$.length()").value(0));

        // 完整房间迁移链：临时家庭 CHECK_IN + MERGE，正式家庭 CHECK_IN + MERGE
        mockMvc.perform(get("/api/households/{no}/events", "TMP-" + identityNo))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CHECK_IN"))
                .andExpect(jsonPath("$[1].type").value("MERGE"));
        mockMvc.perform(get("/api/households/{no}/events", familyNo))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].type").value("MERGE"))
                .andExpect(jsonPath("$[1].memberCount").value(3));

        // 身份确认记录可查
        mockMvc.perform(get("/api/members/{id}/verifications", identityNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].result").value("VERIFIED"));

        // 退住释放全部床位
        long stayId = jsonLong(merged, "stayId");
        mockMvc.perform(post("/api/check-outs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"expectedStayId\":%d}"
                                .formatted(unique("k"), familyNo, stayId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
        mockMvc.perform(get("/api/shelters/{id}/rooms", shelter))
                .andExpect(jsonPath("$[0].occupied").value(0));
    }
}
