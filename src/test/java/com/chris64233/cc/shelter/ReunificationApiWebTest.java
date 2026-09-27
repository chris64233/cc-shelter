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
class ReunificationApiWebTest {

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

    private String registerTemporary(String no, String claimedOriginal, String identity,
                                     boolean needsAccessible) throws Exception {
        mockMvc.perform(post("/api/households/temporary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"householdNo\":\"%s\",\"claimedOriginalHouseholdNo\":\"%s\","
                                        + "\"members\":[{\"identityNo\":\"%s\",\"age\":12,\"needsAccessible\":%s}]}")
                                .formatted(no, claimedOriginal, identity, needsAccessible)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TEMPORARY"))
                .andExpect(jsonPath("$.claimedOriginalHouseholdNo").value(claimedOriginal));
        return no;
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
    void fullReunificationFlowOverHttp() throws Exception {
        long shelter = createShelter();
        addRoom(shelter, 1, 4, false);

        String formalNo = unique("F");
        String tempNo = unique("T");
        String lostIdentity = unique("lost");
        registerFormal(formalNo, unique("dad"), unique("mom"));
        registerTemporary(tempNo, formalNo, lostIdentity, false);

        // 未核验不能合并
        long formalStayId;
        MvcResult formalStay = mockMvc.perform(post("/api/check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(unique("k"), formalNo, shelter))
                ).andExpect(status().isCreated()).andReturn();
        formalStayId = jsonLong(formalStay, "stayId");

        mockMvc.perform(post("/api/temporary-check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(unique("k"), tempNo, shelter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 家庭详情展示临时标记、申报原家庭号与成员核验状态
        mockMvc.perform(get("/api/households/{no}", tempNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TEMPORARY"))
                .andExpect(jsonPath("$.claimedOriginalHouseholdNo").value(formalNo))
                .andExpect(jsonPath("$.members[0].verificationStatus").value("UNVERIFIED"));

        String mergeNo = unique("MRG");
        mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mergeNo\":\"%s\",\"targetHouseholdNo\":\"%s\",\"temporaryHouseholdNos\":[\"%s\"]}"
                                .formatted(mergeNo, formalNo, tempNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_NOT_VERIFIED"));

        // 身份核验（幂等）
        String verifyKey = unique("k");
        String verifyBody = "{\"idempotencyKey\":\"%s\",\"identityNo\":\"%s\"}".formatted(verifyKey, lostIdentity);
        mockMvc.perform(post("/api/identity-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
        MvcResult verifyReplay = mockMvc.perform(post("/api/identity-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andReturn();
        assertTrue(verifyReplay.getResponse().getContentAsString().contains("VERIFIED"));

        // 创建合并申请（业务号幂等）
        String mergeBody = "{\"mergeNo\":\"%s\",\"targetHouseholdNo\":\"%s\",\"temporaryHouseholdNos\":[\"%s\"]}"
                .formatted(mergeNo, formalNo, tempNo);
        mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 错误的 expectedTargetStayId 被拒绝
        mockMvc.perform(post("/api/merges/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"mergeNo\":\"%s\",\"expectedTargetStayId\":%d}"
                                .formatted(unique("k"), mergeNo, formalStayId + 999))
                ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_STATE"));

        // 确认合并
        String confirmKey = unique("k");
        String confirmBody = "{\"idempotencyKey\":\"%s\",\"mergeNo\":\"%s\",\"expectedTargetStayId\":%d}"
                .formatted(confirmKey, mergeNo, formalStayId);
        mockMvc.perform(post("/api/merges/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.memberCount").value(3));
        // 确认幂等重放
        mockMvc.perform(post("/api/merges/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"));

        // 正式家庭事件：CHECK_IN + MERGE
        mockMvc.perform(get("/api/households/{no}/events", formalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].type").value("MERGE"))
                .andExpect(jsonPath("$[1].mergeNo").value(mergeNo));

        // 临时家庭事件：原临时入住记录保留
        mockMvc.perform(get("/api/households/{no}/events", tempNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CHECK_IN"))
                .andExpect(jsonPath("$[1].type").value("TEMP_MERGED"));

        // 成员审计链：临时入住 -> 身份核验 -> 合并
        mockMvc.perform(get("/api/households/members/{id}/events", lostIdentity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("TEMPORARY_CHECK_IN"))
                .andExpect(jsonPath("$[1].type").value("IDENTITY_VERIFIED"))
                .andExpect(jsonPath("$[2].type").value("MERGED"))
                .andExpect(jsonPath("$[2].fromHouseholdNo").value(tempNo))
                .andExpect(jsonPath("$[2].toHouseholdNo").value(formalNo))
                .andExpect(jsonPath("$[2].mergeNo").value(mergeNo));
    }

    @Test
    void unverifiedMergeAndCheckoutRulesOverHttp() throws Exception {
        long shelter = createShelter();
        addRoom(shelter, 1, 4, false);
        String formalNo = unique("F");
        String tempNo = unique("T");
        registerFormal(formalNo, unique("a"));
        registerTemporary(tempNo, formalNo, unique("b"), false);

        MvcResult stay = mockMvc.perform(post("/api/temporary-check-ins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"shelterId\":%d}"
                                .formatted(unique("k"), tempNo, shelter)))
                .andExpect(status().isCreated()).andReturn();
        long stayId = jsonLong(stay, "stayId");

        // 退住
        String checkoutKey = unique("k");
        String checkoutBody = "{\"idempotencyKey\":\"%s\",\"householdNo\":\"%s\",\"expectedStayId\":%d}"
                .formatted(checkoutKey, tempNo, stayId);
        mockMvc.perform(post("/api/checkouts")
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
        mockMvc.perform(post("/api/checkouts")
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
        // 退住后无有效入住
        mockMvc.perform(get("/api/households/{no}/stay", tempNo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_STAY"));
    }

    @Test
    void temporaryHouseholdRequiresClaimedOriginalFamily() throws Exception {
        mockMvc.perform(post("/api/households/temporary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"householdNo\":\"T1\",\"members\":[{\"identityNo\":\"x1\",\"age\":5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
