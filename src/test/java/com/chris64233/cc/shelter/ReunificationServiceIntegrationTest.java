package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.HouseholdType;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.domain.VerificationStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.IdentityVerificationRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.service.CheckInService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.service.ReunificationService;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.CheckOutRequest;
import com.chris64233.cc.shelter.web.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.web.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.MemberDto;
import com.chris64233.cc.shelter.web.dto.MergeRequest;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReunificationServiceIntegrationTest {

    @Autowired
    ReunificationService reunificationService;
    @Autowired
    CheckInService checkInService;
    @Autowired
    RegistrationService registrationService;
    @Autowired
    HouseholdRepository households;
    @Autowired
    RoomRepository rooms;
    @Autowired
    StayRepository stays;
    @Autowired
    StayEventRepository events;
    @Autowired
    IdentityVerificationRepository verifications;

    private Shelter newShelter() {
        return registrationService.createShelter(new CreateShelterRequest("shelter-" + UUID.randomUUID()));
    }

    private Room newRoom(Shelter shelter, int number, int beds, boolean accessible) {
        return registrationService.addRoom(shelter.getId(), new CreateRoomRequest(number, beds, accessible));
    }

    private Household newHousehold(String no, int size, boolean needsAccessible) {
        List<MemberDto> memberDtos = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            memberDtos.add(new MemberDto(no + "-id-" + i, 20 + i, needsAccessible && i == 0));
        }
        return registrationService.registerHousehold(new RegisterHouseholdRequest(no, memberDtos));
    }

    private String key() {
        return "idem-" + UUID.randomUUID();
    }

    private String tempCheckIn(String identityNo, String declaredHouseholdNo, Long shelterId) {
        IdempotentResponse response = reunificationService.temporaryCheckIn(new TemporaryCheckInRequest(
                key(), identityNo, 30, false, declaredHouseholdNo, shelterId));
        assertTrue(response.body().contains("\"householdNo\":\"TMP-" + identityNo + "\""));
        return response.body();
    }

    private void verify(String identityNo) {
        reunificationService.verifyIdentity(new VerifyIdentityRequest(key(), identityNo));
    }

    private ApiException assertApiConflict(Runnable action, String code) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(code, ex.getCode());
        return ex;
    }

    private int occupied(Room room) {
        return rooms.findById(room.getId()).orElseThrow().getOccupied();
    }

    @Test
    void temporaryCheckInRecordsDeclaredHouseholdAndUnverifiedStatus() {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 2, false);

        tempCheckIn("lost-id-1", "h-origin-1", shelter.getId());

        Household temporary = households.findWithMembersByHouseholdNo("TMP-lost-id-1").orElseThrow();
        assertEquals(HouseholdType.TEMPORARY, temporary.getType());
        assertEquals(1, temporary.getMembers().size());
        var member = temporary.getMembers().get(0);
        assertEquals(VerificationStatus.UNVERIFIED, member.getVerificationStatus());
        assertEquals("h-origin-1", member.getDeclaredHouseholdNo());
        assertEquals(1, occupied(room));
        assertTrue(stays.findByHouseholdIdAndStatus(temporary.getId(), StayStatus.ACTIVE).isPresent());
    }

    @Test
    void temporaryCheckInIsIdempotentAndRejectsDuplicateIdentity() {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 2, false);
        String idemKey = key();

        IdempotentResponse first = reunificationService.temporaryCheckIn(new TemporaryCheckInRequest(
                idemKey, "lost-id-2", 25, false, "h-origin-2", shelter.getId()));
        IdempotentResponse replay = reunificationService.temporaryCheckIn(new TemporaryCheckInRequest(
                idemKey, "lost-id-2", 25, false, "h-origin-2", shelter.getId()));
        assertEquals(first.body(), replay.body());
        assertEquals(1, occupied(room));

        // 相同幂等键携带不同内容
        assertApiConflict(() -> reunificationService.temporaryCheckIn(new TemporaryCheckInRequest(
                idemKey, "lost-id-2", 25, false, "h-other", shelter.getId())), "IDEMPOTENCY_CONFLICT");
        // 相同身份标识换个幂等键
        assertApiConflict(() -> reunificationService.temporaryCheckIn(new TemporaryCheckInRequest(
                key(), "lost-id-2", 25, false, "h-origin-2", shelter.getId())), "DUPLICATE_MEMBER");
        assertEquals(1, occupied(room));
    }

    @Test
    void unverifiedMemberCannotBeMerged() {
        Shelter shelter = newShelter();
        Room familyRoom = newRoom(shelter, 1, 4, false);
        Room tempRoom = newRoom(shelter, 2, 1, false);
        Household family = newHousehold("h-mv-1", 2, false);
        checkInService.checkIn(new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        tempCheckIn("lost-mv-1", family.getHouseholdNo(), shelter.getId());

        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-lost-mv-1"), null)), "UNVERIFIED_MEMBER");

        // 原入住完全保持不变
        assertEquals(2, occupied(familyRoom));
        assertEquals(1, occupied(tempRoom));
        assertEquals(2, stays.findByHouseholdIdAndStatus(family.getId(), StayStatus.ACTIVE)
                .orElseThrow().getMemberCount());
        Household temporary = households.findWithMembersByHouseholdNo("TMP-lost-mv-1").orElseThrow();
        assertEquals(1, temporary.getMembers().size());
        assertTrue(stays.findByHouseholdIdAndStatus(temporary.getId(), StayStatus.ACTIVE).isPresent());
        assertEquals(0, events.findByHouseholdNoOrderByIdAsc(family.getHouseholdNo()).stream()
                .filter(e -> e.getType().name().equals("MERGE")).count());
    }

    @Test
    void verifiedMemberMergesIntoExistingRoomInPlace() {
        Shelter shelter = newShelter();
        Room familyRoom = newRoom(shelter, 1, 4, false);
        Room tempRoom = newRoom(shelter, 2, 1, false);
        Household family = newHousehold("h-mg-1", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        Long stayId = stayIdOf(checkedIn);
        tempCheckIn("lost-mg-1", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-1");

        IdempotentResponse merged = reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-lost-mg-1"), null));

        assertTrue(merged.body().contains("\"memberCount\":3"));
        assertTrue(merged.body().contains("\"roomChanged\":false"));
        // 原地合并：入住 ID 不变，房间占用 2 -> 3
        assertTrue(merged.body().contains("\"stayId\":" + stayId));
        assertEquals(3, occupied(familyRoom));
        assertEquals(0, occupied(tempRoom));

        // 临时入住记录保留为已结束，成员关系迁入正式家庭
        Household temporary = households.findWithMembersByHouseholdNo("TMP-lost-mg-1").orElseThrow();
        assertEquals(0, temporary.getMembers().size());
        assertEquals(StayStatus.ENDED, stays.findByHouseholdIdAndStatus(temporary.getId(), StayStatus.ENDED)
                .orElseThrow().getStatus());
        Household mergedFamily = households.findWithMembersByHouseholdNo(family.getHouseholdNo()).orElseThrow();
        assertEquals(3, mergedFamily.getMembers().size());
        var moved = mergedFamily.getMembers().stream()
                .filter(m -> m.getIdentityNo().equals("lost-mg-1"))
                .findFirst().orElseThrow();
        assertEquals(VerificationStatus.VERIFIED, moved.getVerificationStatus());
        assertEquals(family.getHouseholdNo(), moved.getDeclaredHouseholdNo());

        // 迁移链：临时家庭 CHECK_IN + MERGE，正式家庭 CHECK_IN + MERGE
        var tempEvents = events.findByHouseholdNoOrderByIdAsc("TMP-lost-mg-1");
        assertEquals(List.of("CHECK_IN", "MERGE"), tempEvents.stream().map(e -> e.getType().name()).toList());
        assertEquals(tempRoom.getId(), tempEvents.get(1).getFromRoomId());
        assertEquals(familyRoom.getId(), tempEvents.get(1).getToRoomId());
        var familyEvents = events.findByHouseholdNoOrderByIdAsc(family.getHouseholdNo());
        assertEquals(List.of("CHECK_IN", "MERGE"), familyEvents.stream().map(e -> e.getType().name()).toList());
        assertEquals(3, familyEvents.get(1).getMemberCount());

        // 身份核验事件可查
        assertEquals(1, verifications.findByIdentityNoOrderByIdAsc("lost-mg-1").size());
    }

    @Test
    void mergeReRoomsWholeFamilyWhenOriginalRoomTooSmall() {
        Shelter shelter = newShelter();
        Room smallRoom = newRoom(shelter, 1, 2, false);
        Room bigRoom = newRoom(shelter, 2, 5, false);
        Household family = newHousehold("h-mg-2", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        Long originalStayId = stayIdOf(checkedIn);
        tempCheckIn("lost-mg-2", family.getHouseholdNo(), shelter.getId());
        tempCheckIn("lost-mg-3", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-2");
        verify("lost-mg-3");

        IdempotentResponse merged = reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-lost-mg-2", "TMP-lost-mg-3"), null));

        assertTrue(merged.body().contains("\"roomChanged\":true"));
        assertTrue(merged.body().contains("\"memberCount\":4"));
        assertTrue(merged.body().contains("\"roomId\":" + bigRoom.getId()));
        assertEquals(0, occupied(smallRoom));
        assertEquals(4, occupied(bigRoom));
        assertEquals(StayStatus.ENDED, stays.findById(originalStayId).orElseThrow().getStatus());
        Long newStayId = stays.findByHouseholdIdAndStatus(family.getId(), StayStatus.ACTIVE)
                .orElseThrow().getId();
        assertNotEquals(originalStayId, newStayId);
        // 两个临时家庭 + 正式家庭各一条 MERGE
        assertEquals(2, events.findByHouseholdNoOrderByIdAsc(family.getHouseholdNo()).size());
        assertEquals(2, events.findByHouseholdNoOrderByIdAsc("TMP-lost-mg-2").size());
        assertEquals(2, events.findByHouseholdNoOrderByIdAsc("TMP-lost-mg-3").size());
    }

    @Test
    void mergeWithExplicitShelterRelocatesWholeFamily() {
        Shelter origin = newShelter();
        Room originRoom = newRoom(origin, 1, 4, false);
        Shelter target = newShelter();
        Room targetRoom = newRoom(target, 1, 4, false);
        Household family = newHousehold("h-mg-3", 2, false);
        checkInService.checkIn(new CheckInRequest(key(), family.getHouseholdNo(), origin.getId()));
        tempCheckIn("lost-mg-4", family.getHouseholdNo(), origin.getId());
        verify("lost-mg-4");

        IdempotentResponse merged = reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-lost-mg-4"), target.getId()));

        assertTrue(merged.body().contains("\"roomChanged\":true"));
        assertTrue(merged.body().contains("\"shelterId\":" + target.getId()));
        assertEquals(0, occupied(originRoom));
        assertEquals(3, occupied(targetRoom));
    }

    @Test
    void failedMergeKeepsAllStaysAndBedsUnchanged() {
        Shelter shelter = newShelter();
        Room familyRoom = newRoom(shelter, 1, 2, false);
        Room tempRoom = newRoom(shelter, 2, 1, false);
        Household family = newHousehold("h-mg-fail", 2, false);
        checkInService.checkIn(new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        tempCheckIn("lost-mg-fail", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-fail");

        // 原房间住不下 3 人，且没有其他房间
        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-lost-mg-fail"), null)), "NO_SUITABLE_ROOM");

        assertEquals(2, occupied(familyRoom));
        assertEquals(1, occupied(tempRoom));
        assertEquals(StayStatus.ACTIVE, stays.findByHouseholdIdAndStatus(family.getId(), StayStatus.ACTIVE)
                .orElseThrow().getStatus());
        Household temporary = households.findWithMembersByHouseholdNo("TMP-lost-mg-fail").orElseThrow();
        assertEquals(1, temporary.getMembers().size());
        assertTrue(stays.findByHouseholdIdAndStatus(temporary.getId(), StayStatus.ACTIVE).isPresent());
        assertEquals(1, events.findByHouseholdNoOrderByIdAsc(family.getHouseholdNo()).size());
        assertEquals(1, events.findByHouseholdNoOrderByIdAsc("TMP-lost-mg-fail").size());
    }

    @Test
    void mergeBusinessKeyIsIdempotent() {
        Shelter shelter = newShelter();
        Room familyRoom = newRoom(shelter, 1, 4, false);
        newRoom(shelter, 2, 1, false);
        Household family = newHousehold("h-mg-idem", 2, false);
        checkInService.checkIn(new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        tempCheckIn("lost-mg-idem", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-idem");
        String mergeKey = key();

        IdempotentResponse first = reunificationService.merge(new MergeRequest(
                mergeKey, family.getHouseholdNo(), List.of("TMP-lost-mg-idem"), null));
        IdempotentResponse replay = reunificationService.merge(new MergeRequest(
                mergeKey, family.getHouseholdNo(), List.of("TMP-lost-mg-idem"), null));

        assertEquals(first.body(), replay.body());
        // 重放不会重复迁移成员或重复释放床位
        assertEquals(3, occupied(familyRoom));
        assertEquals(3, households.findWithMembersByHouseholdNo(family.getHouseholdNo()).orElseThrow().getMembers().size());
        assertEquals(2, events.findByHouseholdNoOrderByIdAsc(family.getHouseholdNo()).size());

        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                mergeKey, family.getHouseholdNo(), List.of("TMP-lost-mg-idem", "TMP-other"), null)),
                "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void mergeValidatesRequestShape() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 4, false);
        Household family = newHousehold("h-mg-bad", 2, false);
        checkInService.checkIn(new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        tempCheckIn("lost-mg-bad", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-bad");

        // 临时家庭重复
        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-lost-mg-bad", "TMP-lost-mg-bad"), null)),
                "DUPLICATE_TEMPORARY");
        // 目标不是正式家庭
        tempCheckIn("lost-mg-bad-2", family.getHouseholdNo(), shelter.getId());
        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                key(), "TMP-lost-mg-bad", List.of("TMP-lost-mg-bad-2"), null)), "INVALID_TARGET");
        // 被并入方不是临时家庭
        Household other = newHousehold("h-mg-bad-2", 1, false);
        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of(other.getHouseholdNo()), null)), "NOT_TEMPORARY");
        // 临时家庭不存在
        assertApiConflict(() -> reunificationService.merge(new MergeRequest(
                key(), family.getHouseholdNo(), List.of("TMP-missing"), null)), "HOUSEHOLD_NOT_FOUND");
    }

    @Test
    void verificationEventIsIdempotent() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 2, false);
        tempCheckIn("lost-vf-1", "h-vf-1", shelter.getId());
        String verifyKey = key();

        IdempotentResponse first = reunificationService.verifyIdentity(
                new VerifyIdentityRequest(verifyKey, "lost-vf-1"));
        IdempotentResponse replay = reunificationService.verifyIdentity(
                new VerifyIdentityRequest(verifyKey, "lost-vf-1"));

        assertEquals(first.body(), replay.body());
        assertEquals(1, verifications.findByIdentityNoOrderByIdAsc("lost-vf-1").size());

        assertApiConflict(() -> reunificationService.verifyIdentity(
                new VerifyIdentityRequest(verifyKey, "lost-vf-other")), "IDEMPOTENCY_CONFLICT");

        ApiException notFound = assertThrows(ApiException.class, () ->
                reunificationService.verifyIdentity(new VerifyIdentityRequest(key(), "missing-id")));
        assertEquals("MEMBER_NOT_FOUND", notFound.getCode());
    }

    @Test
    void checkOutReleasesBedsAndIsIdempotent() {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 4, false);
        Household family = newHousehold("h-co-1", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        Long stayId = stayIdOf(checkedIn);
        String checkOutKey = key();

        IdempotentResponse first = checkInService.checkOut(
                new CheckOutRequest(checkOutKey, family.getHouseholdNo(), stayId));
        assertTrue(first.body().contains("\"status\":\"ENDED\""));
        assertEquals(0, occupied(room));

        IdempotentResponse replay = checkInService.checkOut(
                new CheckOutRequest(checkOutKey, family.getHouseholdNo(), stayId));
        assertEquals(first.body(), replay.body());
        // 重放不会重复释放床位
        assertEquals(0, occupied(room));

        var familyEvents = events.findByHouseholdNoOrderByIdAsc(family.getHouseholdNo());
        assertEquals(List.of("CHECK_IN", "CHECK_OUT"), familyEvents.stream().map(e -> e.getType().name()).toList());
        assertEquals(room.getId(), familyEvents.get(1).getFromRoomId());

        // 退住后再次退住 / 陈旧入住 ID 都被拒绝
        assertApiConflict(() -> checkInService.checkOut(
                new CheckOutRequest(key(), family.getHouseholdNo(), stayId)), "NO_ACTIVE_STAY");
    }

    @Test
    void concurrentMergesHaveSingleWinner() throws Exception {
        Shelter shelter = newShelter();
        Room familyRoom = newRoom(shelter, 1, 4, false);
        Room tempRoom = newRoom(shelter, 2, 1, false);
        Household family = newHousehold("h-mg-race", 2, false);
        checkInService.checkIn(new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        tempCheckIn("lost-mg-race", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-race");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            ready.countDown();
            go.await();
            try {
                reunificationService.merge(new MergeRequest(
                        key(), family.getHouseholdNo(), List.of("TMP-lost-mg-race"), null));
                return true;
            } catch (ApiException e) {
                assertEquals("NO_ACTIVE_STAY", e.getCode());
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
        // 没有重复成员、没有双重入住、没有错误释放床位
        assertEquals(3, occupied(familyRoom));
        assertEquals(0, occupied(tempRoom));
        assertEquals(3, households.findWithMembersByHouseholdNo(family.getHouseholdNo()).orElseThrow().getMembers().size());
        assertEquals(1, events.findByHouseholdNoOrderByIdAsc("TMP-lost-mg-race").stream()
                .filter(e -> e.getType().name().equals("MERGE")).count());
    }

    @Test
    void concurrentMergeAndCheckOutHaveSingleWinner() throws Exception {
        Shelter shelter = newShelter();
        Room smallRoom = newRoom(shelter, 1, 2, false);
        Room bigRoom = newRoom(shelter, 2, 5, false);
        Household family = newHousehold("h-mg-co", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), family.getHouseholdNo(), shelter.getId()));
        Long stayId = stayIdOf(checkedIn);
        tempCheckIn("lost-mg-co", family.getHouseholdNo(), shelter.getId());
        verify("lost-mg-co");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> mergeTask = () -> {
            ready.countDown();
            go.await();
            try {
                reunificationService.merge(new MergeRequest(
                        key(), family.getHouseholdNo(), List.of("TMP-lost-mg-co"), null));
                return true;
            } catch (ApiException e) {
                assertEquals("NO_ACTIVE_STAY", e.getCode());
                return false;
            }
        };
        Callable<Boolean> checkOutTask = () -> {
            ready.countDown();
            go.await();
            try {
                checkInService.checkOut(new CheckOutRequest(key(), family.getHouseholdNo(), stayId));
                return true;
            } catch (ApiException e) {
                assertEquals("STALE_STATE", e.getCode());
                return false;
            }
        };
        Future<Boolean> mergeResult = pool.submit(mergeTask);
        Future<Boolean> checkOutResult = pool.submit(checkOutTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        boolean mergeWon = mergeResult.get(30, TimeUnit.SECONDS);
        boolean checkOutWon = checkOutResult.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertNotEquals(mergeWon, checkOutWon);
        if (mergeWon) {
            // 合并成功：全家迁入大房间，退住因入住状态变化被拒绝
            assertEquals(3, occupied(bigRoom));
            assertEquals(0, occupied(smallRoom));
            assertEquals(3, stays.findByHouseholdIdAndStatus(family.getId(), StayStatus.ACTIVE)
                    .orElseThrow().getMemberCount());
        } else {
            // 退住成功：合并因没有有效入住被拒绝，临时入住保持不变（仍占大房间 1 床）
            assertEquals(0, occupied(smallRoom));
            assertEquals(1, occupied(bigRoom));
            assertTrue(stays.findByHouseholdIdAndStatus(family.getId(), StayStatus.ACTIVE).isEmpty());
            Household temporary = households.findWithMembersByHouseholdNo("TMP-lost-mg-co").orElseThrow();
            assertEquals(1, temporary.getMembers().size());
        }
    }

    @Test
    void concurrentMergeAndTransferNeverCorruptBeds() throws Exception {
        Shelter origin = newShelter();
        Room smallRoom = newRoom(origin, 1, 2, false);
        Room bigRoom = newRoom(origin, 2, 5, false);
        Shelter elsewhere = newShelter();
        Room elsewhereRoom = newRoom(elsewhere, 1, 5, false);
        Household family = newHousehold("h-mg-tr", 2, false);
        IdempotentResponse checkedIn = checkInService.checkIn(
                new CheckInRequest(key(), family.getHouseholdNo(), origin.getId()));
        Long stayId = stayIdOf(checkedIn);
        tempCheckIn("lost-mg-tr", family.getHouseholdNo(), origin.getId());
        verify("lost-mg-tr");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> mergeTask = () -> {
            ready.countDown();
            go.await();
            reunificationService.merge(new MergeRequest(
                    key(), family.getHouseholdNo(), List.of("TMP-lost-mg-tr"), null));
            return true;
        };
        Callable<Boolean> transferTask = () -> {
            ready.countDown();
            go.await();
            try {
                checkInService.transfer(new TransferRequest(key(), family.getHouseholdNo(),
                        elsewhere.getId(), stayId));
                return true;
            } catch (ApiException e) {
                assertTrue(List.of("STALE_STATE", "NO_ACTIVE_STAY").contains(e.getCode()));
                return false;
            }
        };
        Future<Boolean> mergeResult = pool.submit(mergeTask);
        Future<Boolean> transferResult = pool.submit(transferTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        mergeResult.get(30, TimeUnit.SECONDS);
        transferResult.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 不变量：合并一定成功（成员迁入后家庭共 3 人），转移可能因状态变化失败；
        // 任一结果下都不能出现重复成员、双重入住或负占用
        var activeStay = stays.findByHouseholdIdAndStatus(family.getId(), StayStatus.ACTIVE).orElseThrow();
        assertEquals(3, activeStay.getMemberCount());
        assertEquals(3, households.findWithMembersByHouseholdNo(family.getHouseholdNo()).orElseThrow().getMembers().size());
        Household temporary = households.findWithMembersByHouseholdNo("TMP-lost-mg-tr").orElseThrow();
        assertEquals(0, temporary.getMembers().size());
        assertTrue(occupied(smallRoom) >= 0);
        assertTrue(occupied(bigRoom) >= 0);
        assertTrue(occupied(elsewhereRoom) >= 0);
        // 全部房间床位占用之和等于在住人数（临时房间已释放为 0）
        assertEquals(3, occupied(smallRoom) + occupied(bigRoom) + occupied(elsewhereRoom));
    }

    private Long stayIdOf(IdempotentResponse response) {
        String body = response.body();
        int start = body.indexOf("\"stayId\":") + "\"stayId\":".length();
        int end = body.indexOf(',', start);
        return Long.parseLong(body.substring(start, end));
    }
}
