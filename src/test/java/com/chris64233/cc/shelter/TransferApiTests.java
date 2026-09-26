package com.chris64233.cc.shelter;

import com.chris64233.cc.shelter.household.HouseholdService;
import com.chris64233.cc.shelter.household.dto.MemberRequest;
import com.chris64233.cc.shelter.household.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.shelter.ShelterService;
import com.chris64233.cc.shelter.shelter.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.shelter.dto.CreateShelterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransferApiTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShelterService shelterService;

    @Autowired
    HouseholdService householdService;

    private Long shelterWithRooms(String name, int[][] rooms) {
        Long shelterId = shelterService.createShelter(new CreateShelterRequest(name + "-" + UUID.randomUUID())).id();
        for (int[] room : rooms) {
            shelterService.addRoom(shelterId, new CreateRoomRequest(room[0], room[1], room[2] == 1));
        }
        return shelterId;
    }

    private String household(int size, boolean needsAccessibility) {
        String number = "H-" + UUID.randomUUID();
        List<MemberRequest> members = java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> new MemberRequest("ID-" + UUID.randomUUID(), 25 + i, needsAccessibility && i == 0))
                .toList();
        householdService.register(new RegisterHouseholdRequest(number, members));
        return number;
    }

    private void checkIn(String key, String householdNumber, Long shelterId) throws Exception {
        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + key + "\",\"householdNumber\":\"" + householdNumber
                                + "\",\"shelterId\":" + shelterId + "}"))
                .andExpect(status().isCreated());
    }

    private String transferBody(String key, Long targetShelterId) {
        return "{\"idempotencyKey\":\"" + key + "\",\"targetShelterId\":" + targetShelterId + "}";
    }

    @Test
    void transfersWholeHouseholdAndRecordsEvents() throws Exception {
        Long source = shelterWithRooms("A", new int[][]{{101, 4, 0}});
        Long target = shelterWithRooms("B", new int[][]{{101, 1, 0}, {102, 3, 0}});
        String householdNumber = household(2, false);
        checkIn("ci-1", householdNumber, source);

        mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-1", target)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shelterId").value(target))
                .andExpect(jsonPath("$.roomNumber").value(102))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/shelters/{id}/rooms", source))
                .andExpect(jsonPath("$[0].occupiedBeds").value(0));
        mockMvc.perform(get("/api/shelters/{id}/rooms", target))
                .andExpect(jsonPath("$[?(@.roomNumber == 102)].occupiedBeds").value(2));

        mockMvc.perform(get("/api/households/{num}/stay", householdNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelterId").value(target));

        mockMvc.perform(get("/api/households/{num}/events", householdNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CHECK_IN"))
                .andExpect(jsonPath("$[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$[1].fromShelterId").value(source))
                .andExpect(jsonPath("$[1].toShelterId").value(target));
    }

    @Test
    void failedTransferKeepsOriginalStayIntact() throws Exception {
        Long source = shelterWithRooms("A", new int[][]{{101, 4, 0}});
        Long target = shelterWithRooms("B", new int[][]{{101, 1, 0}});
        String householdNumber = household(3, false);
        checkIn("ci-2", householdNumber, source);

        mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-2", target)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_ROOM"));

        mockMvc.perform(get("/api/households/{num}/stay", householdNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelterId").value(source));
        mockMvc.perform(get("/api/shelters/{id}/rooms", source))
                .andExpect(jsonPath("$[0].occupiedBeds").value(3));
        mockMvc.perform(get("/api/shelters/{id}/rooms", target))
                .andExpect(jsonPath("$[0].occupiedBeds").value(0));
    }

    @Test
    void duplicateTransferReplayReturnsSameResultWithoutDoubleRelease() throws Exception {
        Long source = shelterWithRooms("A", new int[][]{{101, 4, 0}});
        Long target = shelterWithRooms("B", new int[][]{{101, 4, 0}});
        String householdNumber = household(2, false);
        checkIn("ci-3", householdNumber, source);

        String first = mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-3", target)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Object stayId = com.jayway.jsonpath.JsonPath.read(first, "$.stayId");

        mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-3", target)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stayId").value(stayId));

        mockMvc.perform(get("/api/shelters/{id}/rooms", source))
                .andExpect(jsonPath("$[0].occupiedBeds").value(0));
        mockMvc.perform(get("/api/shelters/{id}/rooms", target))
                .andExpect(jsonPath("$[0].occupiedBeds").value(2));
        mockMvc.perform(get("/api/households/{num}/events", householdNumber))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void transferWithoutActiveStayReturnsConflict() throws Exception {
        Long target = shelterWithRooms("B", new int[][]{{101, 4, 0}});
        String householdNumber = household(2, false);

        mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-4", target)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_STAY"));
    }

    @Test
    void transferToSameShelterIsRejected() throws Exception {
        Long source = shelterWithRooms("A", new int[][]{{101, 4, 0}, {102, 4, 0}});
        String householdNumber = household(2, false);
        checkIn("ci-5", householdNumber, source);

        mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-5", source)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_SHELTER_TRANSFER"));
    }

    @Test
    void transferRespectsAccessibilityNeed() throws Exception {
        Long source = shelterWithRooms("A", new int[][]{{101, 4, 1}});
        Long target = shelterWithRooms("B", new int[][]{{101, 4, 0}});
        String householdNumber = household(2, true);
        checkIn("ci-6", householdNumber, source);

        mockMvc.perform(post("/api/households/{num}/transfers", householdNumber)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody("t-6", target)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_ROOM"));

        mockMvc.perform(get("/api/households/{num}/stay", householdNumber))
                .andExpect(jsonPath("$.shelterId").value(source));
    }
}
