package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.MemberEvent;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.domain.TransferApplication;
import com.chris64233.cc.shelter.domain.TransferStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.repo.TransferActivitySlotRepository;
import com.chris64233.cc.shelter.repo.TransferApplicationRepository;
import com.chris64233.cc.shelter.repo.TransferRoomReservationRepository;
import com.chris64233.cc.shelter.service.CheckInService;
import com.chris64233.cc.shelter.service.MergeService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.service.TemporaryStayService;
import com.chris64233.cc.shelter.service.TransferQueryService;
import com.chris64233.cc.shelter.service.TransferService;
import com.chris64233.cc.shelter.web.dto.AcceptTransferRequest;
import com.chris64233.cc.shelter.web.dto.ArriveTransferRequest;
import com.chris64233.cc.shelter.web.dto.CancelTransferRequest;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.CheckoutRequest;
import com.chris64233.cc.shelter.web.dto.ConfirmMergeRequest;
import com.chris64233.cc.shelter.web.dto.CreateMergeRequest;
import com.chris64233.cc.shelter.web.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.web.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.web.dto.CreateTransferRequest;
import com.chris64233.cc.shelter.web.dto.ExpireTransferRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.MemberDto;
import com.chris64233.cc.shelter.web.dto.RejectTransferRequest;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.RegisterTemporaryHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class TransferServiceIntegrationTest {

    @Autowired RegistrationService registrationService;
    @Autowired CheckInService checkInService;
    @Autowired TemporaryStayService temporaryStayService;
    @Autowired MergeService mergeService;
    @Autowired TransferService transferService;
    @Autowired TransferQueryService transferQueryService;
    @Autowired HouseholdRepository households;
    @Autowired RoomRepository rooms;
    @Autowired StayRepository stays;
    @Autowired StayEventRepository stayEvents;
    @Autowired MemberEventRepository memberEvents;
    @Autowired TransferApplicationRepository transferApplications;
    @Autowired TransferActivitySlotRepository activitySlots;
    @Autowired TransferRoomReservationRepository reservations;
    @Autowired ObjectMapper objectMapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private String key() {
        return "idem-" + UUID.randomUUID();
    }

    private String bizNo() {
        return "TRF-" + UUID.randomUUID();
    }

    private String handoverNo() {
        return "HOV-" + UUID.randomUUID();
    }

    private String mergeNo() {
        return "MRG-" + UUID.randomUUID();
    }

    private Shelter newShelter() {
        return registrationService.createShelter(new CreateShelterRequest("shelter-" + UUID.randomUUID()));
    }

    private Room newRoom(Shelter shelter, int number, int beds, boolean accessible) {
        return registrationService.addRoom(shelter.getId(), new CreateRoomRequest(number, beds, accessible));
    }

    private String formal(String no, String... identities) {
        List<MemberDto> dtos = new ArrayList<>();
        for (int i = 0; i < identities.length; i++) {
            dtos.add(new MemberDto(identities[i], 30 + i, false));
        }
        registrationService.registerHousehold(new RegisterHouseholdRequest(no, dtos));
        return no;
    }

    private Long checkIn(String householdNo, Long shelterId) {
        IdempotentResponse response = checkInService.checkIn(
                new CheckInRequest(key(), householdNo, shelterId));
        return json(response.body(), "stayId").asLong();
    }

    private IdempotentResponse createTransfer(String householdNo, Long targetShelterId,
                                              Integer targetRoomNumber, Instant plannedAt) {
        return transferService.create(new CreateTransferRequest(bizNo(), householdNo,
                targetShelterId, targetRoomNumber, plannedAt, "EXT-" + UUID.randomUUID()));
    }

    /** 创建申请并返回业务号 */
    private String apply(String householdNo, Long targetShelterId) {
        String transferNo = bizNo();
        IdempotentResponse response = transferService.create(new CreateTransferRequest(
                transferNo, householdNo, targetShelterId, null,
                Instant.now().plus(2, ChronoUnit.DAYS), "EXT-" + UUID.randomUUID()));
        assertEquals(201, response.status());
        return transferNo;
    }

    private void accept(String transferNo) {
        IdempotentResponse response = transferService.accept(new AcceptTransferRequest(key(), transferNo));
        assertEquals(200, response.status());
        assertTrue(response.body().contains("ACCEPTED"));
    }

    private IdempotentResponse arrive(String transferNo, String handoverNo, Long expectedStayId) {
        return transferService.arrive(
                new ArriveTransferRequest(handoverNo, transferNo, expectedStayId));
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

    /** 全局床位账实一致：房间占用总和 = 有效入住人数总和；预留另计 */
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

    private TransferApplication entity(String transferNo) {
        return new TransactionTemplate(txManager).execute(status ->
                transferApplications.findByTransferNo(transferNo).orElseThrow());
    }

    private JsonNode json(String body, String field) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.get(field);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- 阶段一：申请 ----------

    @Test
    void createRejectsWhenHouseholdCheckedOutOrNeverCheckedIn() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-none-" + UUID.randomUUID(), "id-x1");
        assertConflict(() -> createTransfer(f, b.getId(), null, Instant.now().plusSeconds(3600)),
                "NO_ACTIVE_STAY");
    }

    @Test
    void createRejectsWithPendingReunification() {
        Shelter a = newShelter();
        newRoom(a, 1, 8, false);
        Shelter b = newShelter();
        newRoom(b, 1, 8, false);
        String f = formal("f-pm-" + UUID.randomUUID(), "id-pm-1");
        String t = "t-pm-" + UUID.randomUUID();
        registrationService.registerTemporaryHousehold(new RegisterTemporaryHouseholdRequest(
                t, f, List.of(new MemberDto("id-pm-2", 9, false))));
        checkIn(f, a.getId());
        temporaryStayService.temporaryCheckIn(new TemporaryCheckInRequest(key(), t, a.getId()));
        temporaryStayService.verifyIdentity(new VerifyIdentityRequest(key(), "id-pm-2"));
        mergeService.createApplication(new CreateMergeRequest(mergeNo(), f, List.of(t), null));

        // 存在未完成团聚申请，转移直接拒绝
        assertConflict(() -> createTransfer(f, b.getId(), null, Instant.now().plusSeconds(3600)),
                "MERGE_IN_PROGRESS");
    }

    @Test
    void createRejectsTemporaryHouseholdAndSameShelter() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        String f = formal("f-ts-" + UUID.randomUUID(), "id-ts-1");
        String t = "t-ts-" + UUID.randomUUID();
        registrationService.registerTemporaryHousehold(new RegisterTemporaryHouseholdRequest(
                t, f, List.of(new MemberDto("id-ts-2", 9, false))));
        temporaryStayService.temporaryCheckIn(new TemporaryCheckInRequest(key(), t, a.getId()));
        assertConflict(() -> createTransfer(t, a.getId() + 999, null, Instant.now().plusSeconds(3600)),
                "NOT_FORMAL_HOUSEHOLD");

        checkIn(f, a.getId());
        assertConflict(() -> createTransfer(f, a.getId(), null, Instant.now().plusSeconds(3600)),
                "SAME_SHELTER");
    }

    @Test
    void createFreezesMembersAndStayAndIsIdempotent() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-fz-" + UUID.randomUUID(), "id-fz-1", "id-fz-2");
        Long stayId = checkIn(f, a.getId());

        String transferNo = bizNo();
        Instant planned = Instant.now().plus(1, ChronoUnit.HOURS);
        CreateTransferRequest req = new CreateTransferRequest(transferNo, f, b.getId(), null,
                planned, "EXT-777");
        IdempotentResponse first = transferService.create(req);
        assertEquals(201, first.status());
        assertTrue(first.body().contains("REQUESTED"));
        assertTrue(first.body().contains("EXT-777"));
        assertTrue(first.body().contains("\"frozenMemberCount\":2"));

        // 相同业务号相同内容重放返回首次结果
        IdempotentResponse replay = transferService.create(req);
        assertEquals(first.body(), replay.body());
        // 相同业务号不同内容 → 冲突
        assertConflict(() -> transferService.create(new CreateTransferRequest(transferNo, f,
                b.getId(), 5, planned, "EXT-777")), "IDEMPOTENCY_CONFLICT");

        TransferApplication app = entity(transferNo);
        assertEquals(TransferStatus.REQUESTED, app.getStatus());
        assertEquals(stayId, app.getOriginStayId());
        assertEquals(2, app.getFrozenMembers().size());
        assertEquals("id-fz-1", app.getFrozenMembers().get(0).getIdentityNo());
        assertTrue(activitySlots.existsById(f));
    }

    @Test
    void onlyOneActiveTransferPerHousehold() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-one-" + UUID.randomUUID(), "id-one-1");
        checkIn(f, a.getId());
        apply(f, b.getId());
        assertConflict(() -> createTransfer(f, b.getId(), null, Instant.now().plusSeconds(3600)),
                "TRANSFER_IN_PROGRESS");
    }

    // ---------- 阶段二：接受预留 ----------

    @Test
    void acceptReservesTargetRoomWithoutReleasingOriginBeds() {
        Shelter a = newShelter();
        Room originRoom = newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        Room targetRoom = newRoom(b, 1, 4, false);
        String f = formal("f-acc-" + UUID.randomUUID(), "id-acc-1", "id-acc-2");
        checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);

        // 来源床位未释放，目标房间实际占用仍为 0，名额以预留形式存在
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        TransferApplication app = entity(transferNo);
        assertEquals(TransferStatus.ACCEPTED, app.getStatus());
        assertEquals(targetRoom.getId(), app.getReservedRoomId());
        assertTrue(reservations.findByTransferApplicationId(app.getId()).isPresent());

        // 接受幂等：相同幂等键重放
        // 重复接受（新键）被拒绝
        assertConflict(() -> transferService.accept(new AcceptTransferRequest(key(), transferNo)),
                "TRANSFER_ALREADY_ACCEPTED");
        assertOccupancyMatchesStays();
    }

    @Test
    void acceptFailsWithoutSuitableRoomAndKeepsOriginStay() {
        Shelter a = newShelter();
        newRoom(a, 1, 6, false);
        Shelter b = newShelter();
        newRoom(b, 1, 2, false); // 容不下 3 人家庭
        String f = formal("f-nr-" + UUID.randomUUID(), "id-nr-1", "id-nr-2", "id-nr-3");
        checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        assertConflict(() -> transferService.accept(new AcceptTransferRequest(key(), transferNo)),
                "NO_SUITABLE_ROOM");
        // 仍处于申请中，来源入住不变，后续扩容可再接受
        assertEquals(TransferStatus.REQUESTED, entity(transferNo).getStatus());
        assertOccupancyMatchesStays();

        newRoom(b, 2, 6, false);
        accept(transferNo);
    }

    @Test
    void targetRoomRequirementIsHonored() {
        Shelter a = newShelter();
        newRoom(a, 1, 6, false);
        Shelter b = newShelter();
        Room r1 = newRoom(b, 1, 6, false);
        Room r2 = newRoom(b, 2, 3, false);
        String f = formal("f-req-" + UUID.randomUUID(), "id-req-1", "id-req-2");
        checkIn(f, a.getId());

        String transferNo = bizNo();
        transferService.create(new CreateTransferRequest(transferNo, f, b.getId(), 2,
                Instant.now().plusSeconds(3600), "EXT-REQ"));
        accept(transferNo);
        assertEquals(r2.getId(), entity(transferNo).getReservedRoomId());

        // 指定一个容不下家庭的房间 → 接受失败
        String f2 = formal("f-req2-" + UUID.randomUUID(), "id-req-3", "id-req-4", "id-req-5");
        checkIn(f2, a.getId());
        String transferNo2 = bizNo();
        transferService.create(new CreateTransferRequest(transferNo2, f2, b.getId(), 2,
                Instant.now().plusSeconds(3600), "EXT-REQ2"));
        // 房间2只剩 1 个床位（3-2 预留），容不下 3 人
        assertConflict(() -> transferService.accept(new AcceptTransferRequest(key(), transferNo2)),
                "NO_SUITABLE_ROOM");
        assertEquals(0, rooms.findById(r1.getId()).orElseThrow().getOccupied());
    }

    @Test
    void twoFamiliesCannotReserveSameTargetRoom() {
        Shelter a = newShelter();
        newRoom(a, 1, 10, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false); // 唯一房间，4 床
        String f1 = formal("f-r1-" + UUID.randomUUID(), "id-r1-1", "id-r1-2", "id-r1-3");
        String f2 = formal("f-r2-" + UUID.randomUUID(), "id-r2-1", "id-r2-2", "id-r2-3");
        checkIn(f1, a.getId());
        checkIn(f2, a.getId());
        String t1 = apply(f1, b.getId());
        String t2 = apply(f2, b.getId());
        accept(t1);
        // 第二户只剩 1 个床位名额，不能重复预留
        assertConflict(() -> transferService.accept(new AcceptTransferRequest(key(), t2)),
                "NO_SUITABLE_ROOM");
        // 第二户来源入住仍有效，可取消申请后重新安排
        assertEquals(StayStatus.ACTIVE,
                stays.findByHouseholdHouseholdNoAndStatus(f2, StayStatus.ACTIVE).orElseThrow().getStatus());
        transferService.cancel(new CancelTransferRequest(key(), t2));
        assertOccupancyMatchesStays();
    }

    // ---------- 阶段三：到达确认 ----------

    @Test
    void arriveSwitchesBothStaysAndMovesAllMembersAtomically() {
        Shelter a = newShelter();
        Room originRoom = newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        Room targetRoom = newRoom(b, 1, 4, false);
        String f = formal("f-arr-" + UUID.randomUUID(), "id-arr-1", "id-arr-2");
        Long originStay = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);

        String hov = handoverNo();
        IdempotentResponse arrived = arrive(transferNo, hov, originStay);
        assertEquals(200, arrived.status());
        assertTrue(arrived.body().contains("ARRIVED"));
        assertTrue(arrived.body().contains("\"shelterId\":" + b.getId()));
        assertEquals(hov, json(arrived.body(), "handoverNo").asText());
        Long newStayId = json(arrived.body(), "stayId").asLong();
        assertNotEquals(originStay, newStayId);

        // 床位切换：来源释放、目标占用；预留行删除、活动槽位释放
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(StayStatus.ENDED, stays.findById(originStay).orElseThrow().getStatus());
        assertEquals(StayStatus.ACTIVE, stays.findById(newStayId).orElseThrow().getStatus());
        TransferApplication app = entity(transferNo);
        assertEquals(TransferStatus.ARRIVED, app.getStatus());
        assertEquals(newStayId, app.getResultStayId());
        assertTrue(reservations.findByTransferApplicationId(app.getId()).isEmpty());
        assertFalse(activitySlots.existsById(f));

        // 家庭入住事件流：CHECK_IN + TRANSFER（带转移业务号）
        List<StayEvent> events = stayEvents.findByHouseholdNoOrderByIdAsc(f);
        assertEquals(List.of("CHECK_IN", "TRANSFER"),
                events.stream().map(e -> e.getType().name()).toList());
        StayEvent transferEvent = events.get(1);
        assertEquals(transferNo, transferEvent.getTransferNo());
        assertEquals(a.getId(), transferEvent.getFromShelterId());
        assertEquals(b.getId(), transferEvent.getToShelterId());

        // 成员交接事件：来源房间 -> 目标房间
        List<MemberEvent> chain = memberEvents.findByIdentityNoOrderByIdAsc("id-arr-1");
        assertEquals(1, chain.size());
        MemberEvent moved = chain.get(0);
        assertEquals("TRANSFER", moved.getType().name());
        assertEquals(a.getId(), moved.getShelterId());
        assertEquals(b.getId(), moved.getToShelterId());
        assertEquals(transferNo, moved.getTransferNo());

        // 交接清单：全部 HANDED_OVER
        Map<String, Object> handover = transferQueryService.handoverList(transferNo);
        assertEquals("ARRIVED", handover.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) handover.get("members");
        assertEquals(2, members.size());
        members.forEach(m -> assertEquals("HANDED_OVER", m.get("handoverStatus")));
        assertOccupancyMatchesStays();
    }

    @Test
    void arriveBeforeAcceptOrWithWrongExpectedStayFails() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-early-" + UUID.randomUUID(), "id-early-1");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        assertConflict(() -> arrive(transferNo, handoverNo(), stayId), "TRANSFER_NOT_ACCEPTED");

        accept(transferNo);
        assertConflict(() -> arrive(transferNo, handoverNo(), stayId + 99999), "STALE_STATE");
        // 失败后状态与床位不变
        assertEquals(TransferStatus.ACCEPTED, entity(transferNo).getStatus());
        assertOccupancyMatchesStays();
    }

    @Test
    void arriveRechecksFrozenListWhenMemberDepartsAndKeepsBothSidesConsistent() {
        Shelter a = newShelter();
        Room originRoom = newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        Room targetRoom = newRoom(b, 1, 4, false);
        String f = formal("f-frz-" + UUID.randomUUID(), "id-frzc-1", "id-frzc-2");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);

        // 并发的成员退出先落库（直接把一名在住成员标记离开，模拟退住通道）
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household household = households.findByHouseholdNo(f).orElseThrow();
            household.getMembers().stream()
                    .filter(Member::isCurrentlyStaying)
                    .findFirst().orElseThrow().markDeparted();
        });

        // 旧的到达确认必须失败，不能产生成员留在两处或半成品目标入住
        assertConflict(() -> arrive(transferNo, handoverNo(), stayId), "FROZEN_LIST_CHANGED");
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(TransferStatus.ACCEPTED, entity(transferNo).getStatus());
        assertTrue(reservations.findByTransferApplicationId(entity(transferNo).getId()).isPresent());
        assertEquals(StayStatus.ACTIVE, stays.findById(stayId).orElseThrow().getStatus());

        // 清单已变，该笔只能取消；取消后来源入住仍有效、预留释放
        transferService.cancel(new CancelTransferRequest(key(), transferNo));
        assertEquals(TransferStatus.CANCELLED, entity(transferNo).getStatus());
        assertTrue(reservations.findByTransferApplicationId(entity(transferNo).getId()).isEmpty());
        assertOccupancyMatchesStays();
    }

    @Test
    void handoverNoIsIdempotentAndConflictsOnDifferentContent() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-idem-" + UUID.randomUUID(), "id-idem-1");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);

        String hov = handoverNo();
        IdempotentResponse first = arrive(transferNo, hov, stayId);
        // 相同交接事件号 + 相同内容 → 返回首次结果
        IdempotentResponse replay = arrive(transferNo, hov, stayId);
        assertEquals(first.body(), replay.body());
        // 相同交接事件号但内容不同 → 冲突
        assertConflict(() -> arrive(transferNo, hov, stayId + 1), "IDEMPOTENCY_CONFLICT");
        // 已完成不能撤销：拒绝/取消/超时都不允许
        assertConflict(() -> transferService.reject(
                new RejectTransferRequest(key(), transferNo, null)), "TRANSFER_ALREADY_COMPLETED");
        assertConflict(() -> transferService.cancel(
                new CancelTransferRequest(key(), transferNo)), "TRANSFER_ALREADY_COMPLETED");
        assertConflict(() -> transferService.expire(
                new ExpireTransferRequest(key(), transferNo)), "TRANSFER_ALREADY_COMPLETED");
    }

    // ---------- 拒绝 / 取消 / 超时 ----------

    @Test
    void rejectReleasesReservationAndKeepsOriginStay() {
        Shelter a = newShelter();
        Room originRoom = newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-rej-" + UUID.randomUUID(), "id-rej-1", "id-rej-2");
        checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);

        transferService.reject(new RejectTransferRequest(key(), transferNo, "容量调整"));
        assertEquals(TransferStatus.REJECTED, entity(transferNo).getStatus());
        assertTrue(reservations.findByTransferApplicationId(entity(transferNo).getId()).isEmpty());
        assertFalse(activitySlots.existsById(f));
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());

        // 释放后可重新发起转移
        String transferNo2 = apply(f, b.getId());
        accept(transferNo2);
        assertOccupancyMatchesStays();
    }

    @Test
    void cancelBeforeAcceptReleasesSlotAndIsIdempotent() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-ccl-" + UUID.randomUUID(), "id-ccl-1");
        checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        String idem = key();
        IdempotentResponse first = transferService.cancel(
                new CancelTransferRequest(idem, transferNo));
        assertTrue(first.body().contains("CANCELLED"));
        IdempotentResponse replay = transferService.cancel(
                new CancelTransferRequest(idem, transferNo));
        assertEquals(first.body(), replay.body());
        assertFalse(activitySlots.existsById(f));
        // 再次取消已终态申请 → 错误
        assertConflict(() -> transferService.cancel(new CancelTransferRequest(key(), transferNo)),
                "TRANSFER_NOT_ACTIVE");
    }

    @Test
    void expireOnlyAfterDueAndBatchSweepWorks() {
        Shelter a = newShelter();
        newRoom(a, 1, 6, false);
        Shelter b = newShelter();
        newRoom(b, 1, 6, false);
        newRoom(b, 2, 6, false);
        String f1 = formal("f-exp1-" + UUID.randomUUID(), "id-exp1-1");
        String f2 = formal("f-exp2-" + UUID.randomUUID(), "id-exp2-1");
        checkIn(f1, a.getId());
        checkIn(f2, a.getId());

        // 未到期不能超时
        String notDue = apply(f1, b.getId());
        accept(notDue);
        assertConflict(() -> transferService.expire(new ExpireTransferRequest(key(), notDue)),
                "TRANSFER_NOT_DUE");
        assertEquals(TransferStatus.ACCEPTED, entity(notDue).getStatus());

        // 已到期（计划到达时间在过去）的申请可超时，预留释放、来源不变
        String dueNo = bizNo();
        transferService.create(new CreateTransferRequest(dueNo, f2, b.getId(), null,
                Instant.now().minus(1, ChronoUnit.HOURS), "EXT-DUE"));
        accept(dueNo);
        transferService.expire(new ExpireTransferRequest(key(), dueNo));
        assertEquals(TransferStatus.EXPIRED, entity(dueNo).getStatus());
        assertTrue(reservations.findByTransferApplicationId(entity(dueNo).getId()).isEmpty());
        assertFalse(activitySlots.existsById(f2));
        assertOccupancyMatchesStays();

        // 批量扫描：再造一笔到期申请，expireDue 处理
        String f3 = formal("f-exp3-" + UUID.randomUUID(), "id-exp3-1");
        checkIn(f3, a.getId());
        String dueNo2 = bizNo();
        transferService.create(new CreateTransferRequest(dueNo2, f3, b.getId(), null,
                Instant.now().minusSeconds(60), "EXT-DUE2"));
        int processed = transferService.expireDue();
        assertTrue(processed >= 1);
        assertEquals(TransferStatus.EXPIRED, entity(dueNo2).getStatus());
    }

    // ---------- 完成后只能反向转移 ----------

    @Test
    void completedTransferIsUndoneOnlyByNewReverseTransfer() {
        Shelter a = newShelter();
        Room roomA = newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        Room roomB = newRoom(b, 1, 4, false);
        String f = formal("f-rev-" + UUID.randomUUID(), "id-rev-1", "id-rev-2");
        checkIn(f, a.getId());
        String t1 = apply(f, b.getId());
        accept(t1);
        arrive(t1, handoverNo(), entity(t1).getOriginStayId());
        assertEquals(2, rooms.findById(roomB.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(roomA.getId()).orElseThrow().getOccupied());

        // 发起反向转移 B -> A，完整走三阶段
        Long stayAtB = stays.findByHouseholdHouseholdNoAndStatus(f, StayStatus.ACTIVE).orElseThrow().getId();
        String t2 = bizNo();
        transferService.create(new CreateTransferRequest(t2, f, a.getId(), null,
                Instant.now().plusSeconds(3600), "EXT-BACK"));
        accept(t2);
        arrive(t2, handoverNo(), stayAtB);
        assertEquals(0, rooms.findById(roomB.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(roomA.getId()).orElseThrow().getOccupied());
        List<String> types = stayEvents.findByHouseholdNoOrderByIdAsc(f).stream()
                .map(e -> e.getType().name()).toList();
        assertEquals(List.of("CHECK_IN", "TRANSFER", "TRANSFER"), types);
        assertOccupancyMatchesStays();
    }

    // ---------- 与即时转移/退住/合并的并发 ----------

    @Test
    void immediateTransferBlockedWhileActiveTransferExists() {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-blk-" + UUID.randomUUID(), "id-blk-1");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        assertConflict(() -> checkInService.transfer(
                new TransferRequest(key(), f, b.getId(), stayId)), "TRANSFER_IN_PROGRESS");
        // 取消后即时转移恢复可用
        transferService.cancel(new CancelTransferRequest(key(), transferNo));
        IdempotentResponse moved = checkInService.transfer(
                new TransferRequest(key(), f, b.getId(), stayId));
        assertTrue(moved.body().contains("\"shelterId\":" + b.getId()));
    }

    @Test
    void concurrentArriveAndCheckoutHaveSingleWinner() throws Exception {
        Shelter a = newShelter();
        newRoom(a, 1, 6, false);
        Shelter b = newShelter();
        newRoom(b, 1, 6, false);
        String f = formal("f-rac-" + UUID.randomUUID(), "id-rac-1", "id-rac-2");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> arriveTask = () -> {
            ready.countDown();
            go.await();
            try {
                arrive(transferNo, handoverNo(), stayId);
                outcomes.add("ARRIVED");
            } catch (ApiException e) {
                outcomes.add("ARRIVE_REJECTED:" + e.getCode());
            }
            return null;
        };
        Callable<Void> checkoutTask = () -> {
            ready.countDown();
            go.await();
            try {
                temporaryStayService.checkout(new CheckoutRequest(key(), f, stayId));
                outcomes.add("CHECKED_OUT");
            } catch (ApiException e) {
                outcomes.add("CHECKOUT_REJECTED:" + e.getCode());
            }
            return null;
        };
        Future<?> f1 = pool.submit(arriveTask);
        Future<?> f2 = pool.submit(checkoutTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long arrived = outcomes.stream().filter("ARRIVED"::equals).count();
        long checkedOut = outcomes.stream().filter("CHECKED_OUT"::equals).count();
        assertEquals(1, arrived + checkedOut, "到达与退住只能有一个成功: " + outcomes);
        // 不会出现成员留在两处
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household household = households.findByHouseholdNo(f).orElseThrow();
            long staying = household.getMembers().stream().filter(Member::isCurrentlyStaying).count();
            if (arrived == 1) {
                assertEquals(2, staying);
            } else {
                assertEquals(0, staying);
            }
        });
        assertOccupancyMatchesStays();
    }

    @Test
    void concurrentArriveAndMergeConfirmHaveSingleWinner() throws Exception {
        Shelter a = newShelter();
        newRoom(a, 1, 8, false);
        Shelter b = newShelter();
        newRoom(b, 1, 8, false);
        String f = formal("f-mrg-" + UUID.randomUUID(), "id-mrg-1");
        String t = "t-mrg-" + UUID.randomUUID();
        registrationService.registerTemporaryHousehold(new RegisterTemporaryHouseholdRequest(
                t, f, List.of(new MemberDto("id-mrg-2", 9, false))));
        Long formalStay = checkIn(f, a.getId());
        temporaryStayService.temporaryCheckIn(new TemporaryCheckInRequest(key(), t, a.getId()));
        temporaryStayService.verifyIdentity(new VerifyIdentityRequest(key(), "id-mrg-2"));

        // 先发起并接受转移
        String transferNo = apply(f, b.getId());
        accept(transferNo);
        // 转移活动期间创建团聚申请（允许），确认合并与到达确认并发竞争
        String mergeBizNo = mergeNo();
        mergeService.createApplication(new CreateMergeRequest(mergeBizNo, f, List.of(t), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> arriveTask = () -> {
            ready.countDown();
            go.await();
            try {
                arrive(transferNo, handoverNo(), formalStay);
                outcomes.add("ARRIVED");
            } catch (ApiException e) {
                outcomes.add("ARRIVE_REJECTED:" + e.getCode());
            }
            return null;
        };
        Callable<Void> mergeTask = () -> {
            ready.countDown();
            go.await();
            try {
                mergeService.confirm(new ConfirmMergeRequest(key(), mergeBizNo, formalStay));
                outcomes.add("MERGED");
            } catch (ApiException e) {
                outcomes.add("MERGE_REJECTED:" + e.getCode());
            }
            return null;
        };
        Future<?> f1 = pool.submit(arriveTask);
        Future<?> f2 = pool.submit(mergeTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, outcomes.stream().filter(o -> o.equals("ARRIVED") || o.equals("MERGED")).count(),
                outcomes.toString());
        // 正式家庭始终只有一个有效入住
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            long active = stays.findAll().stream()
                    .filter(s -> s.getHousehold().getHouseholdNo().equals(f))
                    .filter(s -> s.getStatus() == StayStatus.ACTIVE).count();
            assertEquals(1, active);
        });
        assertOccupancyMatchesStays();
    }

    @Test
    void duplicateHandoverWithSameNumberIsIdempotentUnderConcurrency() throws Exception {
        Shelter a = newShelter();
        newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        newRoom(b, 1, 4, false);
        String f = formal("f-dhov-" + UUID.randomUUID(), "id-dhov-1");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());
        accept(transferNo);
        String hov = handoverNo();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
        Callable<Void> task = () -> {
            ready.countDown();
            go.await();
            bodies.add(arrive(transferNo, hov, stayId).body());
            return null;
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(2, bodies.size());
        assertEquals(bodies.iterator().next(), bodies.toArray()[1]);
        // 目标入住只建立一次
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            long activeAtTarget = stays.findAll().stream()
                    .filter(s -> s.getHousehold().getHouseholdNo().equals(f))
                    .filter(s -> s.getStatus() == StayStatus.ACTIVE)
                    .filter(s -> s.getShelter().getId().equals(b.getId())).count();
            assertEquals(1, activeAtTarget);
        });
    }

    // ---------- 查询 ----------

    @Test
    void queriesExposeProgressRoomOccupancyAndTimeline() {
        Shelter a = newShelter();
        Room originRoom = newRoom(a, 1, 4, false);
        Shelter b = newShelter();
        Room targetRoom = newRoom(b, 1, 4, false);
        String f = formal("f-q-" + UUID.randomUUID(), "id-q-1", "id-q-2");
        Long stayId = checkIn(f, a.getId());
        String transferNo = apply(f, b.getId());

        // 进度
        var progress = transferQueryService.progress(transferNo);
        assertEquals("REQUESTED", progress.status());
        assertEquals("EXT-", progress.externalBusinessNo().substring(0, 4));
        assertNotNull(progress.frozenMembers());

        accept(transferNo);
        // 两端房间占用：来源仍占 2；目标占用 0 + 预留 2 = 可用 2
        @SuppressWarnings("unchecked")
        Map<String, Object> occupancy = transferQueryService.roomOccupancy(transferNo);
        @SuppressWarnings("unchecked")
        Map<String, Object> origin = (Map<String, Object>) occupancy.get("origin");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) occupancy.get("target");
        assertEquals(2, origin.get("occupied"));
        assertEquals(0, target.get("occupied"));
        assertEquals(2, target.get("reservedBeds"));
        assertEquals(2, target.get("availableBeds"));
        assertEquals(targetRoom.getId(), target.get("roomId"));
        assertEquals(originRoom.getId(), origin.get("roomId"));

        // 交接清单：待交接
        @SuppressWarnings("unchecked")
        Map<String, Object> handover = transferQueryService.handoverList(transferNo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) handover.get("members");
        assertEquals(2, items.size());
        items.forEach(m -> assertEquals("FROZEN", m.get("handoverStatus")));

        arrive(transferNo, handoverNo(), stayId);

        // 时间线跨安置点：CHECK_IN(A) -> TRANSFER(A->B)
        var timeline = transferQueryService.timeline(f);
        assertEquals(2, timeline.size());
        assertEquals("TRANSFER", timeline.get(1).type());
        assertEquals(a.getId(), timeline.get(1).fromShelterId());
        assertEquals(b.getId(), timeline.get(1).toShelterId());

        // 按家庭查转移列表
        List<?> all = transferQueryService.byHousehold(f);
        assertEquals(1, all.size());
    }
}
