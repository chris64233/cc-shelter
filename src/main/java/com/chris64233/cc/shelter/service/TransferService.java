package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.FrozenMember;
import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.IdempotencyRecord;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.MemberEvent;
import com.chris64233.cc.shelter.domain.MergeStatus;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.domain.TransferActivitySlot;
import com.chris64233.cc.shelter.domain.TransferApplication;
import com.chris64233.cc.shelter.domain.TransferRoomReservation;
import com.chris64233.cc.shelter.domain.TransferStatus;
import com.chris64233.cc.shelter.domain.VerificationStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.MergeApplicationRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.repo.TransferActivitySlotRepository;
import com.chris64233.cc.shelter.repo.TransferApplicationRepository;
import com.chris64233.cc.shelter.repo.TransferRoomReservationRepository;
import com.chris64233.cc.shelter.web.dto.AcceptTransferRequest;
import com.chris64233.cc.shelter.web.dto.ArriveTransferRequest;
import com.chris64233.cc.shelter.web.dto.CancelTransferRequest;
import com.chris64233.cc.shelter.web.dto.CreateTransferRequest;
import com.chris64233.cc.shelter.web.dto.ExpireTransferRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.RejectTransferRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家庭跨安置点整体转移（三阶段）：
 *
 * <ol>
 *   <li>申请（{@link TransferStatus#REQUESTED}）：冻结家庭成员与当前入住关系；
 *       存在未完成团聚申请、成员身份未确认或家庭已退住时拒绝。</li>
 *   <li>接受（{@link TransferStatus#ACCEPTED}）：目标安置点为完整家庭预留一间满足容量与
 *       无障碍条件的房间；只形成待交接，绝不提前释放来源床位。</li>
 *   <li>到达确认（{@link TransferStatus#ARRIVED}）：重新核对冻结清单后，在同一事务内
 *       一次性结束来源入住、启用目标入住并完成全部成员交接。</li>
 * </ol>
 *
 * <p>拒绝/取消/超时释放目标预留并保持来源入住有效；已完成的转移不可撤销，
 * 只能再发起一笔反向转移。并发与退住、合并、即时转移由“家庭行 → 房间行（按主键升序）”
 * 的统一锁序串行化。
 */
@Service
public class TransferService {

    /** 交接事件号在通用幂等表中的键前缀，避免与其它幂等键空间冲突 */
    private static final String HANDOVER_KEY_PREFIX = "HANDOVER:";

    private final HouseholdRepository households;
    private final ShelterRepository shelters;
    private final StayRepository stays;
    private final StayEventRepository stayEvents;
    private final MemberEventRepository memberEvents;
    private final MergeApplicationRepository mergeApplications;
    private final TransferApplicationRepository transferApplications;
    private final TransferActivitySlotRepository activitySlots;
    private final TransferRoomReservationRepository reservations;
    private final RoomAllocator roomAllocator;
    private final IdempotencyStore idempotency;
    private final org.springframework.transaction.support.TransactionTemplate expireTemplate;

    public TransferService(HouseholdRepository households, ShelterRepository shelters,
                           StayRepository stays, StayEventRepository stayEvents,
                           MemberEventRepository memberEvents,
                           MergeApplicationRepository mergeApplications,
                           TransferApplicationRepository transferApplications,
                           TransferActivitySlotRepository activitySlots,
                           TransferRoomReservationRepository reservations,
                           RoomAllocator roomAllocator, IdempotencyStore idempotency,
                           org.springframework.transaction.PlatformTransactionManager txManager) {
        this.households = households;
        this.shelters = shelters;
        this.stays = stays;
        this.stayEvents = stayEvents;
        this.memberEvents = memberEvents;
        this.mergeApplications = mergeApplications;
        this.transferApplications = transferApplications;
        this.activitySlots = activitySlots;
        this.reservations = reservations;
        this.roomAllocator = roomAllocator;
        this.idempotency = idempotency;
        this.expireTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    /** 阶段一：创建转移申请，冻结家庭成员与来源入住关系 */
    @Transactional
    public IdempotentResponse create(CreateTransferRequest request) {
        String fingerprint = fingerprint("CREATE_TRANSFER", request.transferNo(), request.householdNo(),
                request.targetShelterId(), request.targetRoomNumber(),
                request.plannedArrivalAt(), request.externalBusinessNo());

        Optional<TransferApplication> existing = transferApplications.findByTransferNo(request.transferNo());
        if (existing.isPresent()) {
            return replayApplication(existing.get(), fingerprint);
        }

        // 先锁家庭行（与入住/退住/合并/即时转移相同的锁序起点），再复核业务号与并发条件
        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND",
                        "家庭不存在: " + request.householdNo()));
        existing = transferApplications.findByTransferNo(request.transferNo());
        if (existing.isPresent()) {
            return replayApplication(existing.get(), fingerprint);
        }

        if (household.isTemporary()) {
            throw ApiException.conflict("NOT_FORMAL_HOUSEHOLD",
                    "转移申请必须以正式家庭为单位: " + request.householdNo());
        }
        if (activitySlots.existsById(household.getHouseholdNo())) {
            throw ApiException.conflict("TRANSFER_IN_PROGRESS",
                    "家庭已有一笔进行中的转移，不能重复发起: " + request.householdNo());
        }
        if (mergeApplications.existsByHouseholdNoAndStatus(
                household.getHouseholdNo(), MergeStatus.PENDING)) {
            throw ApiException.conflict("MERGE_IN_PROGRESS",
                    "家庭存在未完成的团聚申请，不能发起转移: " + request.householdNo());
        }

        Stay originStay = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY",
                        "家庭已退住或当前没有有效入住，不能发起转移"));
        List<Member> staying = stayingMembers(household);
        if (staying.isEmpty()) {
            throw ApiException.conflict("NO_STAYING_MEMBER", "家庭没有在住成员，不能发起转移");
        }
        if (staying.size() != originStay.getMemberCount()) {
            throw ApiException.conflict("STALE_STATE", "在住成员数与当前入住记录不一致，请刷新后重试");
        }
        if (staying.stream().anyMatch(m -> m.getVerificationStatus() == VerificationStatus.UNVERIFIED)) {
            throw ApiException.conflict("IDENTITY_NOT_VERIFIED",
                    "存在身份尚未确认的成员，不能发起转移");
        }

        Shelter target = shelters.findById(request.targetShelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND",
                        "目标安置点不存在: " + request.targetShelterId()));
        if (target.getId().equals(originStay.getShelter().getId())) {
            throw ApiException.conflict("SAME_SHELTER", "目标安置点与来源安置点相同");
        }

        List<FrozenMember> frozen = staying.stream().map(FrozenMember::of).toList();
        // 时间统一到毫秒，避免 H2 微秒精度导致重放响应与首次响应不一致
        Instant plannedArrivalAt = request.plannedArrivalAt().truncatedTo(ChronoUnit.MILLIS);
        TransferApplication application = new TransferApplication(
                request.transferNo(), fingerprint, household.getHouseholdNo(),
                originStay.getShelter().getId(), target.getId(), request.targetRoomNumber(),
                frozen, household.needsAccessibleRoom(),
                originStay.getId(), originStay.getRoom().getId(),
                plannedArrivalAt, request.externalBusinessNo(), plannedArrivalAt);
        transferApplications.saveAndFlush(application);
        // 活动槽位：主键兜底保证同一家庭同时只有一笔活动转移
        activitySlots.saveAndFlush(new TransferActivitySlot(household.getHouseholdNo(), application.getId()));
        return created(application);
    }

    /** 阶段二：目标安置点接受并预留房间，只形成待交接，不触碰来源床位 */
    @Transactional
    public IdempotentResponse accept(AcceptTransferRequest request) {
        String fingerprint = fingerprint("ACCEPT_TRANSFER", request.transferNo());
        Optional<IdempotencyRecord> existingIdem = idempotency.find(request.idempotencyKey());
        if (existingIdem.isPresent()) {
            return idempotency.replay(existingIdem.get(), fingerprint);
        }

        TransferApplication application = getActiveOrReplayable(request.transferNo());
        Household household = lockHousehold(application);
        application = transferApplications.findByTransferNoForUpdate(request.transferNo()).orElseThrow();

        existingIdem = idempotency.find(request.idempotencyKey());
        if (existingIdem.isPresent()) {
            return idempotency.replay(existingIdem.get(), fingerprint);
        }

        if (application.getStatus() == TransferStatus.ACCEPTED) {
            throw ApiException.conflict("TRANSFER_ALREADY_ACCEPTED", "转移已被接受，等待到达交接");
        }
        if (application.getStatus() == TransferStatus.ARRIVED) {
            throw ApiException.conflict("TRANSFER_ALREADY_COMPLETED", "转移已到达完成，不能重复接受");
        }
        if (application.getStatus() != TransferStatus.REQUESTED) {
            throw ApiException.conflict("TRANSFER_NOT_ACTIVE",
                    "转移已结束（" + application.getStatus() + "），不能接受");
        }

        // 接受前再次确认来源入住仍有效、冻结清单未变化——家庭必须保持完整
        verifyOriginStayAndFrozenList(application, household, null);

        Optional<Room> chosen;
        if (application.getTargetRoomNumber() != null) {
            chosen = roomAllocator.reserveSpecific(application.getTargetShelterId(),
                    application.getTargetRoomNumber(), application.getFrozenMemberCount(),
                    application.isFrozenAccessibleRequired(), Map.of(), List.of());
        } else {
            chosen = roomAllocator.reserve(application.getTargetShelterId(),
                    application.getFrozenMemberCount(), application.isFrozenAccessibleRequired());
        }
        Room targetRoom = chosen.orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM",
                "目标安置点没有满足容量和无障碍条件的可预留房间"));

        // 预留只占名额不动 occupied；room_id 唯一约束兜底防止两个家庭重复预留同一房间
        reservations.saveAndFlush(new TransferRoomReservation(application.getId(),
                targetRoom.getId(), application.getTargetShelterId(),
                application.getHouseholdNo(), application.getFrozenMemberCount()));
        application.markAccepted(targetRoom.getId());
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.OK.value(),
                applicationBody(application, false));
    }

    /** 阶段三：到达确认——重核冻结清单后原子切换两端入住，完成全部成员交接 */
    @Transactional
    public IdempotentResponse arrive(ArriveTransferRequest request) {
        String idemKey = HANDOVER_KEY_PREFIX + request.handoverNo();
        String fingerprint = fingerprint("ARRIVE_TRANSFER", request.transferNo(),
                request.expectedOriginStayId());
        Optional<IdempotencyRecord> existingIdem = idempotency.find(idemKey);
        if (existingIdem.isPresent()) {
            return idempotency.replay(existingIdem.get(), fingerprint);
        }

        TransferApplication application = transferApplications.findByTransferNo(request.transferNo())
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + request.transferNo()));
        Household household = lockHousehold(application);
        application = transferApplications.findByTransferNoForUpdate(request.transferNo()).orElseThrow();

        existingIdem = idempotency.find(idemKey);
        if (existingIdem.isPresent()) {
            return idempotency.replay(existingIdem.get(), fingerprint);
        }

        if (application.getStatus() == TransferStatus.ARRIVED) {
            throw ApiException.conflict("TRANSFER_ALREADY_COMPLETED",
                    "转移已完成，不能重复交接；如需返回请发起反向转移");
        }
        if (application.getStatus() == TransferStatus.REQUESTED) {
            throw ApiException.conflict("TRANSFER_NOT_ACCEPTED", "目标安置点尚未接受转移，不能交接");
        }
        if (application.getStatus() != TransferStatus.ACCEPTED) {
            throw ApiException.conflict("TRANSFER_NOT_ACTIVE",
                    "转移已结束（" + application.getStatus() + "），不能交接");
        }

        TransferRoomReservation reservation =
                reservations.findByTransferApplicationId(application.getId())
                        .orElseThrow(() -> ApiException.conflict("ROOM_RESERVATION_MISSING",
                                "目标房间预留不存在，无法交接"));

        // 锁内重核：来源入住有效且与冻结时一致；冻结成员清单逐项一致
        Stay originStay = verifyOriginStayAndFrozenList(
                application, household, request.expectedOriginStayId());

        // 两端房间按主键全局升序加锁后再改占用，锁序与入住/退住/合并一致
        List<Room> locked = roomAllocator.lockByIds(
                List.of(originStay.getRoom().getId(), reservation.getRoomId()));
        Room originRoom = roomById(locked, originStay.getRoom().getId());
        Room targetRoom = roomById(locked, reservation.getRoomId());

        if (application.isFrozenAccessibleRequired() && !targetRoom.isAccessible()) {
            throw ApiException.conflict("ACCESSIBILITY_NOT_MET", "预留房间不满足无障碍条件");
        }
        // 扣除本笔预留后，目标房间仍需容下整个家庭（防御配置变化与并发占用）
        final Long thisApplicationId = application.getId();
        int heldByOthers = reservations.findByShelterId(application.getTargetShelterId()).stream()
                .filter(r -> r.getRoomId().equals(targetRoom.getId()))
                .filter(r -> !r.getTransferApplicationId().equals(thisApplicationId))
                .mapToInt(TransferRoomReservation::getBedCount).sum();
        if (targetRoom.getBedCount() - targetRoom.getOccupied() - heldByOthers
                < application.getFrozenMemberCount()) {
            throw ApiException.conflict("NO_SUITABLE_ROOM", "目标预留房间容量已不足，交接失败");
        }

        List<Member> staying = stayingMembers(household);
        Shelter targetShelter = targetRoom.getShelter();

        // —— 以下改动同一事务一次性提交：任一步失败整体回滚，不会出现成员留在两处，
        //    也不会出现来源床位已释放但目标入住未建立 ——
        originRoom.setOccupied(originRoom.getOccupied() - originStay.getMemberCount());
        targetRoom.setOccupied(targetRoom.getOccupied() + application.getFrozenMemberCount());
        originStay.end();
        Stay newStay = stays.save(new Stay(household, targetRoom, targetShelter,
                application.getFrozenMemberCount()));

        stayEvents.save(StayEvent.transfer(newStay, originStay, application.getTransferNo()));
        for (Member member : staying) {
            memberEvents.save(MemberEvent.transfer(member, originStay, newStay,
                    application.getTransferNo()));
        }

        reservations.deleteByTransferApplicationId(application.getId());
        application.markArrived(request.handoverNo(), newStay.getId());
        activitySlots.deleteById(application.getHouseholdNo());

        return idempotency.save(idemKey, fingerprint, HttpStatus.OK.value(),
                arrivalBody(application, newStay));
    }

    /** 目标拒绝：释放预留（如有），来源入住保持有效 */
    @Transactional
    public IdempotentResponse reject(RejectTransferRequest request) {
        return terminate(request.idempotencyKey(), request.transferNo(),
                "REJECT_TRANSFER", TransferStatus.REJECTED, false, null, request.reason());
    }

    /** 家庭取消：释放预留（如有），来源入住保持有效 */
    @Transactional
    public IdempotentResponse cancel(CancelTransferRequest request) {
        return terminate(request.idempotencyKey(), request.transferNo(),
                "CANCEL_TRANSFER", TransferStatus.CANCELLED, false, null, null);
    }

    /** 单笔超时：已过交接时限才允许，释放预留，来源入住保持有效 */
    @Transactional
    public IdempotentResponse expire(ExpireTransferRequest request) {
        return terminate(request.idempotencyKey(), request.transferNo(),
                "EXPIRE_TRANSFER", TransferStatus.EXPIRED, true, null, null);
    }

    /**
     * 扫描所有已过交接时限的活动转移并逐笔超时。每笔独立事务，
     * 一笔失败不影响其它笔；返回处理笔数。
     */
    public int expireDue() {
        List<TransferApplication> due = transferApplications.findDueForExpiry(Instant.now());
        int processed = 0;
        for (TransferApplication application : due) {
            // 单笔独立事务：加锁后再次确认仍活动且到期，避免与到达/取消竞争
            Boolean ended = expireTemplate.execute(status ->
                    terminateInTransaction(application.getId()));
            if (Boolean.TRUE.equals(ended)) {
                processed++;
            }
        }
        return processed;
    }

    /** 在调用方事务内完成单笔超时（不做幂等），供 {@link #expireDue()} 使用 */
    private Boolean terminateInTransaction(Long applicationId) {
        TransferApplication application = transferApplications.findById(applicationId).orElse(null);
        if (application == null || !application.isActive()
                || !application.isExpirable(Instant.now())) {
            return false;
        }
        // 与到达/取消/拒绝相同的锁序：先家庭行，再申请单行
        lockHousehold(application);
        application = transferApplications.findByIdForUpdate(applicationId).orElseThrow();
        if (!application.isActive() || !application.isExpirable(Instant.now())) {
            return false;
        }
        reservations.deleteByTransferApplicationId(application.getId());
        application.markExpired();
        activitySlots.deleteById(application.getHouseholdNo());
        return true;
    }

    private IdempotentResponse terminate(String idempotencyKey, String transferNo, String operation,
                                         TransferStatus terminal, boolean requireDue,
                                         Object unused, String rejectReason) {
        String fingerprint = fingerprint(operation, transferNo);
        Optional<IdempotencyRecord> existingIdem = idempotency.find(idempotencyKey);
        if (existingIdem.isPresent()) {
            return idempotency.replay(existingIdem.get(), fingerprint);
        }

        TransferApplication application = transferApplications.findByTransferNo(transferNo)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + transferNo));
        lockHousehold(application);
        application = transferApplications.findByTransferNoForUpdate(transferNo).orElseThrow();

        existingIdem = idempotency.find(idempotencyKey);
        if (existingIdem.isPresent()) {
            return idempotency.replay(existingIdem.get(), fingerprint);
        }

        if (application.getStatus() == TransferStatus.ARRIVED) {
            throw ApiException.conflict("TRANSFER_ALREADY_COMPLETED",
                    "转移已完成，不能" + terminal.label());
        }
        if (!application.isActive()) {
            throw ApiException.conflict("TRANSFER_NOT_ACTIVE",
                    "转移已结束（" + application.getStatus() + "），不能" + terminal.label());
        }
        if (requireDue && !application.isExpirable(Instant.now())) {
            throw ApiException.conflict("TRANSFER_NOT_DUE", "尚未超过交接时限，不能置为超时");
        }

        reservations.deleteByTransferApplicationId(application.getId());
        if (terminal == TransferStatus.REJECTED) {
            application.markRejected(rejectReason);
        } else if (terminal == TransferStatus.CANCELLED) {
            application.markCancelled();
        } else {
            application.markExpired();
        }
        activitySlots.deleteById(application.getHouseholdNo());
        return idempotency.save(idempotencyKey, fingerprint, HttpStatus.OK.value(),
                applicationBody(application, false));
    }

    /**
     * 在家庭行锁内重新核对：来源入住仍有效、期望入住 ID 一致、冻结清单与当前在住清单完全一致。
     * 任何变化（退住、成员退出、合并迁移、并发即时转移等）都使交接/接受失败。
     */
    private Stay verifyOriginStayAndFrozenList(TransferApplication application,
                                               Household household, Long expectedStayId) {
        Stay originStay = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY",
                        "来源入住已结束（家庭可能已退住或迁移），交接失败"));
        if (!originStay.getId().equals(application.getOriginStayId())
                || (expectedStayId != null && !originStay.getId().equals(expectedStayId))) {
            throw ApiException.conflict("STALE_STATE", "来源入住已变化，请刷新后重试");
        }

        List<Member> staying = stayingMembers(household);
        List<FrozenMember> frozen = application.getFrozenMembers();
        if (staying.size() != frozen.size()) {
            throw ApiException.conflict("FROZEN_LIST_CHANGED",
                    "冻结成员清单已变化（人数不一致），旧交接失败");
        }
        Map<String, Member> currentByIdentity = staying.stream()
                .collect(Collectors.toMap(Member::getIdentityNo, m -> m));
        for (FrozenMember frozenMember : frozen) {
            Member current = currentByIdentity.get(frozenMember.getIdentityNo());
            if (current == null || !frozenMember.matches(current)) {
                throw ApiException.conflict("FROZEN_LIST_CHANGED",
                        "冻结成员清单已变化（成员 " + frozenMember.getIdentityNo()
                                + " 不在住或信息不一致），旧交接失败");
            }
        }
        if (originStay.getMemberCount() != frozen.size()) {
            throw ApiException.conflict("FROZEN_LIST_CHANGED",
                    "来源入住人数与冻结清单不一致，旧交接失败");
        }
        return originStay;
    }

    private Household lockHousehold(TransferApplication application) {
        return households.findByHouseholdNoForUpdate(application.getHouseholdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND",
                        "家庭不存在: " + application.getHouseholdNo()));
    }

    private TransferApplication getActiveOrReplayable(String transferNo) {
        return transferApplications.findByTransferNo(transferNo)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + transferNo));
    }

    private List<Member> stayingMembers(Household household) {
        return household.getMembers().stream()
                .filter(Member::isCurrentlyStaying)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Room roomById(List<Room> rooms, Long id) {
        return rooms.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }

    private IdempotentResponse replayApplication(TransferApplication application, String fingerprint) {
        if (!application.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同转移业务号携带了不同的申请内容");
        }
        // 创建重放：仍处于申请中按首次的 201 返回，已流转到后续状态则回显当前状态（200）
        int status = application.getStatus() == TransferStatus.REQUESTED
                ? HttpStatus.CREATED.value() : HttpStatus.OK.value();
        return new IdempotentResponse(status, idempotency.toJson(applicationBody(application, true)));
    }

    private IdempotentResponse created(TransferApplication application) {
        return new IdempotentResponse(HttpStatus.CREATED.value(),
                idempotency.toJson(applicationBody(application, true)));
    }

    private Map<String, Object> applicationBody(TransferApplication application, boolean includeMembers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", application.getTransferNo());
        body.put("status", application.getStatus().name());
        body.put("householdNo", application.getHouseholdNo());
        body.put("originShelterId", application.getOriginShelterId());
        body.put("targetShelterId", application.getTargetShelterId());
        body.put("originStayId", application.getOriginStayId());
        body.put("originRoomId", application.getOriginRoomId());
        body.put("reservedRoomId", application.getReservedRoomId());
        body.put("frozenMemberCount", application.getFrozenMemberCount());
        body.put("accessibleRequired", application.isFrozenAccessibleRequired());
        body.put("plannedArrivalAt", application.getPlannedArrivalAt());
        body.put("externalBusinessNo", application.getExternalBusinessNo());
        body.put("expiresAt", application.getExpiresAt());
        body.put("resultStayId", application.getResultStayId());
        body.put("handoverNo", application.getHandoverNo());
        body.put("rejectReason", application.getRejectReason());
        body.put("createdAt", application.getCreatedAt());
        body.put("acceptedAt", application.getAcceptedAt());
        body.put("arrivedAt", application.getArrivedAt());
        body.put("finishedAt", application.getFinishedAt());
        if (includeMembers) {
            body.put("frozenMembers", application.getFrozenMembers().stream().map(m -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("identityNo", m.getIdentityNo());
                view.put("age", m.getAge());
                view.put("needsAccessible", m.isNeedsAccessible());
                view.put("verificationStatus", m.getVerificationStatus().name());
                return view;
            }).toList());
        }
        return body;
    }

    private Map<String, Object> arrivalBody(TransferApplication application, Stay newStay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", application.getTransferNo());
        body.put("status", application.getStatus().name());
        body.put("handoverNo", application.getHandoverNo());
        body.put("stayId", newStay.getId());
        body.put("householdNo", newStay.getHousehold().getHouseholdNo());
        body.put("shelterId", newStay.getShelter().getId());
        body.put("roomId", newStay.getRoom().getId());
        body.put("roomNumber", newStay.getRoom().getRoomNumber());
        body.put("memberCount", newStay.getMemberCount());
        return body;
    }

    private String fingerprint(String operation, Object... parts) {
        StringBuilder builder = new StringBuilder(operation);
        for (Object part : parts) {
            builder.append('|').append(part);
        }
        return builder.toString();
    }
}
