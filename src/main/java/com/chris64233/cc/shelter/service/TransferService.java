package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.IdempotencyRecord;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.MemberEvent;
import com.chris64233.cc.shelter.domain.MergeApplication;
import com.chris64233.cc.shelter.domain.MergeStatus;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.domain.TransferApplication;
import com.chris64233.cc.shelter.domain.TransferMemberSnapshot;
import com.chris64233.cc.shelter.domain.TransferStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.MergeApplicationRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.repo.TransferApplicationRepository;
import com.chris64233.cc.shelter.web.dto.AcceptTransferRequest;
import com.chris64233.cc.shelter.web.dto.ArriveTransferRequest;
import com.chris64233.cc.shelter.web.dto.CancelTransferRequest;
import com.chris64233.cc.shelter.web.dto.CreateTransferRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.RejectTransferRequest;
import com.chris64233.cc.shelter.web.dto.TransferApplicationResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家庭跨安置点整体转移的两阶段交接服务。
 *
 * <p>阶段一：创建申请（冻结成员与来源入住）→ 目标安置点接受（预留房间，不释放来源床位）；
 * 阶段二：到达确认在同一事务内结束来源入住、启用目标入住、迁移全部成员。
 * 拒绝/取消/超时只释放目标预留，来源入住始终有效；已完成转移不可撤销。
 *
 * <p>锁序与全系统一致：家庭行（主键升序）→ 申请单行 → 房间行（主键升序），
 * 与合并/退住/即时转移串行化，避免双重入住、成员留两处或床位账实不一致。
 */
@Service
public class TransferService {

    private static final List<TransferStatus> ACTIVE_STATUSES =
            List.of(TransferStatus.REQUESTED, TransferStatus.ACCEPTED);

    private final TransferApplicationRepository transferApplications;
    private final HouseholdRepository households;
    private final ShelterRepository shelters;
    private final StayRepository stays;
    private final StayEventRepository stayEvents;
    private final MemberEventRepository memberEvents;
    private final MergeApplicationRepository mergeApplications;
    private final RoomRepository rooms;
    private final RoomAllocator roomAllocator;
    private final IdempotencyStore idempotency;

    public TransferService(TransferApplicationRepository transferApplications,
                           HouseholdRepository households, ShelterRepository shelters,
                           StayRepository stays, StayEventRepository stayEvents,
                           MemberEventRepository memberEvents,
                           MergeApplicationRepository mergeApplications, RoomRepository rooms,
                           RoomAllocator roomAllocator, IdempotencyStore idempotency) {
        this.transferApplications = transferApplications;
        this.households = households;
        this.shelters = shelters;
        this.stays = stays;
        this.stayEvents = stayEvents;
        this.memberEvents = memberEvents;
        this.mergeApplications = mergeApplications;
        this.rooms = rooms;
        this.roomAllocator = roomAllocator;
        this.idempotency = idempotency;
    }

    // ------------------------------------------------------------------
    // 阶段一前半：创建申请（冻结成员与入住关系）
    // ------------------------------------------------------------------

    @Transactional
    public IdempotentResponse create(CreateTransferRequest request) {
        String fingerprint = fingerprint("CREATE_TRANSFER", request.transferNo(), request.householdNo(),
                request.targetShelterId(), request.requiredBedCount(), request.requiresAccessible(),
                request.plannedArrivalAt());
        Optional<TransferApplication> existing = transferApplications.findByTransferNo(request.transferNo());
        if (existing.isPresent()) {
            return replayApplication(existing.get(), fingerprint);
        }

        // 先锁家庭行（全系统锁序起点），再复核业务号，串行化并发创建与并发退住/合并
        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND",
                        "家庭不存在: " + request.householdNo()));
        existing = transferApplications.findByTransferNo(request.transferNo());
        if (existing.isPresent()) {
            return replayApplication(existing.get(), fingerprint);
        }

        if (household.isTemporary()) {
            throw ApiException.conflict("TARGET_NOT_FORMAL_HOUSEHOLD",
                    "整体转移只面向正式家庭，临时家庭请先走团聚合并: " + request.householdNo());
        }
        Stay originStay = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY",
                        "家庭已退住或当前没有有效入住，不能发起转移: " + request.householdNo()));
        if (originStay.getShelter().getId().equals(request.targetShelterId())) {
            throw ApiException.conflict("SAME_SHELTER", "目标安置点与来源安置点相同");
        }
        shelters.findById(request.targetShelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND",
                        "目标安置点不存在: " + request.targetShelterId()));

        // 存在未完成团聚申请的家庭不能发起转移（团聚申请可能把该家庭整体迁走或改变成员）
        ensureNoPendingMerge(Set.of(request.householdNo()));
        // 同一家庭同时只能有一笔活动转移
        if (!transferApplications.findActiveByHousehold(request.householdNo(), ACTIVE_STATUSES).isEmpty()) {
            throw ApiException.conflict("ACTIVE_TRANSFER_EXISTS",
                    "家庭已有进行中的转移申请，同一家庭同时只能有一笔活动转移: " + request.householdNo());
        }

        List<Member> staying = stayingMembers(household);
        if (staying.isEmpty()) {
            throw ApiException.conflict("NO_STAYING_MEMBER", "家庭没有在住成员，不能发起转移");
        }
        // 成员身份尚未确认（仅可能出现在数据被直接改动的纵深防御场景）不得发起
        for (Member member : staying) {
            if (member.getVerificationStatus() == com.chris64233.cc.shelter.domain.VerificationStatus.UNVERIFIED) {
                throw ApiException.conflict("IDENTITY_NOT_VERIFIED",
                        "家庭成员身份尚未全部确认，不能发起整体转移: " + member.getIdentityNo());
            }
        }

        int frozenSize = staying.size();
        // 无障碍要求取“成员实际需要”与“申请明确要求”的并集，宁严勿松
        boolean accessibleRequired = staying.stream().anyMatch(Member::isNeedsAccessible)
                || request.isRequiresAccessible();
        int requiredBeds = request.requiredBedCount() == null ? frozenSize : request.requiredBedCount();
        if (requiredBeds < frozenSize) {
            throw ApiException.conflict("INVALID_ROOM_REQUIREMENT",
                    "目标房间要求的床位数不能少于冻结家庭成员数: " + frozenSize);
        }

        List<TransferMemberSnapshot> snapshots = staying.stream()
                .map(TransferMemberSnapshot::new).toList();
        TransferApplication application = new TransferApplication(
                request.transferNo(), fingerprint, household.getHouseholdNo(),
                originStay.getShelter().getId(), request.targetShelterId(),
                originStay.getId(), originStay.getRoom().getId(), snapshots,
                requiredBeds, accessibleRequired, request.plannedArrivalAt());
        transferApplications.saveAndFlush(application);
        return applicationResponse(application, HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------
    // 阶段一后半：目标安置点接受（预留房间，不动来源床位）
    // ------------------------------------------------------------------

    @Transactional
    public IdempotentResponse accept(AcceptTransferRequest request) {
        String idemKey = operationKey("accept", request.transferNo());
        String fingerprint = fingerprint("ACCEPT_TRANSFER", request.transferNo());
        Optional<IdempotencyRecord> idemExisting = idempotency.find(idemKey);
        if (idemExisting.isPresent()) {
            return idempotency.replay(idemExisting.get(), fingerprint);
        }

        // 锁序与全系统一致：先家庭行，再申请单行
        Household household = lockHouseholdOf(mustExist(request.transferNo()));
        TransferApplication application = lockApplication(request.transferNo());

        idemExisting = idempotency.find(idemKey);
        if (idemExisting.isPresent()) {
            return idempotency.replay(idemExisting.get(), fingerprint);
        }

        switch (application.getStatus()) {
            case ACCEPTED -> {
                // 重复接受：幂等返回当前待交接状态（预留仍然有效）
                return idempotency.save(idemKey, fingerprint, HttpStatus.OK.value(),
                        toMap(application));
            }
            case REQUESTED -> {
                // 继续校验
            }
            default -> throw alreadyFinished(application);
        }

        // 接受前重新核对冻结清单：来源入住必须仍有效且与冻结一致、成员不得有变化
        verifyFrozenHousehold(application, household, application.getOriginStayId());

        // 预留房间：容量与无障碍按申请冻结要求。只锁目标安置点房间——
        // 接受阶段不触碰来源床位，无需锁来源房间，避免跨安置点无谓争用
        Room targetRoom = roomAllocator
                .reserve(application.getTargetShelterId(), application.getRequiredBedCount(),
                        application.isRequiresAccessible(), List.of())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM",
                        "目标安置点没有满足容量和无障碍条件的可预留房间"));

        targetRoom.setReservedBeds(targetRoom.getReservedBeds() + application.getRequiredBedCount());
        application.markAccepted(targetRoom.getId());
        return idempotency.save(idemKey, fingerprint, HttpStatus.OK.value(), toMap(application));
    }

    // ------------------------------------------------------------------
    // 阶段二：到达确认（一次性结束来源入住、启用目标入住、迁移全部成员）
    // ------------------------------------------------------------------

    @Transactional
    public IdempotentResponse arrive(ArriveTransferRequest request) {
        // 交接事件号是到达确认的幂等键，加命名空间避免与其它操作的幂等键混用
        String idemKey = operationKey("handover", request.handoverNo());
        String fingerprint = fingerprint("ARRIVE_TRANSFER", request.transferNo(),
                request.handoverNo(), request.expectedOriginStayId());
        Optional<IdempotencyRecord> idemExisting = idempotency.find(idemKey);
        if (idemExisting.isPresent()) {
            return idempotency.replay(idemExisting.get(), fingerprint);
        }

        Household household = lockHouseholdOf(mustExist(request.transferNo()));
        TransferApplication application = lockApplication(request.transferNo());

        idemExisting = idempotency.find(idemKey);
        if (idemExisting.isPresent()) {
            return idempotency.replay(idemExisting.get(), fingerprint);
        }

        switch (application.getStatus()) {
            case COMPLETED -> throw ApiException.conflict("TRANSFER_ALREADY_COMPLETED",
                    "转移已完成，不能撤销；如需返回请发起新的反向转移，结果入住 ID: "
                            + application.getResultStayId());
            case ACCEPTED -> {
                // 继续
            }
            case REQUESTED -> throw ApiException.conflict("TRANSFER_NOT_ACCEPTED",
                    "目标安置点尚未接受转移，不能确认到达");
            default -> throw alreadyFinished(application);
        }

        // —— 到达确认重新核对冻结清单：退住/合并/并发到达造成任何变化都使旧确认失败 ——
        Stay originStay = verifyFrozenHousehold(application, household, request.expectedOriginStayId());
        if (!application.getOriginStayId().equals(request.expectedOriginStayId())) {
            throw ApiException.conflict("STALE_STATE", "期望来源入住 ID 与冻结的入住不一致，请刷新后重试");
        }

        int frozenSize = application.getMembers().size();
        // 来源/目标房间按主键升序一起加锁，锁序与合并、即时转移一致，避免跨家庭竞争死锁
        List<Room> lockedRooms = roomAllocator.lockByIds(
                List.of(application.getOriginRoomId(), application.getReservedRoomId()));
        Room originRoom = lockedRooms.stream()
                .filter(r -> r.getId().equals(application.getOriginRoomId())).findFirst().orElseThrow();
        Room targetRoom = lockedRooms.stream()
                .filter(r -> r.getId().equals(application.getReservedRoomId())).findFirst().orElseThrow();
        Shelter targetShelter = targetRoom.getShelter();

        // 同一事务内完成全部改动，失败整体回滚：
        // 不会出现成员留在两处，也不会出现来源床位已释放但目标入住未建立
        originRoom.setOccupied(originRoom.getOccupied() - frozenSize);
        // 预留转为实际占用：预留减、占用加，净占用 +frozenSize
        targetRoom.setReservedBeds(targetRoom.getReservedBeds() - frozenSize);
        targetRoom.setOccupied(targetRoom.getOccupied() + frozenSize);

        originStay.end();
        Stay newStay = stays.save(new Stay(household, targetRoom, targetShelter, frozenSize));

        stayEvents.save(StayEvent.transferArrived(newStay, originStay,
                application.getTransferNo(), request.handoverNo()));
        for (Member member : stayingMembers(household)) {
            memberEvents.save(MemberEvent.transferred(member, originStay, newStay,
                    application.getTransferNo(), request.handoverNo()));
        }

        application.markArrived(request.handoverNo(), newStay.getId());
        return idempotency.save(idemKey, fingerprint, HttpStatus.OK.value(),
                arrivedBody(application, newStay));
    }

    // ------------------------------------------------------------------
    // 终结：目标拒绝 / 家庭取消 / 超时（释放预留，来源入住保持有效）
    // ------------------------------------------------------------------

    @Transactional
    public IdempotentResponse reject(RejectTransferRequest request) {
        return terminate(request.transferNo(), "reject",
                TransferStatus.REJECTED, request.reason());
    }

    @Transactional
    public IdempotentResponse cancel(CancelTransferRequest request) {
        return terminate(request.transferNo(), "cancel",
                TransferStatus.CANCELLED, request.reason());
    }

    private IdempotentResponse terminate(String transferNo, String operation,
                                         TransferStatus terminal, String reason) {
        // 拒绝/取消与接受共用业务号但属于不同操作，按操作加命名空间做幂等
        String idemKey = operationKey(operation, transferNo);
        String fingerprint = fingerprint(operation.toUpperCase(java.util.Locale.ROOT) + "_TRANSFER",
                transferNo);
        Optional<IdempotencyRecord> idemExisting = idempotency.find(idemKey);
        if (idemExisting.isPresent()) {
            return idempotency.replay(idemExisting.get(), fingerprint);
        }

        TransferApplication preview = mustExist(transferNo);
        lockHouseholdOf(preview);
        TransferApplication application = lockApplication(transferNo);
        idemExisting = idempotency.find(idemKey);
        if (idemExisting.isPresent()) {
            return idempotency.replay(idemExisting.get(), fingerprint);
        }

        TransferStatus current = application.getStatus();
        if (current == terminal) {
            return idempotency.save(idemKey, fingerprint, HttpStatus.OK.value(), toMap(application));
        }
        if (current == TransferStatus.COMPLETED) {
            throw ApiException.conflict("TRANSFER_ALREADY_COMPLETED",
                    "已完成的转移不能撤销，只能发起新的反向转移");
        }
        if (!current.isActive()) {
            throw alreadyFinished(application);
        }
        // REQUESTED 没有预留；ACCEPTED 需要释放目标预留房间，来源入住始终不动
        if (current == TransferStatus.ACCEPTED && application.getReservedRoomId() != null) {
            releaseReservation(application);
        }
        switch (terminal) {
            case REJECTED -> application.markRejected();
            case CANCELLED -> application.markCancelled();
            default -> throw new IllegalStateException("unsupported terminal status " + terminal);
        }
        application.setEndReason(reason);
        return idempotency.save(idemKey, fingerprint, HttpStatus.OK.value(), toMap(application));
    }

    /**
     * 超时终结：把所有“已接受且计划到达时间已过”的活动申请标记为 TIMED_OUT 并释放预留。
     * 由调度器或管理接口按周期调用；逐条独立提交，单条失败不影响其它申请。
     * 返回本次超时终结的业务号列表。
     */
    @Transactional
    public List<String> timeoutOverdue(Instant now) {
        List<TransferApplication> overdue = transferApplications.findOverdueAccepted(now);
        List<String> timedOut = new ArrayList<>();
        for (TransferApplication application : overdue) {
            // 每条加锁后再次确认状态，避免与并发到达/取消冲突
            TransferApplication locked =
                    transferApplications.findByTransferNoForUpdate(application.getTransferNo()).orElseThrow();
            if (locked.getStatus() == TransferStatus.ACCEPTED
                    && locked.getPlannedArrivalAt().isBefore(now) && locked.getReservedRoomId() != null) {
                releaseReservation(locked);
                locked.markTimedOut();
                locked.setEndReason("超过计划到达时间未交接，系统自动释放预留");
                timedOut.add(locked.getTransferNo());
            }
        }
        return timedOut;
    }

    private void releaseReservation(TransferApplication application) {
        Room room = rooms.findByIdForUpdate(application.getReservedRoomId()).orElseThrow();
        room.setReservedBeds(room.getReservedBeds() - application.getRequiredBedCount());
    }

    /**
     * 供退住/即时转移在已持有家庭行锁的同事务内调用：把该家庭的活动转移申请全部终结，
     * 已接受的释放预留，避免家庭退住/迁走后目标房间悬挂预留。
     *
     * <p>{@code roomIdsAlsoLocked} 是调用方接下来要修改占用数的房间（如来源房间），
     * 与全部预留房间合并后一次按主键升序加锁，保证与并发到达确认的锁序完全一致，
     * 不会跨家庭形成锁序倒置死锁。
     */
    @Transactional
    public void cancelActiveForHousehold(String householdNo, String reason,
                                         java.util.Collection<Long> roomIdsAlsoLocked) {
        List<TransferApplication> actives =
                transferApplications.findActiveByHousehold(householdNo, ACTIVE_STATUSES);
        if (actives.isEmpty()) {
            return;
        }
        java.util.Set<Long> roomIds = new java.util.TreeSet<>(roomIdsAlsoLocked);
        for (TransferApplication active : actives) {
            TransferApplication locked =
                    transferApplications.findByTransferNoForUpdate(active.getTransferNo()).orElseThrow();
            if (locked.getStatus() == TransferStatus.ACCEPTED && locked.getReservedRoomId() != null) {
                roomIds.add(locked.getReservedRoomId());
            }
        }
        // 先一次性按主键升序锁定所有涉及房间，再逐单终结并释放预留
        Map<Long, Room> lockedRooms = new LinkedHashMap<>();
        roomAllocator.lockByIds(roomIds).forEach(r -> lockedRooms.put(r.getId(), r));
        for (TransferApplication active : actives) {
            TransferApplication application =
                    transferApplications.findByTransferNoForUpdate(active.getTransferNo()).orElseThrow();
            if (!application.getStatus().isActive()) {
                continue;
            }
            if (application.getStatus() == TransferStatus.ACCEPTED
                    && application.getReservedRoomId() != null) {
                Room reserved = lockedRooms.get(application.getReservedRoomId());
                reserved.setReservedBeds(reserved.getReservedBeds() - application.getRequiredBedCount());
            }
            application.markCancelled();
            application.setEndReason(reason);
        }
    }

    /** 供合并申请创建调用：任一参与家庭存在活动转移都拒绝（无锁快速检查，家庭行已由调用方锁定） */
    @Transactional(readOnly = true)
    public void assertNoActiveTransfer(Set<String> householdNos) {
        for (String householdNo : householdNos) {
            List<TransferApplication> actives =
                    transferApplications.findActiveByHousehold(householdNo, ACTIVE_STATUSES);
            if (!actives.isEmpty()) {
                throw ApiException.conflict("ACTIVE_TRANSFER_EXISTS",
                        "家庭存在进行中的跨安置点转移，不能发起团聚: " + householdNo);
            }
        }
    }

    /** 供即时转移调用：返回该家庭的活动转移（调用方已持有家庭行锁） */
    @Transactional(readOnly = true)
    public List<TransferApplication> activeTransfersOf(String householdNo) {
        return transferApplications.findActiveByHousehold(householdNo, ACTIVE_STATUSES);
    }

    // ------------------------------------------------------------------
    // 查询：进度、两端占用、成员交接清单、跨安置点入住时间线
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TransferApplicationResponse progress(String transferNo) {
        TransferApplication application = transferApplications.findByTransferNo(transferNo)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + transferNo));
        return responseOf(application);
    }

    /** 成员交接清单：冻结成员 + 当前实际在住状态，标记是否与冻结一致（到达确认失败时据此排查） */
    @Transactional(readOnly = true)
    public Map<String, Object> handoverList(String transferNo) {
        TransferApplication application = mustExist(transferNo);
        List<Map<String, Object>> frozen = new ArrayList<>();
        boolean matches = true;
        if (households.findByHouseholdNo(application.getHouseholdNo()).isPresent()) {
            Household household = households.findByHouseholdNo(application.getHouseholdNo()).orElseThrow();
            Map<Long, Member> currentById = new LinkedHashMap<>();
            household.getMembers().stream()
                    .filter(Member::isCurrentlyStaying)
                    .forEach(m -> currentById.put(m.getId(), m));
            Set<Long> frozenIds = new HashSet<>();
            for (TransferMemberSnapshot snapshot : application.getMembers()) {
                frozenIds.add(snapshot.getMemberId());
                Member current = currentById.get(snapshot.getMemberId());
                boolean same = current != null
                        && current.getVerificationStatus() == snapshot.getVerificationStatus();
                matches &= same;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("memberId", snapshot.getMemberId());
                row.put("identityNo", snapshot.getIdentityNo());
                row.put("frozenVerificationStatus", snapshot.getVerificationStatus().name());
                row.put("needsAccessible", snapshot.isNeedsAccessible());
                row.put("currentlyStaying", current != null);
                row.put("currentVerificationStatus",
                        current == null ? null : current.getVerificationStatus().name());
                row.put("matchesFrozen", same);
                frozen.add(row);
            }
            matches &= frozenIds.equals(currentById.keySet());
        } else {
            matches = false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", application.getTransferNo());
        body.put("status", application.getStatus().name());
        body.put("householdNo", application.getHouseholdNo());
        body.put("memberCount", application.getMembers().size());
        body.put("frozenListMatchesCurrent", matches);
        body.put("members", frozen);
        return body;
    }

    /** 跨安置点入住时间线：按时间排列该家庭所有入住事件（入住/转移/合并/退住） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stayTimeline(String householdNo) {
        if (households.findByHouseholdNo(householdNo).isEmpty()) {
            throw ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNo);
        }
        List<Map<String, Object>> timeline = new ArrayList<>();
        stayEvents.findByHouseholdNoOrderByIdAsc(householdNo).forEach(event -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", event.getId());
            row.put("type", event.getType().name());
            row.put("occurredAt", event.getOccurredAt());
            row.put("fromShelterId", event.getFromShelterId());
            row.put("fromRoomId", event.getFromRoomId());
            row.put("fromRoomNumber", event.getFromRoomNumber());
            row.put("toShelterId", event.getToShelterId());
            row.put("toRoomId", event.getToRoomId());
            row.put("toRoomNumber", event.getToRoomNumber());
            row.put("memberCount", event.getMemberCount());
            row.put("transferNo", event.getTransferNo());
            row.put("handoverNo", event.getHandoverNo());
            row.put("mergeNo", event.getMergeNo());
            timeline.add(row);
        });
        return timeline;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private TransferApplication mustExist(String transferNo) {
        return transferApplications.findByTransferNo(transferNo)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + transferNo));
    }

    private TransferApplication lockApplication(String transferNo) {
        return transferApplications.findByTransferNoForUpdate(transferNo)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + transferNo));
    }

    /** 按转移申请锁定其家庭行（全系统锁序起点：家庭行 → 申请单行 → 房间行） */
    private Household lockHouseholdOf(TransferApplication application) {
        return households.findByHouseholdNoForUpdate(application.getHouseholdNo()).orElseThrow();
    }

    /**
     * 业务号/交接事件号在不同操作中可能复用，按操作类型加命名空间作为幂等键：
     * 例如同一 transferNo 的接受、拒绝、取消互不串号；交接事件号独占 handover 空间。
     */
    private String operationKey(String operation, String businessNo) {
        return operation + ":" + businessNo;
    }

    /**
     * 到达确认/接受时重新核对冻结清单：
     * 1. 来源入住仍存在且仍是该家庭当前唯一有效入住；
     * 2. 当前有效入住 ID 等于冻结的来源入住 ID（未被即时转移/合并替换）；
     * 3. 在住成员集合与冻结清单逐项一致（成员 ID、在住状态、核验状态）。
     * 任一不满足直接抛 STALE_STATE/FROZEN_LIST_CHANGED，旧确认失败且不改任何数据。
     */
    private Stay verifyFrozenHousehold(TransferApplication application, Household household,
                                       Long expectedStayId) {
        Stay currentStay = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY",
                        "来源入住已结束（家庭已退住或已迁移），冻结清单失效: " + household.getHouseholdNo()));
        if (!currentStay.getId().equals(application.getOriginStayId())) {
            throw ApiException.conflict("STALE_STATE",
                    "家庭入住已被其它操作改变，旧的转移确认失败，请刷新后重试");
        }
        if (expectedStayId != null && !expectedStayId.equals(currentStay.getId())) {
            throw ApiException.conflict("STALE_STATE", "期望来源入住 ID 与当前有效入住不一致");
        }

        List<Member> staying = stayingMembers(household);
        Map<Long, Member> currentById = new LinkedHashMap<>();
        staying.forEach(m -> currentById.put(m.getId(), m));
        Set<Long> frozenIds = new HashSet<>();
        for (TransferMemberSnapshot snapshot : application.getMembers()) {
            frozenIds.add(snapshot.getMemberId());
            Member current = currentById.get(snapshot.getMemberId());
            if (current == null) {
                throw ApiException.conflict("FROZEN_LIST_CHANGED",
                        "冻结成员已退出或迁出，旧的转移确认失败: " + snapshot.getIdentityNo());
            }
            if (current.getVerificationStatus() != snapshot.getVerificationStatus()) {
                throw ApiException.conflict("FROZEN_LIST_CHANGED",
                        "冻结成员身份核验状态发生变化，旧的转移确认失败: " + snapshot.getIdentityNo());
            }
        }
        if (!frozenIds.equals(currentById.keySet())) {
            throw ApiException.conflict("FROZEN_LIST_CHANGED",
                    "家庭新增了在住成员，冻结清单与当前成员不一致，旧的转移确认失败");
        }
        if (currentStay.getMemberCount() != frozenIds.size()) {
            throw ApiException.conflict("FROZEN_LIST_CHANGED",
                    "来源入住人数与冻结成员数不一致，旧的转移确认失败");
        }
        return currentStay;
    }

    private void ensureNoPendingMerge(Set<String> householdNos) {
        List<MergeApplication> pending = mergeApplications.findByStatus(MergeStatus.PENDING);
        for (MergeApplication application : pending) {
            if (householdNos.contains(application.getTargetHouseholdNo())
                    || application.getTemporaryHouseholdNos().stream().anyMatch(householdNos::contains)) {
                throw ApiException.conflict("PENDING_MERGE_EXISTS",
                        "家庭存在未完成的团聚申请，不能发起整体转移: " + application.getMergeNo());
            }
        }
    }

    private List<Member> stayingMembers(Household household) {
        return household.getMembers().stream()
                .filter(Member::isCurrentlyStaying)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private ApiException alreadyFinished(TransferApplication application) {
        return ApiException.conflict("TRANSFER_ALREADY_FINISHED",
                "转移申请已终结（状态 " + application.getStatus().name() + "），不能重复操作");
    }

    private IdempotentResponse replayApplication(TransferApplication application, String fingerprint) {
        if (!application.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同业务号携带了不同的申请内容");
        }
        return applicationResponse(application, HttpStatus.CREATED);
    }

    private IdempotentResponse applicationResponse(TransferApplication application, HttpStatus status) {
        return new IdempotentResponse(status.value(), idempotency.toJson(responseOf(application)));
    }

    private Map<String, Object> toMap(TransferApplication application) {
        TransferApplicationResponse r = responseOf(application);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", r.transferNo());
        body.put("status", r.status());
        body.put("householdNo", r.householdNo());
        body.put("originShelterId", r.originShelterId());
        body.put("targetShelterId", r.targetShelterId());
        body.put("originStayId", r.originStayId());
        body.put("originRoomId", r.originRoomId());
        body.put("reservedRoomId", r.reservedRoomId());
        body.put("targetRoomId", r.targetRoomId());
        body.put("targetRoomNumber", r.targetRoomNumber());
        body.put("targetReservedBeds", r.targetReservedBeds());
        body.put("requiredBedCount", r.requiredBedCount());
        body.put("requiresAccessible", r.requiresAccessible());
        body.put("plannedArrivalAt", r.plannedArrivalAt());
        body.put("handoverNo", r.handoverNo());
        body.put("resultStayId", r.resultStayId());
        body.put("endReason", r.endReason());
        body.put("members", r.members());
        return body;
    }

    private Map<String, Object> arrivedBody(TransferApplication application, Stay stay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", application.getTransferNo());
        body.put("handoverNo", application.getHandoverNo());
        body.put("status", application.getStatus().name());
        body.put("stayId", stay.getId());
        body.put("householdNo", stay.getHousehold().getHouseholdNo());
        body.put("shelterId", stay.getShelter().getId());
        body.put("roomId", stay.getRoom().getId());
        body.put("roomNumber", stay.getRoom().getRoomNumber());
        body.put("memberCount", stay.getMemberCount());
        return body;
    }

    private TransferApplicationResponse responseOf(TransferApplication application) {
        Room originRoom = rooms.findById(application.getOriginRoomId()).orElse(null);
        // 优先展示活动预留房间，终结后回退到历史预留房间
        Long targetRoomId = application.getReservedRoomId() != null
                ? application.getReservedRoomId()
                : application.getTargetRoomId();
        Room targetRoom = targetRoomId == null ? null : rooms.findById(targetRoomId).orElse(null);
        return TransferApplicationResponse.of(application, originRoom, targetRoom);
    }

    private String fingerprint(String operation, Object... parts) {
        StringBuilder builder = new StringBuilder(operation);
        for (Object part : parts) {
            builder.append('|').append(part);
        }
        return builder.toString();
    }
}
