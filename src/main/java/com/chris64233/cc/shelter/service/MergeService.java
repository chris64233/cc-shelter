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
import com.chris64233.cc.shelter.domain.VerificationStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.MergeApplicationRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.ConfirmMergeRequest;
import com.chris64233.cc.shelter.web.dto.CreateMergeRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MergeService {

    private final HouseholdRepository households;
    private final ShelterRepository shelters;
    private final StayRepository stays;
    private final StayEventRepository stayEvents;
    private final MemberEventRepository memberEvents;
    private final MemberRepository members;
    private final MergeApplicationRepository mergeApplications;
    private final RoomAllocator roomAllocator;
    private final IdempotencyStore idempotency;

    public MergeService(HouseholdRepository households, ShelterRepository shelters,
                        StayRepository stays, StayEventRepository stayEvents,
                        MemberEventRepository memberEvents, MemberRepository members,
                        MergeApplicationRepository mergeApplications,
                        RoomAllocator roomAllocator, IdempotencyStore idempotency) {
        this.households = households;
        this.shelters = shelters;
        this.stays = stays;
        this.stayEvents = stayEvents;
        this.memberEvents = memberEvents;
        this.members = members;
        this.mergeApplications = mergeApplications;
        this.roomAllocator = roomAllocator;
        this.idempotency = idempotency;
    }

    /** 创建合并申请：冻结参与家庭名单与目标安置点，只做结构校验，不改动任何入住 */
    @Transactional
    public IdempotentResponse createApplication(CreateMergeRequest request) {
        List<String> tempNos = normalize(request.temporaryHouseholdNos());
        String fingerprint = fingerprint("CREATE_MERGE", request.targetHouseholdNo(),
                String.join(",", tempNos), request.targetShelterId());

        Optional<MergeApplication> existing = mergeApplications.findByMergeNo(request.mergeNo());
        if (existing.isPresent()) {
            return replayApplication(existing.get(), fingerprint);
        }

        // 先按家庭主键升序锁定参与家庭（与确认/转移/退住相同的锁序起点），再复核业务号
        List<String> allNos = new ArrayList<>(tempNos);
        allNos.add(request.targetHouseholdNo());
        List<Household> locked = households.findByHouseholdNoInForUpdateOrderByIdAsc(allNos);
        existing = mergeApplications.findByMergeNo(request.mergeNo());
        if (existing.isPresent()) {
            return replayApplication(existing.get(), fingerprint);
        }

        Household target = byNo(locked, request.targetHouseholdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND",
                        "正式家庭不存在: " + request.targetHouseholdNo()));
        // 注意：活动转移期间不拦截团聚创建——合并确认与到达确认并发时，
        // 由确认事务内的期望入住/冻结清单重核决定胜负（见 TransferService）
        Stay targetStay = validateTarget(target);
        validateTemporaries(locked, tempNos, target);

        if (request.targetShelterId() != null) {
            shelters.findById(request.targetShelterId())
                    .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND",
                            "目标安置点不存在: " + request.targetShelterId()));
        }

        MergeApplication application = new MergeApplication(request.mergeNo(), fingerprint,
                target.getHouseholdNo(), tempNos, request.targetShelterId(), targetStay.getId());
        mergeApplications.saveAndFlush(application);
        return applicationResponse(application);
    }

    /**
     * 确认合并：在家庭行锁内重新检查全部条件——身份唯一性、目标房间容量、无障碍、期望入住，
     * 然后一次性迁移全部成员；任一条件不满足抛错回滚，所有原入住保持不变。
     */
    @Transactional
    public IdempotentResponse confirm(ConfirmMergeRequest request) {
        String fingerprint = fingerprint("CONFIRM_MERGE", request.mergeNo(), request.expectedTargetStayId());
        Optional<IdempotencyRecord> existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        MergeApplication application = mergeApplications.findByMergeNo(request.mergeNo())
                .orElseThrow(() -> ApiException.notFound("MERGE_NOT_FOUND",
                        "合并申请不存在: " + request.mergeNo()));

        // 与创建/转移/退住一致：先按主键升序锁全部参与家庭，再锁申请单行
        List<String> allNos = new ArrayList<>(application.getTemporaryHouseholdNos());
        allNos.add(application.getTargetHouseholdNo());
        List<Household> locked = households.findByHouseholdNoInForUpdateOrderByIdAsc(allNos);
        application = mergeApplications.findByMergeNoForUpdate(request.mergeNo()).orElseThrow();

        existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        if (application.getStatus() == MergeStatus.MERGED) {
            throw ApiException.conflict("MERGE_ALREADY_CONFIRMED",
                    "合并已确认，结果入住 ID: " + application.getResultStayId());
        }

        Household target = byNo(locked, application.getTargetHouseholdNo()).orElseThrow();
        Stay targetStay = validateTarget(target);
        if (!application.getExpectedTargetStayId().equals(request.expectedTargetStayId())
                || !targetStay.getId().equals(request.expectedTargetStayId())) {
            throw ApiException.conflict("STALE_STATE", "正式家庭入住状态已变化，请刷新后重试");
        }
        List<Household> temps = validateTemporaries(locked,
                application.getTemporaryHouseholdNos(), target);

        // 成员唯一性：正式家庭与各临时家庭在住成员身份标识不得重复
        List<Member> targetStaying = stayingMembers(target);
        List<List<Member>> tempStaying = temps.stream().map(this::stayingMembers).toList();
        Set<String> identityNos = new HashSet<>();
        targetStaying.forEach(m -> identityNos.add(m.getIdentityNo()));
        for (List<Member> ms : tempStaying) {
            for (Member m : ms) {
                if (!identityNos.add(m.getIdentityNo())) {
                    throw ApiException.conflict("MEMBER_NOT_UNIQUE",
                            "成员身份标识在合并范围内重复: " + m.getIdentityNo());
                }
            }
        }

        int totalSize = targetStaying.size()
                + tempStaying.stream().mapToInt(List::size).sum();
        boolean accessibleRequired = targetStaying.stream().anyMatch(Member::isNeedsAccessible)
                || tempStaying.stream().flatMap(List::stream).anyMatch(Member::isNeedsAccessible);

        long destinationShelterId = application.getTargetShelterId() != null
                ? application.getTargetShelterId()
                : targetStay.getShelter().getId();
        Shelter destination = shelters.findById(destinationShelterId)
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND",
                        "目标安置点不存在: " + destinationShelterId));

        // 本事务将结束的入住（正式家庭旧入住 + 各临时家庭入住）释放的床位计入容量复核；
        // 所有涉及房间（含其它安置点的原房间）交由 RoomAllocator 按主键统一顺序加锁
        Map<Long, Integer> releasing = new LinkedHashMap<>();
        Set<Long> roomLockIds = new TreeSet<>();
        accumulateRelease(targetStay, destinationShelterId, releasing, roomLockIds);
        List<Stay> tempStays = new ArrayList<>();
        for (Household temp : temps) {
            Stay stay = activeStay(temp);
            tempStays.add(stay);
            accumulateRelease(stay, destinationShelterId, releasing, roomLockIds);
        }

        Room destinationRoom = roomAllocator
                .allocate(destination.getId(), totalSize, accessibleRequired, releasing, roomLockIds)
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM",
                        "没有满足合并后容量和无障碍条件的房间"));

        // —— 以下所有改动在同一事务内一次性提交，失败则整体回滚，原入住保持不变 ——
        List<Stay> endingStays = new ArrayList<>();
        endingStays.add(targetStay);
        endingStays.addAll(tempStays);
        for (Stay ending : endingStays) {
            if (!ending.getRoom().getId().equals(destinationRoom.getId())) {
                Room room = ending.getRoom();
                room.setOccupied(room.getOccupied() - ending.getMemberCount());
            }
        }
        // 目标房间即某个结束入住的原房间时，释放与新增净额计算，避免重复扣减/超卖
        int releasedIntoDestination = releasing.getOrDefault(destinationRoom.getId(), 0);
        destinationRoom.setOccupied(
                destinationRoom.getOccupied() - releasedIntoDestination + totalSize);

        targetStay.end();
        tempStays.forEach(Stay::end);

        Stay newStay = stays.save(new Stay(target, destinationRoom, destination, totalSize));

        stayEvents.save(StayEvent.merge(newStay, targetStay, application.getMergeNo()));
        for (int i = 0; i < temps.size(); i++) {
            Household temp = temps.get(i);
            stayEvents.save(StayEvent.tempMerged(tempStays.get(i), newStay, application.getMergeNo()));
            for (Member old : tempStaying.get(i)) {
                old.markDeparted();
                Member moved = new Member(old.getIdentityNo(), old.getAge(),
                        old.isNeedsAccessible(), VerificationStatus.VERIFIED);
                target.addMember(moved);
                members.saveAndFlush(moved);
                memberEvents.save(MemberEvent.merged(moved, temp, newStay, application.getMergeNo()));
            }
        }

        application.markMerged(newStay.getId());
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.OK.value(),
                mergeBody(application, newStay));
    }

    private void accumulateRelease(Stay stay, long destinationShelterId,
                                   Map<Long, Integer> releasing, Set<Long> roomLockIds) {
        roomLockIds.add(stay.getRoom().getId());
        if (stay.getShelter().getId().equals(destinationShelterId)) {
            releasing.merge(stay.getRoom().getId(), stay.getMemberCount(), Integer::sum);
        }
    }

    private Stay validateTarget(Household target) {
        if (target.isTemporary()) {
            throw ApiException.conflict("TARGET_NOT_FORMAL_HOUSEHOLD",
                    "合并目标必须是正式家庭: " + target.getHouseholdNo());
        }
        return activeStay(target);
    }

    private List<Household> validateTemporaries(List<Household> locked, List<String> tempNos,
                                                Household target) {
        List<Household> temps = new ArrayList<>();
        for (String no : tempNos) {
            if (no.equals(target.getHouseholdNo())) {
                throw ApiException.conflict("INVALID_MERGE_PARTICIPANT",
                        "正式家庭不能同时作为临时家庭: " + no);
            }
            Household temp = byNo(locked, no)
                    .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND",
                            "临时家庭不存在: " + no));
            if (!temp.isTemporary()) {
                throw ApiException.conflict("NOT_TEMPORARY_HOUSEHOLD",
                        "合并来源必须是临时家庭: " + no);
            }
            if (!target.getHouseholdNo().equals(temp.getClaimedOriginalHouseholdNo())) {
                throw ApiException.conflict("DECLARED_FAMILY_MISMATCH",
                        "临时家庭 " + no + " 声明的原家庭号与合并目标不一致");
            }
            List<Member> staying = stayingMembers(temp);
            if (staying.isEmpty()) {
                throw ApiException.conflict("NO_STAYING_MEMBER", "临时家庭没有在住成员: " + no);
            }
            if (staying.stream().anyMatch(m -> m.getVerificationStatus() != VerificationStatus.VERIFIED)) {
                throw ApiException.conflict("IDENTITY_NOT_VERIFIED",
                        "临时家庭存在未完成身份核验的成员，不能并入正式家庭: " + no);
            }
            temps.add(temp);
        }
        return temps;
    }

    private Stay activeStay(Household household) {
        return stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY",
                        "家庭当前没有有效入住: " + household.getHouseholdNo()));
    }

    private List<Member> stayingMembers(Household household) {
        return household.getMembers().stream()
                .filter(Member::isCurrentlyStaying)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Optional<Household> byNo(List<Household> list, String no) {
        return list.stream().filter(h -> h.getHouseholdNo().equals(no)).findFirst();
    }

    private List<String> normalize(List<String> nos) {
        List<String> sorted = nos.stream().sorted().distinct().toList();
        if (sorted.size() != nos.size()) {
            throw ApiException.conflict("INVALID_MERGE_PARTICIPANT", "临时家庭名单存在重复项");
        }
        return sorted;
    }

    private IdempotentResponse replayApplication(MergeApplication application, String fingerprint) {
        if (!application.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同合并业务号携带了不同的申请内容");
        }
        return applicationResponse(application);
    }

    private IdempotentResponse applicationResponse(MergeApplication application) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mergeNo", application.getMergeNo());
        body.put("status", application.getStatus().name());
        body.put("targetHouseholdNo", application.getTargetHouseholdNo());
        body.put("temporaryHouseholdNos", application.getTemporaryHouseholdNos());
        body.put("targetShelterId", application.getTargetShelterId());
        body.put("expectedTargetStayId", application.getExpectedTargetStayId());
        return new IdempotentResponse(HttpStatus.CREATED.value(), idempotency.toJson(body));
    }

    private Map<String, Object> mergeBody(MergeApplication application, Stay stay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mergeNo", application.getMergeNo());
        body.put("status", application.getStatus().name());
        body.put("stayId", stay.getId());
        body.put("householdNo", stay.getHousehold().getHouseholdNo());
        body.put("shelterId", stay.getShelter().getId());
        body.put("roomId", stay.getRoom().getId());
        body.put("roomNumber", stay.getRoom().getRoomNumber());
        body.put("memberCount", stay.getMemberCount());
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
