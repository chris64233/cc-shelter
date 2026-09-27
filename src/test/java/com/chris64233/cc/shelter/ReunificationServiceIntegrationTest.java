package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.HouseholdType;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.MemberEvent;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.domain.VerificationStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.service.CheckInService;
import com.chris64233.cc.shelter.service.MergeService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.service.TemporaryStayService;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.CheckoutRequest;
import com.chris64233.cc.shelter.web.dto.ConfirmMergeRequest;
import com.chris64233.cc.shelter.web.dto.CreateMergeRequest;
import com.chris64233.cc.shelter.web.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.web.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.MemberDto;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.RegisterTemporaryHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class ReunificationServiceIntegrationTest {

    @Autowired RegistrationService registrationService;
    @Autowired CheckInService checkInService;
    @Autowired TemporaryStayService temporaryStayService;
    @Autowired MergeService mergeService;
    @Autowired HouseholdRepository households;
    @Autowired MemberRepository members;
    @Autowired RoomRepository rooms;
    @Autowired StayRepository stays;
    @Autowired StayEventRepository stayEvents;
    @Autowired MemberEventRepository memberEvents;
    @Autowired ObjectMapper objectMapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private String key() {
        return "idem-" + UUID.randomUUID();
    }

    private String mergeNo() {
        return "merge-" + UUID.randomUUID();
    }

    private Shelter newShelter() {
        return registrationService.createShelter(new CreateShelterRequest("shelter-" + UUID.randomUUID()));
    }

    private Room newRoom(Shelter shelter, int number, int beds, boolean accessible) {
        return registrationService.addRoom(shelter.getId(), new CreateRoomRequest(number, beds, accessible));
    }

    private Household formal(String no, String... identities) {
        List<MemberDto> dtos = new ArrayList<>();
        for (int i = 0; i < identities.length; i++) {
            dtos.add(new MemberDto(identities[i], 30 + i, false));
        }
        return registrationService.registerHousehold(new RegisterHouseholdRequest(no, dtos));
    }

    private Household temporary(String no, String claimedOriginal, String... identities) {
        List<MemberDto> dtos = new ArrayList<>();
        for (int i = 0; i < identities.length; i++) {
            dtos.add(new MemberDto(identities[i], 10 + i, false));
        }
        Household household = registrationService.registerTemporaryHousehold(
                new RegisterTemporaryHouseholdRequest(no, claimedOriginal, dtos));
        assertEquals(HouseholdType.TEMPORARY, household.getType());
        return household;
    }

    private Long checkIn(String householdNo, Long shelterId) {
        return stayId(checkInService.checkIn(new CheckInRequest(key(), householdNo, shelterId)));
    }

    private Long tempCheckIn(String householdNo, Long shelterId) {
        return stayId(temporaryStayService.temporaryCheckIn(
                new TemporaryCheckInRequest(key(), householdNo, shelterId)));
    }

    private void verify(String identityNo) {
        temporaryStayService.verifyIdentity(new VerifyIdentityRequest(key(), identityNo));
    }

    private Long stayId(IdempotentResponse response) {
        return Json.readLong(response.body(), "stayId");
    }

    private void assertConflict(Callable<?> action, String code) {
        ApiException ex = assertThrows(ApiException.class, () -> {
            try {
                action.call();
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(code, ex.getCode());
    }

    /** 全局床位账实一致：所有房间占用数之和 = 所有有效入住人数之和 */
    private void assertOccupancyMatchesStays() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            int occupied = rooms.findAll().stream().mapToInt(Room::getOccupied).sum();
            int active = (int) stays.findAll().stream()
                    .filter(s -> s.getStatus() == StayStatus.ACTIVE)
                    .mapToLong(Stay::getMemberCount).sum();
            assertEquals(active, occupied, "房间占用总数与有效入住总人数不一致");
            assertFalse(rooms.findAll().stream().anyMatch(r -> r.getOccupied() < 0), "出现负数占用");
        });
    }

    @Test
    void temporaryRegistrationRecordsClaimedFamilyAndUnverifiedStatus() {
        String tempNo = unique("t");
        temporary(tempNo, "F-ORIG", "id-temp-1");

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household t = households.findByHouseholdNo(tempNo).orElseThrow();
            assertEquals(HouseholdType.TEMPORARY, t.getType());
            assertEquals("F-ORIG", t.getClaimedOriginalHouseholdNo());
            Member member = t.getMembers().get(0);
            assertEquals(VerificationStatus.UNVERIFIED, member.getVerificationStatus());
        });
    }

    @Test
    void temporaryAndFormalChannelsAreSeparated() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 4, false);
        String formalNo = unique("f");
        String tempNo = unique("t");
        formal(formalNo, "id-f-1");
        temporary(tempNo, formalNo, "id-t-1");

        assertConflict(() -> checkInService.checkIn(new CheckInRequest(key(), tempNo, shelter.getId())),
                "TEMPORARY_HOUSEHOLD_USE_TEMP_CHANNEL");
        assertConflict(() -> temporaryStayService.temporaryCheckIn(
                new TemporaryCheckInRequest(key(), formalNo, shelter.getId())), "NOT_TEMPORARY_HOUSEHOLD");

        tempCheckIn(tempNo, shelter.getId());
        assertConflict(() -> temporaryStayService.temporaryCheckIn(
                new TemporaryCheckInRequest(key(), tempNo, shelter.getId())), "ALREADY_ACCOMMODATED");
    }

    @Test
    void unverifiedTemporaryMemberCannotMergeIntoFormalFamily() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 6, false);
        String formalNo = unique("f");
        String tempNo = unique("t");
        formal(formalNo, "id-fam-1");
        temporary(tempNo, formalNo, "id-lost-1");
        checkIn(formalNo, shelter.getId());
        tempCheckIn(tempNo, shelter.getId());
        // 未核验直接申请合并被拒绝
        assertConflict(() -> mergeService.createApplication(new CreateMergeRequest(
                mergeNo(), formalNo, List.of(tempNo), null)), "IDENTITY_NOT_VERIFIED");
        assertOccupancyMatchesStays();
    }

    @Test
    void identityVerificationIsIdempotent() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 4, false);
        String formalNo = unique("f");
        String tempNo = unique("t");
        temporary(tempNo, formalNo, "id-verify-1");
        tempCheckIn(tempNo, shelter.getId());

        String idemKey = key();
        IdempotentResponse first = temporaryStayService.verifyIdentity(
                new VerifyIdentityRequest(idemKey, "id-verify-1"));
        assertTrue(first.body().contains("VERIFIED"));
        // 相同幂等键重放返回首次结果
        IdempotentResponse replay = temporaryStayService.verifyIdentity(
                new VerifyIdentityRequest(idemKey, "id-verify-1"));
        assertEquals(first.body(), replay.body());
        // 相同幂等键不同内容
        assertConflict(() -> temporaryStayService.verifyIdentity(
                new VerifyIdentityRequest(idemKey, "id-verify-other")), "IDEMPOTENCY_CONFLICT");
        // 已核验再次核验
        assertConflict(() -> temporaryStayService.verifyIdentity(
                new VerifyIdentityRequest(key(), "id-verify-1")), "IDENTITY_ALREADY_VERIFIED");
        // 只追加一条核验事件
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            long count = memberEvents.findByIdentityNoOrderByIdAsc("id-verify-1").stream()
                    .filter(e -> e.getType().name().equals("IDENTITY_VERIFIED")).count();
            assertEquals(1, count);
        });
    }

    @Test
    void mergeApplicationIsIdempotentByMergeNoAndRejectsMismatchedDeclaredFamily() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 6, false);
        String formalNo = unique("f");
        String otherFormal = unique("f2");
        formal(formalNo, "id-m-1");
        formal(otherFormal, "id-m-2");
        String tempNo = unique("t");
        temporary(tempNo, formalNo, "id-m-3");
        checkIn(formalNo, shelter.getId());
        checkIn(otherFormal, shelter.getId());
        tempCheckIn(tempNo, shelter.getId());
        verify("id-m-3");

        // 声明的原家庭与目标不一致
        assertConflict(() -> mergeService.createApplication(new CreateMergeRequest(
                mergeNo(), otherFormal, List.of(tempNo), null)), "DECLARED_FAMILY_MISMATCH");

        String no = mergeNo();
        IdempotentResponse created = mergeService.createApplication(
                new CreateMergeRequest(no, formalNo, List.of(tempNo), null));
        assertTrue(created.body().contains("PENDING"));
        IdempotentResponse replay = mergeService.createApplication(
                new CreateMergeRequest(no, formalNo, List.of(tempNo), null));
        assertEquals(created.body(), replay.body());
        // 相同业务号不同内容
        assertConflict(() -> mergeService.createApplication(
                new CreateMergeRequest(no, formalNo, List.of(tempNo), shelter.getId())),
                "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void confirmedMergeMovesAllMembersAtOnceAndKeepsOriginalStaysAndChain() {
        Shelter shelter = newShelter();
        Room formalRoom = newRoom(shelter, 1, 4, false);
        newRoom(shelter, 2, 2, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        String t2 = unique("t2");
        formal(formalNo, "id-c-1", "id-c-2");
        temporary(t1, formalNo, "id-c-3");
        temporary(t2, formalNo, "id-c-4");
        Long formalStay = checkIn(formalNo, shelter.getId());
        tempCheckIn(t1, shelter.getId());
        tempCheckIn(t2, shelter.getId());
        verify("id-c-3");
        verify("id-c-4");

        String no = mergeNo();
        mergeService.createApplication(
                new CreateMergeRequest(no, formalNo, List.of(t1, t2), null));
        String confirmKey = key();
        IdempotentResponse confirmed = mergeService.confirm(
                new ConfirmMergeRequest(confirmKey, no, formalStay));

        assertEquals(200, confirmed.status());
        assertTrue(confirmed.body().contains("\"memberCount\":4"));
        // 正式家庭房间（4 床）正好容纳合并后的 4 人
        assertEquals(4, rooms.findById(formalRoom.getId()).orElseThrow().getOccupied());

        // 原临时入住记录保留（ENDED，不删除）
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household f = households.findByHouseholdNo(formalNo).orElseThrow();
            assertEquals(4, f.stayingMemberCount());
            assertEquals(4, f.getMembers().size());
            // 新成员均为已核验
            f.getMembers().forEach(m -> {
                if (m.isCurrentlyStaying()) {
                    assertNotEquals(VerificationStatus.UNVERIFIED, m.getVerificationStatus());
                }
            });
            assertEquals(StayStatus.ENDED,
                    stays.findByHouseholdHouseholdNoAndStatus(t1, StayStatus.ACTIVE)
                            .isPresent() ? StayStatus.ACTIVE : StayStatus.ENDED);
            // 临时家庭旧成员行保留并标记离开
            Household temp = households.findByHouseholdNo(t1).orElseThrow();
            assertTrue(temp.getMembers().stream().noneMatch(Member::isCurrentlyStaying));

            // 事件流：正式家庭 CHECK_IN + MERGE；临时家庭 CHECK_IN + TEMP_MERGED
            var fEvents = stayEvents.findByHouseholdNoOrderByIdAsc(formalNo);
            assertEquals(List.of("CHECK_IN", "MERGE"),
                    fEvents.stream().map(e -> e.getType().name()).toList());
            var tEvents = stayEvents.findByHouseholdNoOrderByIdAsc(t1);
            assertEquals(List.of("CHECK_IN", "TEMP_MERGED"),
                    tEvents.stream().map(e -> e.getType().name()).toList());
            assertEquals(no, fEvents.get(1).getMergeNo());
            assertEquals(no, tEvents.get(1).getMergeNo());

            // 成员审计链：临时入住 -> 身份核验 -> 合并（家庭关系变化 + 房间信息）
            List<MemberEvent> chain = memberEvents.findByIdentityNoOrderByIdAsc("id-c-3");
            assertEquals(List.of("TEMPORARY_CHECK_IN", "IDENTITY_VERIFIED", "MERGED"),
                    chain.stream().map(e -> e.getType().name()).toList());
            MemberEvent merged = chain.get(2);
            assertEquals(t1, merged.getFromHouseholdNo());
            assertEquals(formalNo, merged.getToHouseholdNo());
            assertEquals(no, merged.getMergeNo());
            assertTrue(merged.getRoomId() != null && merged.getShelterId() != null);
        });
        assertOccupancyMatchesStays();

        // 合并已确认：原幂等键重放返回首次结果，换新键再次确认被拒绝
        IdempotentResponse replay = mergeService.confirm(
                new ConfirmMergeRequest(confirmKey, no, formalStay));
        assertEquals(confirmed.body(), replay.body());
        assertConflict(() -> mergeService.confirm(new ConfirmMergeRequest(key(), no, formalStay)),
                "MERGE_ALREADY_CONFIRMED");
    }

    @Test
    void failedConfirmRollsBackEverythingAndApplicationCanBeRetried() {
        Shelter shelter = newShelter();
        Room formalRoom = newRoom(shelter, 1, 2, false);
        Room tempRoom = newRoom(shelter, 2, 4, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-rb-1", "id-rb-2");
        temporary(t1, formalNo, "id-rb-3", "id-rb-4", "id-rb-5");
        Long formalStay = checkIn(formalNo, shelter.getId());
        Long tempStay = tempCheckIn(t1, shelter.getId());
        verify("id-rb-3");
        verify("id-rb-4");
        verify("id-rb-5");

        String no = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(no, formalNo, List.of(t1), null));
        // 合并后共 5 人，安置点没有 5 床房间
        assertConflict(() -> mergeService.confirm(new ConfirmMergeRequest(key(), no, formalStay)),
                "NO_SUITABLE_ROOM");

        // 原入住与床位完全不变
        assertEquals(2, rooms.findById(formalRoom.getId()).orElseThrow().getOccupied());
        assertEquals(3, rooms.findById(tempRoom.getId()).orElseThrow().getOccupied());
        assertEquals(StayStatus.ACTIVE, stays.findById(formalStay).orElseThrow().getStatus());
        assertEquals(StayStatus.ACTIVE, stays.findById(tempStay).orElseThrow().getStatus());
        assertOccupancyMatchesStays();

        // 扩容后可用同一申请单重试成功
        Room bigRoom = newRoom(shelter, 3, 6, false);
        IdempotentResponse confirmed = mergeService.confirm(
                new ConfirmMergeRequest(key(), no, formalStay));
        assertTrue(confirmed.body().contains("\"memberCount\":5"));
        assertEquals(5, rooms.findById(bigRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(formalRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(tempRoom.getId()).orElseThrow().getOccupied());
        assertOccupancyMatchesStays();
    }

    @Test
    void mergeCanRelocateWholeFamilyToNewShelterInOneTransaction() {
        Shelter origin = newShelter();
        Room originRoom = newRoom(origin, 1, 3, false);
        Shelter destination = newShelter();
        Room destRoom = newRoom(destination, 1, 8, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-mv-1", "id-mv-2");
        temporary(t1, formalNo, "id-mv-3");
        Long formalStay = checkIn(formalNo, origin.getId());
        tempCheckIn(t1, origin.getId());
        verify("id-mv-3");

        String no = mergeNo();
        mergeService.createApplication(
                new CreateMergeRequest(no, formalNo, List.of(t1), destination.getId()));
        IdempotentResponse confirmed = mergeService.confirm(
                new ConfirmMergeRequest(key(), no, formalStay));

        assertTrue(confirmed.body().contains("\"shelterId\":" + destination.getId()));
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(3, rooms.findById(destRoom.getId()).orElseThrow().getOccupied());
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            List<MemberEvent> chain = memberEvents.findByIdentityNoOrderByIdAsc("id-mv-3");
            // 迁移链：原安置点临时入住 -> 核验 -> 合并到新安置点房间
            MemberEvent checkIn = chain.get(0);
            MemberEvent merged = chain.get(2);
            assertEquals(origin.getId(), checkIn.getShelterId());
            assertEquals(destination.getId(), merged.getShelterId());
            assertNotEquals(checkIn.getRoomId(), merged.getRoomId());
        });
        assertOccupancyMatchesStays();
    }

    @Test
    void mergeRechecksAccessibilityRequirement() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 6, false);
        newRoom(shelter, 2, 1, false);
        // 临时成员需要无障碍：1 床无障碍房间可供单人临时入住，但物理上容不下合并后的全家
        newRoom(shelter, 3, 1, true);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-ac-1");
        // 走散成员需要无障碍设施
        registrationService.registerTemporaryHousehold(new RegisterTemporaryHouseholdRequest(
                t1, formalNo, List.of(new MemberDto("id-ac-2", 40, true))));
        checkIn(formalNo, shelter.getId());
        tempCheckIn(t1, shelter.getId());
        verify("id-ac-2");

        String no = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(no, formalNo, List.of(t1), null));
        assertConflict(() -> mergeService.confirm(
                new ConfirmMergeRequest(key(), no, currentStayId(formalNo))), "NO_SUITABLE_ROOM");

        newRoom(shelter, 4, 6, true);
        IdempotentResponse confirmed = mergeService.confirm(
                new ConfirmMergeRequest(key(), no, currentStayId(formalNo)));
        assertTrue(confirmed.body().contains("\"roomNumber\":4"));
        assertOccupancyMatchesStays();
    }

    @Test
    void mergeRejectsDuplicateIdentityAcrossParticipants() {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 8, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-dup-1");
        temporary(t1, formalNo, "id-dup-2");
        checkIn(formalNo, shelter.getId());
        tempCheckIn(t1, shelter.getId());
        verify("id-dup-2");

        // 通过仓储绕过注册校验，制造合并范围内的重复身份标识（纵深防御场景）
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household t = households.findByHouseholdNo(t1).orElseThrow();
            t.addMember(new Member("id-dup-1", 9, false, VerificationStatus.VERIFIED));
        });

        String no = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(no, formalNo, List.of(t1), null));
        assertConflict(() -> mergeService.confirm(
                new ConfirmMergeRequest(key(), no, currentStayId(formalNo))), "MEMBER_NOT_UNIQUE");
        assertOccupancyMatchesStays();
    }

    @Test
    void checkoutReleasesBedsAndIsIdempotent() {
        Shelter shelter = newShelter();
        Room room = newRoom(shelter, 1, 4, false);
        String formalNo = unique("f");
        formal(formalNo, "id-co-1", "id-co-2");
        Long stayId = checkIn(formalNo, shelter.getId());

        String idemKey = key();
        IdempotentResponse first = temporaryStayService.checkout(
                new CheckoutRequest(idemKey, formalNo, stayId));
        assertTrue(first.body().contains("ENDED"));
        IdempotentResponse replay = temporaryStayService.checkout(
                new CheckoutRequest(idemKey, formalNo, stayId));
        assertEquals(first.body(), replay.body());
        assertEquals(0, rooms.findById(room.getId()).orElseThrow().getOccupied());

        // 退住后不能重复退住或转移
        assertConflict(() -> temporaryStayService.checkout(
                new CheckoutRequest(key(), formalNo, stayId)), "NO_ACTIVE_STAY");
        assertConflict(() -> checkInService.transfer(
                new TransferRequest(key(), formalNo, shelter.getId() + 9999, stayId)), "NO_ACTIVE_STAY");
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            var events = stayEvents.findByHouseholdNoOrderByIdAsc(formalNo);
            assertEquals("CHECKOUT", events.get(events.size() - 1).getType().name());
            assertTrue(memberEvents.findByIdentityNoOrderByIdAsc("id-co-1").stream()
                    .anyMatch(e -> e.getType().name().equals("CHECKOUT")));
        });
        assertOccupancyMatchesStays();
    }

    @Test
    void concurrentMergeAndCheckoutHaveSingleWinner() throws Exception {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 6, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-race-1", "id-race-2");
        temporary(t1, formalNo, "id-race-3");
        Long formalStay = checkIn(formalNo, shelter.getId());
        Long tempStay = tempCheckIn(t1, shelter.getId());
        verify("id-race-3");
        String no = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(no, formalNo, List.of(t1), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> mergeTask = () -> {
            ready.countDown();
            go.await();
            try {
                mergeService.confirm(new ConfirmMergeRequest(key(), no, formalStay));
                outcomes.add("MERGED");
            } catch (ApiException e) {
                outcomes.add("MERGE_REJECTED:" + e.getCode());
            }
            return null;
        };
        Callable<Void> checkoutTask = () -> {
            ready.countDown();
            go.await();
            try {
                temporaryStayService.checkout(new CheckoutRequest(key(), t1, tempStay));
                outcomes.add("CHECKED_OUT");
            } catch (ApiException e) {
                outcomes.add("CHECKOUT_REJECTED:" + e.getCode());
            }
            return null;
        };
        Future<?> f1 = pool.submit(mergeTask);
        Future<?> f2 = pool.submit(checkoutTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long merged = outcomes.stream().filter("MERGED"::equals).count();
        long checkedOut = outcomes.stream().filter("CHECKED_OUT"::equals).count();
        assertEquals(1, merged + checkedOut, "合并与退住只能有一个成功: " + outcomes);
        // 没有重复成员：临时家庭在住成员数要么随合并归零，要么随退住归零
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            long activeTempMembers = households.findByHouseholdNo(t1).orElseThrow().getMembers().stream()
                    .filter(Member::isCurrentlyStaying).count();
            assertEquals(0, activeTempMembers);
        });
        assertOccupancyMatchesStays();
    }

    @Test
    void concurrentConfirmsOfSameMergeHaveSingleWinner() throws Exception {
        Shelter shelter = newShelter();
        newRoom(shelter, 1, 6, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-cm-1");
        temporary(t1, formalNo, "id-cm-2");
        Long formalStay = checkIn(formalNo, shelter.getId());
        tempCheckIn(t1, shelter.getId());
        verify("id-cm-2");
        String no = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(no, formalNo, List.of(t1), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> task = () -> {
            ready.countDown();
            go.await();
            try {
                mergeService.confirm(new ConfirmMergeRequest(key(), no, formalStay));
                outcomes.add("MERGED");
            } catch (ApiException e) {
                outcomes.add("REJECTED:" + e.getCode());
            }
            return null;
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, outcomes.stream().filter("MERGED"::equals).count(), outcomes.toString());
        // 正式家庭只有一个有效入住，人数为 2，成员不重复
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Stay active = stays.findByHouseholdHouseholdNoAndStatus(formalNo, StayStatus.ACTIVE).orElseThrow();
            assertEquals(2, active.getMemberCount());
            Household f = households.findByHouseholdNo(formalNo).orElseThrow();
            long staying = f.getMembers().stream().filter(Member::isCurrentlyStaying).count();
            assertEquals(2, staying);
        });
        assertOccupancyMatchesStays();
    }

    @Test
    void concurrentMergeAndTransferHaveSingleWinner() throws Exception {
        Shelter a = newShelter();
        newRoom(a, 1, 6, false);
        Shelter b = newShelter();
        newRoom(b, 1, 6, false);
        String formalNo = unique("f");
        String t1 = unique("t1");
        formal(formalNo, "id-mt-1");
        temporary(t1, formalNo, "id-mt-2");
        Long formalStay = checkIn(formalNo, a.getId());
        tempCheckIn(t1, a.getId());
        verify("id-mt-2");
        String no = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(no, formalNo, List.of(t1), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> mergeTask = () -> {
            ready.countDown();
            go.await();
            try {
                mergeService.confirm(new ConfirmMergeRequest(key(), no, formalStay));
                outcomes.add("MERGED");
            } catch (ApiException e) {
                outcomes.add("MERGE_REJECTED:" + e.getCode());
            }
            return null;
        };
        Callable<Void> transferTask = () -> {
            ready.countDown();
            go.await();
            try {
                checkInService.transfer(new TransferRequest(key(), formalNo, b.getId(), formalStay));
                outcomes.add("TRANSFERRED");
            } catch (ApiException e) {
                outcomes.add("TRANSFER_REJECTED:" + e.getCode());
            }
            return null;
        };
        Future<?> f1 = pool.submit(mergeTask);
        Future<?> f2 = pool.submit(transferTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, outcomes.stream().filter(o -> o.equals("MERGED") || o.equals("TRANSFERRED")).count(),
                outcomes.toString());
        assertOccupancyMatchesStays();
    }

    private Long currentStayId(String householdNo) {
        return new TransactionTemplate(txManager).execute(status ->
                stays.findByHouseholdHouseholdNoAndStatus(householdNo, StayStatus.ACTIVE)
                        .orElseThrow().getId());
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 极简 JSON 字段读取，避免各处手写下标 */
    static class Json {
        static final ObjectMapper MAPPER = new ObjectMapper();

        static Long readLong(String json, String field) {
            try {
                JsonNode node = MAPPER.readTree(json);
                return node.get(field).asLong();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
