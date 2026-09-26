package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.service.CheckInService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.web.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.MemberDto;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CheckInServiceIntegrationTest {

    @Autowired
    CheckInService checkInService;
    @Autowired
    RegistrationService registrationService;
    @Autowired
    RoomRepository rooms;
    @Autowired
    StayRepository stays;
    @Autowired
    StayEventRepository events;

    private Shelter newShelter() {
        return registrationService.createShelter(new CreateShelterRequest("shelter-" + UUID.randomUUID()));
    }

    private Room newRoom(Shelter shelter, int number, int beds, boolean accessible) {
        return registrationService.addRoom(shelter.getId(), new CreateRoomRequest(number, beds, accessible));
    }

    private Household newHousehold(String no, int size, boolean needsAccessible) {
        List<MemberDto> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(new MemberDto(no + "-id-" + i, 20 + i, needsAccessible && i == 0));
        }
        return registrationService.registerHousehold(new RegisterHouseholdRequest(no, members));
    }

    private String key() {
        return "idem-" + UUID.randomUUID();
    }

    private ApiException assertApiConflict(Runnable action, String code) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(code, ex.getCode());
        return ex;
    }

    @Test
    void checkInPicksLeastRemainingBedsThenSmallestRoomNumber() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 4, false);
        newRoom(shelter, 2, 2, false);
        newRoom(shelter, 3, 2, false);

        Household first = newHousehold("h-bestfit-1", 2, false);
        IdempotentResponse firstResult = checkInService.checkIn(
                new CheckInRequest(key(), first.getHouseholdNo(), shelter.getId()));
        assertTrue(firstResult.body().contains("\"roomNumber\":2"));

        Household second = newHousehold("h-bestfit-2", 1, false);
        IdempotentResponse secondResult = checkInService.checkIn(
                new CheckInRequest(key(), second.getHouseholdNo(), shelter.getId()));
        // 房间2已满，剩余床位最少的是房间3
        assertTrue(secondResult.body().contains("\"roomNumber\":3"));
    }

    @Test
    void accessibleFamilyOnlyFitsAccessibleRoom() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 2, false);
        Room accessibleRoom = newRoom(shelter, 2, 4, true);

        Household household = newHousehold("h-acc-1", 1, true);
        IdempotentResponse result = checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), shelter.getId()));
        assertTrue(result.body().contains("\"roomNumber\":2"));
        assertEquals(1, rooms.findById(accessibleRoom.getId()).orElseThrow().getOccupied());
    }

    @Test
    void insufficientCapacityRejectsWholeFamilyWithoutPartialOccupancy() {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 2, false);
        Household household = newHousehold("h-big-1", 3, false);

        assertApiConflict(() -> checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), shelter.getId())), "NO_SUITABLE_ROOM");

        assertEquals(0, rooms.findById(room.getId()).orElseThrow().getOccupied());
        assertTrue(stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE).isEmpty());
    }

    @Test
    void duplicateActiveStayIsRejected() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 4, false);
        Household household = newHousehold("h-dup-1", 2, false);

        checkInService.checkIn(new CheckInRequest(key(), household.getHouseholdNo(), shelter.getId()));

        assertApiConflict(() -> checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), shelter.getId())), "ALREADY_ACCOMMODATED");
    }

    @Test
    void idempotentReplayReturnsOriginalAndDifferentContentConflicts() {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 4, false);
        Household household = newHousehold("h-idem-1", 2, false);
        String idemKey = key();

        IdempotentResponse first = checkInService.checkIn(
                new CheckInRequest(idemKey, household.getHouseholdNo(), shelter.getId()));
        IdempotentResponse replay = checkInService.checkIn(
                new CheckInRequest(idemKey, household.getHouseholdNo(), shelter.getId()));

        assertEquals(first.body(), replay.body());
        assertEquals(2, rooms.findById(room.getId()).orElseThrow().getOccupied());

        Shelter other = newShelter();
        assertApiConflict(() -> checkInService.checkIn(
                new CheckInRequest(idemKey, household.getHouseholdNo(), other.getId())), "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void concurrentCheckInsNeverOversellRoom() throws Exception {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 2, false);
        int contenders = 5;
        List<Household> households = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            households.add(newHousehold("h-race-" + i, 1, false));
        }

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<IdempotentResponse>> futures = new ArrayList<>();
        for (Household household : households) {
            Callable<IdempotentResponse> task = () -> {
                ready.countDown();
                go.await();
                try {
                    return checkInService.checkIn(
                            new CheckInRequest(key(), household.getHouseholdNo(), shelter.getId()));
                } catch (ApiException e) {
                    failures.add(e);
                    return null;
                }
            };
            futures.add(pool.submit(task));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();

        int successes = 0;
        for (Future<IdempotentResponse> future : futures) {
            if (future.get(30, TimeUnit.SECONDS) != null) {
                successes++;
            }
        }
        pool.shutdownNow();

        assertEquals(2, successes);
        assertEquals(contenders - 2, failures.size());
        failures.forEach(t -> assertEquals("NO_SUITABLE_ROOM", ((ApiException) t).getCode()));
        assertEquals(2, rooms.findById(room.getId()).orElseThrow().getOccupied());
    }

    @Test
    void transferMovesWholeFamilyAndRecordsImmutableEvents() {
        Shelter origin = newShelter();
        Room originRoom = newRoom(origin, 1, 2, false);
        Shelter target = newShelter();
        newRoom(target, 1, 4, false);
        Room targetRoom = newRoom(target, 2, 2, false);

        Household household = newHousehold("h-tr-1", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), origin.getId()));
        Long stayId = stayIdOf(checkedIn);

        IdempotentResponse transferred = checkInService.transfer(
                new TransferRequest(key(), household.getHouseholdNo(), target.getId(), stayId));

        assertTrue(transferred.body().contains("\"roomNumber\":2"));
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(StayStatus.ENDED, stays.findById(stayId).orElseThrow().getStatus());

        var householdEvents = events.findByHouseholdNoOrderByIdAsc(household.getHouseholdNo());
        assertEquals(2, householdEvents.size());
        assertEquals("CHECK_IN", householdEvents.get(0).getType().name());
        var transfer = householdEvents.get(1);
        assertEquals("TRANSFER", transfer.getType().name());
        assertEquals(origin.getId(), transfer.getFromShelterId());
        assertEquals(originRoom.getId(), transfer.getFromRoomId());
        assertEquals(target.getId(), transfer.getToShelterId());
        assertEquals(targetRoom.getId(), transfer.getToRoomId());
    }

    @Test
    void failedTransferKeepsOriginalStayIntact() {
        Shelter origin = newShelter();
        Room originRoom = newRoom(origin, 1, 3, false);
        Shelter target = newShelter();
        Room smallRoom = newRoom(target, 1, 1, false);

        Household household = newHousehold("h-tr-fail", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), origin.getId()));
        Long stayId = stayIdOf(checkedIn);

        assertApiConflict(() -> checkInService.transfer(
                new TransferRequest(key(), household.getHouseholdNo(), target.getId(), stayId)),
                "NO_SUITABLE_ROOM");

        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(smallRoom.getId()).orElseThrow().getOccupied());
        assertEquals(StayStatus.ACTIVE, stays.findById(stayId).orElseThrow().getStatus());
        assertEquals(1, events.findByHouseholdNoOrderByIdAsc(household.getHouseholdNo()).size());
    }

    @Test
    void staleStayIdIsRejected() {
        Shelter origin = newShelter();
        newRoom(origin, 1, 2, false);
        Shelter target = newShelter();
        newRoom(target, 1, 2, false);

        Household household = newHousehold("h-tr-stale", 1, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), origin.getId()));
        Long stayId = stayIdOf(checkedIn);

        assertApiConflict(() -> checkInService.transfer(
                new TransferRequest(key(), household.getHouseholdNo(), target.getId(), stayId + 999)),
                "STALE_STATE");
    }

    @Test
    void duplicateTransferReplayDoesNotReleaseOriginTwice() {
        Shelter origin = newShelter();
        Room originRoom = newRoom(origin, 1, 2, false);
        Shelter target = newShelter();
        Room targetRoom = newRoom(target, 1, 2, false);

        Household household = newHousehold("h-tr-idem", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), origin.getId()));
        Long stayId = stayIdOf(checkedIn);
        String transferKey = key();

        IdempotentResponse first = checkInService.transfer(
                new TransferRequest(transferKey, household.getHouseholdNo(), target.getId(), stayId));
        IdempotentResponse replay = checkInService.transfer(
                new TransferRequest(transferKey, household.getHouseholdNo(), target.getId(), stayId));

        assertEquals(first.body(), replay.body());
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, events.findByHouseholdNoOrderByIdAsc(household.getHouseholdNo()).size());
    }

    @Test
    void concurrentTransfersHaveSingleWinner() throws Exception {
        Shelter origin = newShelter();
        Room originRoom = newRoom(origin, 1, 2, false);
        Shelter target = newShelter();
        Room targetRoom = newRoom(target, 1, 2, false);

        Household household = newHousehold("h-tr-race", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), household.getHouseholdNo(), origin.getId()));
        Long stayId = stayIdOf(checkedIn);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            ready.countDown();
            go.await();
            try {
                checkInService.transfer(
                        new TransferRequest(key(), household.getHouseholdNo(), target.getId(), stayId));
                return true;
            } catch (ApiException e) {
                assertTrue(List.of("STALE_STATE", "NO_ACTIVE_STAY").contains(e.getCode()));
                return false;
            }
        };
        Future<Boolean> first = pool.submit(task);
        Future<Boolean> second = pool.submit(task);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        int wins = (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
        pool.shutdownNow();

        assertEquals(1, wins);
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(1, events.findByHouseholdNoOrderByIdAsc(household.getHouseholdNo()).stream()
                .filter(e -> e.getType().name().equals("TRANSFER")).count());
        Long activeStayId = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow().getId();
        assertNotEquals(stayId, activeStayId);
    }

    private Long stayIdOf(IdempotentResponse response) {
        String body = response.body();
        int start = body.indexOf("\"stayId\":") + "\"stayId\":".length();
        int end = body.indexOf(',', start);
        return Long.parseLong(body.substring(start, end));
    }
}
