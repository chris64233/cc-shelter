package com.chris64233.cc.shelter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.MemberEvent;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.domain.VerificationStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.service.CheckInService;
import com.chris64233.cc.shelter.service.MergeService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.service.TemporaryStayService;
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
    @Autowired HouseholdRepository households;
    @Autowired RoomRepository rooms;
    @Autowired StayRepository stays;
    @Autowired StayEventRepository stayEvents;
    @Autowired MemberEventRepository memberEvents;
    @Autowired ObjectMapper objectMapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private static final ObjectMapper JSON = new ObjectMapper();

    private String key() {
        return "idem-" + UUID.randomUUID();
    }

    private String no(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private Shelter shelter(String name) {
        return registrationService.createShelter(new CreateShelterRequest(name));
    }

    private Room room(Shelter shelter, int number, int beds, boolean accessible) {
        return registrationService.addRoom(shelter.getId(), new CreateRoomRequest(number, beds, accessible));
    }

    private Household formal(String no, String... identities) {
        List<MemberDto> dtos = new ArrayList<>();
        for (int i = 0; i < identities.length; i++) {
            dtos.add(new MemberDto(identities[i], 30 + i, false));
        }
        return registrationService.registerHousehold(new RegisterHouseholdRequest(no, dtos));
    }

    private Household accessibleFormal(String no, String... identities) {
        List<MemberDto> dtos = new ArrayList<>();
        for (int i = 0; i < identities.length; i++) {
            dtos.add(new MemberDto(identities[i], 30 + i, i == 0));
        }
        return registrationService.registerHousehold(new RegisterHouseholdRequest(no, dtos));
    }

    private Household temporary(String no, String claimedOriginal, String... identities) {
        List<MemberDto> dtos = new ArrayList<>();
        for (String identity : identities) {
            dtos.add(new MemberDto(identity, 10, false));
        }
        return registrationService.registerTemporaryHousehold(
                new RegisterTemporaryHouseholdRequest(no, claimedOriginal, dtos));
    }

    private Long checkIn(String householdNo, Long shelterId) {
        return readLong(checkInService.checkIn(
                new CheckInRequest(key(), householdNo, shelterId)).body(), "stayId");
    }

    private Long tempCheckIn(String householdNo, Long shelterId) {
        return readLong(temporaryStayService.temporaryCheckIn(
                new TemporaryCheckInRequest(key(), householdNo, shelterId)).body(), "stayId");
    }

    private void verify(String identity) {
        temporaryStayService.verifyIdentity(new VerifyIdentityRequest(key(), identity));
    }

    private Instant future() {
        return Instant.now().plus(1, ChronoUnit.DAYS);
    }

    private CreateTransferRequest request(String transferNo, String householdNo, Long targetShelter) {
        return new CreateTransferRequest(transferNo, householdNo, targetShelter, null, null, future());
    }

    private String create(String householdNo, Long targetShelter) {
        String transferNo = no("TRF");
        transferService.create(request(transferNo, householdNo, targetShelter));
        return transferNo;
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

    private static Long readLong(String json, String field) {
        try {
            JsonNode node = JSON.readTree(json);
            return node.get(field).asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readString(String json, String field) {
        try {
            JsonNode node = JSON.readTree(json);
            return node.get(field).asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 全局床位账实一致：占用总数 = 有效入住人数；预留不重复且非负 */
    private void assertBedsBalanced() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            int occupied = rooms.findAll().stream().mapToInt(Room::getOccupied).sum();
            int active = (int) stays.findAll().stream()
                    .filter(s -> s.getStatus() == StayStatus.ACTIVE)
                    .mapToLong(Stay::getMemberCount).sum();
            assertEquals(active, occupied, "房间占用总数与有效入住总人数不一致");
            assertFalse(rooms.findAll().stream().anyMatch(r -> r.getOccupied() < 0), "出现负数占用");
            assertFalse(rooms.findAll().stream().anyMatch(r -> r.getReservedBeds() < 0), "出现负数预留");
        });
    }

    @Test
    void fullHandoverMovesWholeFamilyAtomicallyAndKeepsAuditChains() {
        Shelter origin = shelter("o");
        Room originRoom = room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 1, false);
        Room targetRoom = room(target, 2, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-1", "id-2");
        Long originStay = checkIn(householdNo, origin.getId());

        String transferNo = no("TRF");
        IdempotentResponse created = transferService.create(request(transferNo, householdNo, target.getId()));
        assertEquals(201, created.status());
        assertTrue(created.body().contains("REQUESTED"));
        // 申请阶段来源床位不变、目标无预留
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());

        IdempotentResponse accepted = transferService.accept(new AcceptTransferRequest(transferNo));
        assertEquals(200, accepted.status());
        assertTrue(accepted.body().contains("ACCEPTED"));
        // 接受只预留，来源床位仍有效
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());

        String handoverNo = no("HND");
        IdempotentResponse arrived = transferService.arrive(
                new ArriveTransferRequest(transferNo, handoverNo, originStay));
        assertEquals(200, arrived.status());
        assertTrue(arrived.body().contains("COMPLETED"));
        Long newStayId = readLong(arrived.body(), "stayId");
        assertNotEquals(originStay, newStayId);
        // 一次性：来源释放、目标预留转占用
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(StayStatus.ENDED, stays.findById(originStay).orElseThrow().getStatus());
        assertEquals(StayStatus.ACTIVE, stays.findById(newStayId).orElseThrow().getStatus());

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household h = households.findByHouseholdNo(householdNo).orElseThrow();
            assertEquals(2, h.stayingMemberCount());
            List<StayEvent> events = stayEvents.findByHouseholdNoOrderByIdAsc(householdNo);
            assertEquals(List.of("CHECK_IN", "TRANSFER"),
                    events.stream().map(e -> e.getType().name()).toList());
            StayEvent transferEvent = events.get(1);
            assertEquals(transferNo, transferEvent.getTransferNo());
            assertEquals(handoverNo, transferEvent.getHandoverNo());
            assertEquals(origin.getId(), transferEvent.getFromShelterId());
            assertEquals(target.getId(), transferEvent.getToShelterId());
            List<MemberEvent> chain = memberEvents.findByIdentityNoOrderByIdAsc("id-1");
            assertEquals(1, chain.size());
            assertEquals("TRANSFERRED", chain.get(0).getType().name());
            assertEquals(transferNo, chain.get(0).getTransferNo());
            assertEquals(handoverNo, chain.get(0).getHandoverNo());
            assertEquals(targetRoom.getId(), chain.get(0).getRoomId());
        });
        assertBedsBalanced();
    }

    @Test
    void createRejectsTemporaryHouseholdCheckedOutFamilyAndSameShelter() {
        Shelter shelter1 = shelter("a");
        room(shelter1, 1, 4, false);
        Shelter shelter2 = shelter("b");
        room(shelter2, 1, 4, false);

        String formalNo = no("F");
        formal(formalNo, "id-f");
        checkIn(formalNo, shelter1.getId());
        String tempNo = no("T");
        temporary(tempNo, formalNo, "id-t");
        tempCheckIn(tempNo, shelter1.getId());

        // 临时家庭不能整体转移
        assertConflict(() -> transferService.create(request(no("TRF"), tempNo, shelter2.getId())),
                "TARGET_NOT_FORMAL_HOUSEHOLD");
        // 同安置点
        assertConflict(() -> transferService.create(request(no("TRF"), formalNo, shelter1.getId())),
                "SAME_SHELTER");
        // 已退住家庭
        Long stayId = currentStayId(formalNo);
        temporaryStayService.checkout(new CheckoutRequest(key(), formalNo, stayId));
        assertConflict(() -> transferService.create(request(no("TRF"), formalNo, shelter2.getId())),
                "NO_ACTIVE_STAY");
        assertBedsBalanced();
    }

    @Test
    void createRejectsWhenPendingMergeOrActiveTransferExists() {
        Shelter origin = shelter("o");
        room(origin, 1, 6, false);
        Shelter target = shelter("t");
        room(target, 1, 6, false);

        String formalNo = no("F");
        String tempNo = no("T");
        formal(formalNo, "id-f1", "id-f2");
        temporary(tempNo, formalNo, "id-t1");
        checkIn(formalNo, origin.getId());
        tempCheckIn(tempNo, origin.getId());
        verify("id-t1");

        String mergeNo = no("MRG");
        mergeService.createApplication(
                new CreateMergeRequest(mergeNo, formalNo, List.of(tempNo), null));
        // 正式家庭存在未完成团聚
        assertConflict(() -> transferService.create(request(no("TRF"), formalNo, target.getId())),
                "PENDING_MERGE_EXISTS");
        // 临时家庭参与了未完成团聚
        assertConflict(() -> transferService.create(request(no("TRF"), tempNo, target.getId())),
                "TARGET_NOT_FORMAL_HOUSEHOLD");

        // 合并完成后可以发起转移
        Long formalStay = currentStayId(formalNo);
        mergeService.confirm(new ConfirmMergeRequest(key(), mergeNo, formalStay));
        String transferNo = create(formalNo, target.getId());
        // 同一家庭第二笔活动转移被拒绝
        assertConflict(() -> transferService.create(request(no("TRF"), formalNo, target.getId())),
                "ACTIVE_TRANSFER_EXISTS");
        // 活动转移期间不能再发起团聚：注册新的临时家庭并核验
        String tempNo2 = no("T");
        temporary(tempNo2, formalNo, "id-t2");
        tempCheckIn(tempNo2, target.getId());
        verify("id-t2");
        assertConflict(() -> mergeService.createApplication(
                new CreateMergeRequest(no("MRG"), formalNo, List.of(tempNo2), null)),
                "ACTIVE_TRANSFER_EXISTS");
        transferNo.length();
        assertBedsBalanced();
    }

    @Test
    void createRejectsUnverifiedFormalMember() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String formalNo = no("F");
        formal(formalNo, "id-v");
        checkIn(formalNo, origin.getId());
        // 直接改动数据制造未核验在住成员（纵深防御）
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household h = households.findByHouseholdNo(formalNo).orElseThrow();
            h.getMembers().get(0).markUnverified();
        });
        assertConflict(() -> transferService.create(request(no("TRF"), formalNo, target.getId())),
                "IDENTITY_NOT_VERIFIED");
    }

    @Test
    void createIsIdempotentByTransferNoAndConflictOnDifferentContent() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter t1 = shelter("t1");
        room(t1, 1, 4, false);
        Shelter t2 = shelter("t2");
        room(t2, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-i");
        checkIn(householdNo, origin.getId());

        String transferNo = no("TRF");
        Instant arrival = future();
        CreateTransferRequest req = new CreateTransferRequest(
                transferNo, householdNo, t1.getId(), null, null, arrival);
        IdempotentResponse first = transferService.create(req);
        IdempotentResponse replay = transferService.create(req);
        // 时间戳经数据库存取后精度会截断，比较稳定字段
        assertEquals(readString(first.body(), "transferNo"), readString(replay.body(), "transferNo"));
        assertEquals(readString(first.body(), "status"), readString(replay.body(), "status"));
        assertEquals(readLong(first.body(), "targetShelterId"), readLong(replay.body(), "targetShelterId"));
        assertEquals(readLong(first.body(), "originStayId"), readLong(replay.body(), "originStayId"));
        // 同业务号不同目标安置点
        assertConflict(() -> transferService.create(new CreateTransferRequest(
                transferNo, householdNo, t2.getId(), null, null, arrival)),
                "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void acceptReservesRoomAndBlocksNormalCheckinFromUsingReservedBeds() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        Room onlyRoom = room(target, 1, 3, false);
        String moving = no("F");
        formal(moving, no("id-m"), no("id-m"));
        Long movingStay = checkIn(moving, origin.getId());

        String transferNo = create(moving, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));
        // 3 床房间预留 2 床：2 人家庭不能使用剩余 1 床（容量不足）
        String other = no("F");
        formal(other, no("id-o"), no("id-o"));
        assertConflict(() -> checkInService.checkIn(
                new CheckInRequest(key(), other, target.getId())), "NO_SUITABLE_ROOM");
        assertEquals(0, rooms.findById(onlyRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(onlyRoom.getId()).orElseThrow().getReservedBeds());

        // 1 人家庭可使用剩余 1 床（系统允许多家庭同住一间）
        String single = no("F");
        formal(single, no("id-s"));
        checkIn(single, target.getId());
        assertEquals(1, rooms.findById(onlyRoom.getId()).orElseThrow().getOccupied());
        // 到达确认仍能完成：预留 2 转占用，来源释放
        transferService.arrive(new ArriveTransferRequest(transferNo, no("HND"), movingStay));
        assertEquals(3, rooms.findById(onlyRoom.getId()).orElseThrow().getOccupied());
        assertEquals(0, rooms.findById(onlyRoom.getId()).orElseThrow().getReservedBeds());
        assertBedsBalanced();
    }

    @Test
    void sameTargetRoomCannotBeReservedByTwoFamilies() {
        Shelter origin1 = shelter("o1");
        room(origin1, 1, 4, false);
        Shelter origin2 = shelter("o2");
        room(origin2, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false); // 只够一个 2 人家庭
        String f1 = no("F");
        String f2 = no("F");
        formal(f1, "id-a1", "id-a2");
        formal(f2, "id-b1", "id-b2");
        checkIn(f1, origin1.getId());
        checkIn(f2, origin2.getId());

        String trf1 = create(f1, target.getId());
        transferService.accept(new AcceptTransferRequest(trf1));
        String trf2 = create(f2, target.getId());
        // 第二家接受时已无满足容量的房间
        assertConflict(() -> transferService.accept(new AcceptTransferRequest(trf2)),
                "NO_SUITABLE_ROOM");
        assertEquals(2, transferService.progress(trf1).members().size());
        assertBedsBalanced();
    }

    @Test
    void acceptAndArriveRecheckAccessibilityRequirement() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, true);
        Shelter target = shelter("t");
        room(target, 1, 6, false);
        room(target, 2, 2, true);
        String householdNo = no("F");
        accessibleFormal(householdNo, "id-ac1", "id-ac2");
        checkIn(householdNo, origin.getId());

        String transferNo = no("TRF");
        transferService.create(new CreateTransferRequest(
                transferNo, householdNo, target.getId(), null, true, future()));
        IdempotentResponse accepted = transferService.accept(new AcceptTransferRequest(transferNo));
        // 选中无障碍房间 2 号
        assertTrue(accepted.body().contains("\"targetRoomNumber\":2"));
    }

    @Test
    void rejectAndCancelReleaseReservationAndKeepOriginStayValid() {
        for (boolean reject : List.of(true, false)) {
            Shelter origin = shelter("o");
            Room originRoom = room(origin, 1, 4, false);
            Shelter target = shelter("t");
            Room targetRoom = room(target, 1, 4, false);
            String householdNo = no("F");
            formal(householdNo, no("id-c"), no("id-c"));
            Long originStay = checkIn(householdNo, origin.getId());

            if (reject) {
                String transferNo = create(householdNo, target.getId());
                transferService.accept(new AcceptTransferRequest(transferNo));
                assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
                IdempotentResponse result = transferService.reject(
                        new RejectTransferRequest(transferNo, "满员"));
                assertTrue(result.body().contains("REJECTED"));
                // 重复拒绝幂等
                IdempotentResponse replay = transferService.reject(
                        new RejectTransferRequest(transferNo, "满员"));
                assertEquals(result.body(), replay.body());
                // 拒绝后不能取消
                assertConflict(() -> transferService.cancel(
                        new CancelTransferRequest(transferNo, null)), "TRANSFER_ALREADY_FINISHED");
            } else {
                // 先取消一个尚未接受的申请（无预留）
                String requested = create(householdNo, target.getId());
                transferService.cancel(new CancelTransferRequest(requested, "改期"));
                assertEquals("CANCELLED", transferService.progress(requested).status());
                assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
                // 再发起一笔并接受，然后取消（已接受，释放预留）
                String transferNo = create(householdNo, target.getId());
                transferService.accept(new AcceptTransferRequest(transferNo));
                IdempotentResponse result = transferService.cancel(
                        new CancelTransferRequest(transferNo, "不去了"));
                assertTrue(result.body().contains("CANCELLED"));
            }
            // 预留释放，来源入住始终有效
            assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
            assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
            assertEquals(StayStatus.ACTIVE, stays.findById(originStay).orElseThrow().getStatus());
            // 终结后可以重新发起转移
            String again = create(householdNo, target.getId());
            transferService.accept(new AcceptTransferRequest(again));
            assertBedsBalanced();
            // 清理，避免影响账实断言之外的数据（终结掉新申请）
            transferService.cancel(new CancelTransferRequest(again, "test cleanup"));
            assertBedsBalanced();
        }
    }

    @Test
    void timeoutReleasesOverdueReservationsAndKeepsOriginStayValid() {
        Shelter origin = shelter("o");
        Room originRoom = room(origin, 1, 4, false);
        Shelter target = shelter("t");
        Room targetRoom = room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-to1", "id-to2");
        Long originStay = checkIn(householdNo, origin.getId());

        String transferNo = no("TRF");
        Instant planned = Instant.now().minus(2, ChronoUnit.HOURS);
        transferService.create(new CreateTransferRequest(
                transferNo, householdNo, target.getId(), null, null, planned));
        transferService.accept(new AcceptTransferRequest(transferNo));
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());

        // 计划时间之前不超时
        assertEquals(0, transferService.timeoutOverdue(planned.minusSeconds(1)).size());
        assertEquals("ACCEPTED", transferService.progress(transferNo).status());
        // 超时扫描终结
        List<String> timedOut = transferService.timeoutOverdue(Instant.now());
        assertEquals(List.of(transferNo), timedOut);
        assertEquals("TIMED_OUT", transferService.progress(transferNo).status());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(StayStatus.ACTIVE, stays.findById(originStay).orElseThrow().getStatus());
        // 超时后不能确认到达
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), originStay)),
                "TRANSFER_ALREADY_FINISHED");
        assertBedsBalanced();
    }

    @Test
    void completedTransferCannotBeRevokedAndReverseTransferWorks() {
        Shelter a = shelter("a");
        room(a, 1, 4, false);
        Shelter b = shelter("b");
        room(b, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-r1");
        Long stayA = checkIn(householdNo, a.getId());

        String transferNo = create(householdNo, b.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));
        String handoverNo = no("HND");
        transferService.arrive(new ArriveTransferRequest(transferNo, handoverNo, stayA));

        // 已完成：重复接受按幂等键回放首次结果，拒绝/取消/再次到达均被拒
        IdempotentResponse acceptReplay = transferService.accept(new AcceptTransferRequest(transferNo));
        assertTrue(acceptReplay.body().contains("ACCEPTED"));
        assertConflict(() -> transferService.reject(new RejectTransferRequest(transferNo, null)),
                "TRANSFER_ALREADY_COMPLETED");
        assertConflict(() -> transferService.cancel(new CancelTransferRequest(transferNo, null)),
                "TRANSFER_ALREADY_COMPLETED");
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), stayA)),
                "TRANSFER_ALREADY_COMPLETED");

        // 只能通过新的反向转移返回
        Long stayB = currentStayId(householdNo);
        String reverseNo = create(householdNo, a.getId());
        transferService.accept(new AcceptTransferRequest(reverseNo));
        IdempotentResponse back = transferService.arrive(
                new ArriveTransferRequest(reverseNo, no("HND"), stayB));
        assertTrue(back.body().contains("COMPLETED"));
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            List<StayEvent> events = stayEvents.findByHouseholdNoOrderByIdAsc(householdNo);
            assertEquals(List.of("CHECK_IN", "TRANSFER", "TRANSFER"),
                    events.stream().map(e -> e.getType().name()).toList());
        });
        assertBedsBalanced();
    }

    @Test
    void arriveFailsWhenMemberChecksOutAfterFreeze() {
        Shelter origin = shelter("o");
        Room originRoom = room(origin, 1, 4, false);
        Shelter target = shelter("t");
        Room targetRoom = room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-co1", "id-co2");
        Long originStay = checkIn(householdNo, origin.getId());

        String transferNo = create(householdNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));
        // 冻结后家庭退住：活动转移被同事务自动取消并释放预留
        temporaryStayService.checkout(new CheckoutRequest(key(), householdNo, originStay));
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), originStay)),
                "TRANSFER_ALREADY_FINISHED");
        assertEquals("CANCELLED", transferService.progress(transferNo).status());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
        assertEquals(0, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertBedsBalanced();
    }

    @Test
    void arriveFailsWhenFrozenMemberListChangesUnderneath() {
        Shelter origin = shelter("o");
        Room originRoom = room(origin, 1, 4, false);
        Shelter target = shelter("t");
        Room targetRoom = room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-fz1", "id-fz2");
        Long originStay = checkIn(householdNo, origin.getId());
        String transferNo = create(householdNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));

        // 纵深防御：绕过业务通道（模拟并发合并/数据修复）直接让一个冻结成员离开，
        // 来源入住仍存在但冻结清单已变，到达确认必须失败，且不能动任何床位
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Household h = households.findByHouseholdNo(householdNo).orElseThrow();
            h.getMembers().get(0).markDeparted();
        });
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), originStay)),
                "FROZEN_LIST_CHANGED");
        // 申请仍在 ACCEPTED，两端床位均未改动
        assertEquals("ACCEPTED", transferService.progress(transferNo).status());
        assertEquals(2, rooms.findById(originRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertBedsBalanced();
    }

    @Test
    void arriveFailsWhenFamilyMovesAfterFreeze() {
        Shelter origin = shelter("o");
        room(origin, 1, 8, false);
        Shelter target = shelter("t");
        Room targetRoom = room(target, 1, 4, false);
        String formalNo = no("F");
        formal(formalNo, "id-mg1", "id-mg2");
        Long formalStay = checkIn(formalNo, origin.getId());

        String transferNo = create(formalNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));

        // 活动转移存在时即时转移会在同事务内自动取消该申请并释放预留，家庭改去第三安置点
        Shelter third = shelter("x");
        Room thirdRoom = room(third, 1, 4, false);
        checkInService.transfer(new TransferRequest(key(), formalNo, third.getId(), formalStay));

        // 旧的到达确认失败，目标点没有建立入住、预留已释放
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), formalStay)),
                "TRANSFER_ALREADY_FINISHED");
        assertEquals("CANCELLED", transferService.progress(transferNo).status());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getReservedBeds());
        assertEquals(0, rooms.findById(targetRoom.getId()).orElseThrow().getOccupied());
        assertEquals(2, rooms.findById(thirdRoom.getId()).orElseThrow().getOccupied());
        assertBedsBalanced();
    }

    @Test
    void arriveRejectsStaleStayIdAndNotAcceptedApplication() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-s1");
        Long originStay = checkIn(householdNo, origin.getId());

        String transferNo = create(householdNo, target.getId());
        // 未接受不能到达
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), originStay)),
                "TRANSFER_NOT_ACCEPTED");
        transferService.accept(new AcceptTransferRequest(transferNo));
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, no("HND"), originStay + 999)),
                "STALE_STATE");
        // 申请仍在 ACCEPTED，来源入住不变
        assertEquals("ACCEPTED", transferService.progress(transferNo).status());
        assertEquals(StayStatus.ACTIVE, stays.findById(originStay).orElseThrow().getStatus());
    }

    @Test
    void handoverIdempotencyReplaysAndConflictsOnDifferentContent() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-h1", "id-h2");
        Long originStay = checkIn(householdNo, origin.getId());
        String transferNo = create(householdNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));

        String handoverNo = no("HND");
        ArriveTransferRequest req = new ArriveTransferRequest(transferNo, handoverNo, originStay);
        IdempotentResponse first = transferService.arrive(req);
        IdempotentResponse replay = transferService.arrive(req);
        assertEquals(first.body(), replay.body());
        // 相同交接事件号、不同内容（期望入住）
        assertConflict(() -> transferService.arrive(
                new ArriveTransferRequest(transferNo, handoverNo, originStay + 1)),
                "IDEMPOTENCY_CONFLICT");
        assertBedsBalanced();
    }

    @Test
    void progressHandoverListAndTimelineQueriesWork() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-q1", "id-q2");
        Long originStay = checkIn(householdNo, origin.getId());
        String transferNo = create(householdNo, target.getId());

        Map<String, Object> handover = transferService.handoverList(transferNo);
        assertEquals(2, handover.get("memberCount"));
        assertEquals(Boolean.TRUE, handover.get("frozenListMatchesCurrent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) handover.get("members");
        assertEquals(2, rows.size());
        assertEquals(Boolean.TRUE, rows.get(0).get("matchesFrozen"));

        transferService.accept(new AcceptTransferRequest(transferNo));
        var progress = transferService.progress(transferNo);
        assertEquals("ACCEPTED", progress.status());
        assertEquals(2, progress.originOccupied());
        assertEquals(2, progress.targetReservedBeds());
        assertEquals(4, progress.targetBedCount());

        transferService.arrive(new ArriveTransferRequest(transferNo, no("HND"), originStay));
        List<Map<String, Object>> timeline = transferService.stayTimeline(householdNo);
        assertEquals(2, timeline.size());
        assertEquals("CHECK_IN", timeline.get(0).get("type"));
        assertEquals("TRANSFER", timeline.get(1).get("type"));
        assertEquals(transferNo, timeline.get(1).get("transferNo"));
        assertBedsBalanced();
    }

    @Test
    void handoverListDetectsFrozenMismatch() {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-x1");
        Long originStay = checkIn(householdNo, origin.getId());
        String transferNo = create(householdNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));

        temporaryStayService.checkout(new CheckoutRequest(key(), householdNo, originStay));
        Map<String, Object> handover = transferService.handoverList(transferNo);
        assertEquals(Boolean.FALSE, handover.get("frozenListMatchesCurrent"));
    }

    @Test
    void concurrentArrivesHaveSingleWinnerWithoutDoubleBooking() throws Exception {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-ra1", "id-ra2");
        Long originStay = checkIn(householdNo, origin.getId());
        String transferNo = create(householdNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> task = () -> {
            ready.countDown();
            go.await();
            try {
                transferService.arrive(new ArriveTransferRequest(
                        transferNo, no("HND"), originStay));
                outcomes.add("ARRIVED");
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

        assertEquals(1, outcomes.stream().filter("ARRIVED"::equals).count(), outcomes.toString());
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Stay active = stays.findByHouseholdHouseholdNoAndStatus(householdNo, StayStatus.ACTIVE).orElseThrow();
            assertEquals(2, active.getMemberCount());
            assertEquals(target.getId(), active.getShelter().getId());
            long activeStays = stays.findAll().stream()
                    .filter(s -> s.getHousehold().getHouseholdNo().equals(householdNo))
                    .filter(s -> s.getStatus() == StayStatus.ACTIVE).count();
            assertEquals(1, activeStays, "成员不能留在两处");
        });
        assertBedsBalanced();
    }

    @Test
    void concurrentArriveAndCheckoutHaveSingleWinner() throws Exception {
        Shelter origin = shelter("o");
        room(origin, 1, 4, false);
        Shelter target = shelter("t");
        room(target, 1, 4, false);
        String householdNo = no("F");
        formal(householdNo, "id-rc1", "id-rc2");
        Long originStay = checkIn(householdNo, origin.getId());
        String transferNo = create(householdNo, target.getId());
        transferService.accept(new AcceptTransferRequest(transferNo));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        Callable<Void> arriveTask = () -> {
            ready.countDown();
            go.await();
            try {
                transferService.arrive(new ArriveTransferRequest(
                        transferNo, no("HND"), originStay));
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
                temporaryStayService.checkout(new CheckoutRequest(key(), householdNo, originStay));
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
        // 不会出现来源床位已释放但目标入住未建立：要么已到达，要么（退住胜出）预留被释放且无 ACTIVE 入住
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            long active = stays.findAll().stream()
                    .filter(s -> s.getHousehold().getHouseholdNo().equals(householdNo))
                    .filter(s -> s.getStatus() == StayStatus.ACTIVE).count();
            if (arrived == 1) {
                assertEquals(1, active);
            } else {
                assertEquals(0, active);
            }
        });
        assertBedsBalanced();
    }

    private Long currentStayId(String householdNo) {
        return new TransactionTemplate(txManager).execute(status ->
                stays.findByHouseholdHouseholdNoAndStatus(householdNo, StayStatus.ACTIVE)
                        .orElseThrow().getId());
    }
}
