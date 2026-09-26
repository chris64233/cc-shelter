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
class CheckInApiTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShelterService shelterService;

    @Autowired
    HouseholdService householdService;

    private Long shelterWithRooms(int[][] rooms) {
        Long shelterId = shelterService.createShelter(new CreateShelterRequest("安置点-" + UUID.randomUUID())).id();
        for (int[] room : rooms) {
            shelterService.addRoom(shelterId, new CreateRoomRequest(room[0], room[1], room[2] == 1));
        }
        return shelterId;
    }

    private String household(int size, boolean needsAccessibility) {
        String number = "H-" + UUID.randomUUID();
        List<MemberRequest> members = java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> new MemberRequest("ID-" + UUID.randomUUID(), 30 + i, needsAccessibility && i == 0))
                .toList();
        householdService.register(new RegisterHouseholdRequest(number, members));
        return number;
    }

    private String checkInBody(String key, String householdNumber, Long shelterId) {
        return "{\"idempotencyKey\":\"" + key + "\",\"householdNumber\":\"" + householdNumber
                + "\",\"shelterId\":" + shelterId + "}";
    }

    @Test
    void assignsWholeHouseholdToRoomWithFewestRemainingBeds() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 4, 0}, {102, 2, 0}});
        String householdNumber = household(2, false);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-1", householdNumber, shelterId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomNumber").value(102))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/shelters/{id}/rooms", shelterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomNumber == 102)].occupiedBeds").value(2))
                .andExpect(jsonPath("$[?(@.roomNumber == 101)].occupiedBeds").value(0));

        mockMvc.perform(get("/api/households/{num}/stay", householdNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomNumber").value(102));
    }

    @Test
    void selectsSmallestRoomNumberWhenRemainingBedsTie() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{202, 3, 0}, {201, 3, 0}});
        String householdNumber = household(2, false);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-2", householdNumber, shelterId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomNumber").value(201));
    }

    @Test
    void requiresAccessibleRoomWhenAnyMemberNeedsIt() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 2, 0}, {102, 6, 1}});
        String householdNumber = household(2, true);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-3", householdNumber, shelterId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomNumber").value(102));
    }

    @Test
    void rejectsWhenNoAccessibleRoomAvailable() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 8, 0}});
        String householdNumber = household(2, true);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-4", householdNumber, shelterId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_ROOM"));
    }

    @Test
    void rejectsWhenCapacityInsufficientWithoutPartialOccupancy() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 2, 0}});
        String householdNumber = household(3, false);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-5", householdNumber, shelterId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_ROOM"));

        mockMvc.perform(get("/api/shelters/{id}/rooms", shelterId))
                .andExpect(jsonPath("$[0].occupiedBeds").value(0));
    }

    @Test
    void rejectsSecondCheckInForSameHousehold() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 4, 0}});
        String householdNumber = household(2, false);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-6a", householdNumber, shelterId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-6b", householdNumber, shelterId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSEHOLD_ALREADY_CHECKED_IN"));
    }

    @Test
    void idempotentReplayReturnsOriginalResultWithoutDoubleOccupancy() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 4, 0}});
        String householdNumber = household(2, false);
        String body = checkInBody("k-7", householdNumber, shelterId);

        String first = mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer stayId = com.jayway.jsonpath.JsonPath.read(first, "$.stayId");

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stayId").value(stayId));

        mockMvc.perform(get("/api/shelters/{id}/rooms", shelterId))
                .andExpect(jsonPath("$[0].occupiedBeds").value(2));
    }

    @Test
    void sameIdempotencyKeyWithDifferentContentReturnsConflict() throws Exception {
        Long shelterId = shelterWithRooms(new int[][]{{101, 4, 0}});
        String first = household(2, false);
        String second = household(2, false);

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-8", first, shelterId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("k-8", second, shelterId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void validationFailureReturnsUnifiedErrorJson() throws Exception {
        mockMvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"householdNumber\":\"H-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
