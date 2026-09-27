package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.IdentityVerification;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.IdentityVerificationRepository;
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.MergeRequest;
import com.chris64233.cc.shelter.web.dto.MergeResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.VerificationEventResponse;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 家庭重聚：走散成员临时入住、身份核验、临时家庭与正式家庭的安全合并。
 */
@Service
public class ReunificationService {

    private final HouseholdRepository households;
    private final MemberRepository members;
    private final ShelterRepository shelters;
    private final RoomRepository rooms;
    private final StayRepository stays;
    private final StayEventRepository events;
    private final IdentityVerificationRepository verifications;
    private final IdempotencyService idempotency;
    private final RoomAllocationService roomAllocation;

    public ReunificationService(HouseholdRepository households, MemberRepository members,
                                ShelterRepository shelters, RoomRepository rooms,
                                StayRepository stays, StayEventRepository events,
                                IdentityVerificationRepository verifications,
                                IdempotencyService idempotency, RoomAllocationService roomAllocation) {
        this.households = households;
        this.members = members;
        this.shelters = shelters;
        this.rooms = rooms;
        this.stays = stays;
        this.events = events;
        this.verifications = verifications;
        this.idempotency = idempotency;
        this.roomAllocation = roomAllocation;
    }

    /**
     * 走散成员以单人临时家庭身份入住，记录声明的原家庭号；身份核验状态初始为未核验。
     */
    @Transactional
    public IdempotentResponse temporaryCheckIn(TemporaryCheckInRequest request) {
        String fingerprint = idempotency.fingerprint("TEMP_CHECK_IN", request.identityNo(),
                request.declaredHouseholdNo(), request.shelterId());
        Optional<IdempotentResponse> existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (members.findByIdentityNo(request.identityNo()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_MEMBER", "成员身份标识已存在: " + request.identityNo());
        }
        Shelter shelter = shelters.findById(request.shelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + request.shelterId()));

        Household temporary = Household.temporary("TMP-" + request.identityNo());
        temporary.addMember(Member.temporary(request.identityNo(), request.age(),
                request.requiresAccessible(), request.declaredHouseholdNo()));
        // 立即 flush，让身份标识/临时家庭号唯一约束冲突在事务边界内抛出
        households.saveAndFlush(temporary);

        Member member = temporary.getMembers().get(0);
        Room room = roomAllocation.lockSuitableRoom(shelter.getId(), 1, member.isNeedsAccessible())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "没有满足容量和无障碍条件的房间"));

        room.setOccupied(room.getOccupied() + 1);
        Stay stay = stays.save(new Stay(temporary, room, shelter, 1));
        events.save(StayEvent.checkIn(stay));
        return idempotency.record(request.idempotencyKey(), fingerprint,
                HttpStatus.CREATED.value(), StayResponse.of(stay));
    }

    /**
     * 记录一次身份核验事件（幂等），并将成员置为已核验。
     */
    @Transactional
    public IdempotentResponse verifyIdentity(VerifyIdentityRequest request) {
        String fingerprint = idempotency.fingerprint("VERIFY_IDENTITY", request.identityNo());
        Optional<IdempotentResponse> existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Member member = members.findByIdentityNoForUpdate(request.identityNo())
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在: " + request.identityNo()));

        // 拿到成员行锁后再次检查，串行化并发重放
        existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        member.verify();
        IdentityVerification event = verifications.save(new IdentityVerification(request.idempotencyKey(),
                member.getIdentityNo(), member.getHousehold().getHouseholdNo(), "VERIFIED"));
        return idempotency.record(request.idempotencyKey(), fingerprint,
                HttpStatus.CREATED.value(), VerificationEventResponse.of(event));
    }

    /**
     * 把多个临时入住合并进一个正式家庭。确认时重新检查成员唯一性、目标房间容量与无障碍条件，
     * 任一条件不满足则整体回滚，原入住保持不变；全部满足时一次性迁移全部成员。
     */
    @Transactional
    public IdempotentResponse merge(MergeRequest request) {
        List<String> tempNos = request.temporaryHouseholdNos().stream().sorted().toList();
        if (new HashSet<>(tempNos).size() != tempNos.size()) {
            throw ApiException.conflict("DUPLICATE_TEMPORARY", "合并申请中临时家庭重复");
        }
        if (tempNos.contains(request.targetHouseholdNo())) {
            throw ApiException.conflict("INVALID_TEMPORARY", "临时家庭不能是目标家庭本身");
        }
        String fingerprint = idempotency.fingerprint("MERGE", request.targetHouseholdNo(),
                tempNos, request.targetShelterId());
        Optional<IdempotentResponse> existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 按家庭号字典序对全部涉及家庭加悲观写锁，保证并发合并/转移/退住之间锁顺序一致
        List<String> allNos = new ArrayList<>(tempNos);
        allNos.add(request.targetHouseholdNo());
        allNos.sort(Comparator.naturalOrder());
        Map<String, Household> locked = new LinkedHashMap<>();
        for (String no : allNos) {
            locked.put(no, households.findByHouseholdNoForUpdate(no)
                    .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + no)));
        }

        existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Household target = locked.get(request.targetHouseholdNo());
        if (target.isTemporary()) {
            throw ApiException.conflict("INVALID_TARGET", "目标家庭必须是正式家庭");
        }
        List<Household> temporaryHouseholds = tempNos.stream().map(locked::get).toList();
        for (Household temporary : temporaryHouseholds) {
            if (!temporary.isTemporary()) {
                throw ApiException.conflict("NOT_TEMPORARY", "不是临时家庭: " + temporary.getHouseholdNo());
            }
        }

        Stay targetStay = stays.findByHouseholdIdAndStatus(target.getId(), StayStatus.ACTIVE).orElse(null);
        List<Stay> tempStays = new ArrayList<>();
        for (Household temporary : temporaryHouseholds) {
            tempStays.add(stays.findByHouseholdIdAndStatus(temporary.getId(), StayStatus.ACTIVE)
                    .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY",
                            "临时家庭当前没有有效入住: " + temporary.getHouseholdNo())));
        }

        // 成员唯一性与身份核验检查
        Set<String> identities = new HashSet<>();
        target.getMembers().forEach(member -> identities.add(member.getIdentityNo()));
        List<Member> incoming = new ArrayList<>();
        for (Household temporary : temporaryHouseholds) {
            for (Member member : temporary.getMembers()) {
                if (!member.isVerified()) {
                    throw ApiException.conflict("UNVERIFIED_MEMBER",
                            "成员未完成身份核验，不能并入正式家庭: " + member.getIdentityNo());
                }
                if (!identities.add(member.getIdentityNo())) {
                    throw ApiException.conflict("DUPLICATE_MEMBER", "成员已存在于目标家庭: " + member.getIdentityNo());
                }
                incoming.add(member);
            }
        }

        int added = incoming.size();
        int newTotal = target.getMembers().size() + added;
        boolean accessibleRequired = target.needsAccessibleRoom()
                || incoming.stream().anyMatch(Member::isNeedsAccessible);

        // 决定合并后全家的房间：优先留在原房间，不足时在同一事务内为全家另选房间；
        // 临时成员即将腾出的床位（同一安置点内）计入有效剩余
        Room finalRoom;
        Shelter finalShelter;
        boolean roomChanged;
        if (request.targetShelterId() != null) {
            finalShelter = shelters.findById(request.targetShelterId())
                    .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND",
                            "目标安置点不存在: " + request.targetShelterId()));
            finalRoom = roomAllocation.lockSuitableRoom(finalShelter.getId(), newTotal, accessibleRequired,
                            releaseCredit(tempStays, finalShelter.getId()))
                    .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "目标安置点没有满足容量和无障碍条件的房间"));
            roomChanged = targetStay == null || !finalRoom.getId().equals(targetStay.getRoom().getId());
        } else if (targetStay != null) {
            Room currentRoom = rooms.findByIdForUpdate(targetStay.getRoom().getId())
                    .orElseThrow(() -> ApiException.conflict("ROOM_NOT_FOUND", "原房间不存在"));
            boolean fits = currentRoom.getBedCount() >= newTotal
                    && (!accessibleRequired || currentRoom.isAccessible());
            if (fits) {
                finalRoom = currentRoom;
                finalShelter = targetStay.getShelter();
                roomChanged = false;
            } else {
                finalShelter = targetStay.getShelter();
                finalRoom = roomAllocation.lockSuitableRoom(finalShelter.getId(), newTotal, accessibleRequired,
                                releaseCredit(tempStays, finalShelter.getId()))
                        .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM",
                                "没有满足容量和无障碍条件的房间"));
                roomChanged = true;
            }
        } else {
            throw ApiException.conflict("NO_ACTIVE_STAY", "目标家庭当前没有有效入住，合并时必须指定目标安置点");
        }

        // 结束全部临时入住并按房间 id 升序释放床位，临时入住记录保留为已结束状态
        List<Stay> tempStaysByRoom = tempStays.stream()
                .sorted(Comparator.comparing(stay -> stay.getRoom().getId()))
                .toList();
        for (Stay tempStay : tempStaysByRoom) {
            Room tempRoom = rooms.findByIdForUpdate(tempStay.getRoom().getId())
                    .orElseThrow(() -> ApiException.conflict("ROOM_NOT_FOUND", "临时入住房间不存在"));
            tempRoom.setOccupied(tempRoom.getOccupied() - tempStay.getMemberCount());
            tempStay.end();
        }

        // 迁移正式家庭侧的入住与床位
        Stay resultStay;
        if (targetStay == null) {
            finalRoom.setOccupied(finalRoom.getOccupied() + newTotal);
            resultStay = stays.save(new Stay(target, finalRoom, finalShelter, newTotal));
        } else if (!roomChanged) {
            finalRoom.setOccupied(finalRoom.getOccupied() + added);
            targetStay.addMembers(added);
            resultStay = targetStay;
        } else {
            Room originRoom = rooms.findByIdForUpdate(targetStay.getRoom().getId())
                    .orElseThrow(() -> ApiException.conflict("ROOM_NOT_FOUND", "原房间不存在"));
            originRoom.setOccupied(originRoom.getOccupied() - targetStay.getMemberCount());
            finalRoom.setOccupied(finalRoom.getOccupied() + newTotal);
            targetStay.end();
            resultStay = stays.save(new Stay(target, finalRoom, finalShelter, newTotal));
        }

        // 一次性把临时成员迁入正式家庭
        for (Household temporary : temporaryHouseholds) {
            for (Member member : new ArrayList<>(temporary.getMembers())) {
                target.addMember(member);
                temporary.removeMember(member);
            }
        }

        // 事件：目标家庭与每个临时家庭各一条 MERGE，构成完整房间迁移链
        for (int i = 0; i < temporaryHouseholds.size(); i++) {
            Stay tempStay = tempStays.get(i);
            events.save(StayEvent.merge(tempStay.getId(), temporaryHouseholds.get(i).getHouseholdNo(),
                    tempStay, finalRoom, finalShelter, tempStay.getMemberCount()));
        }
        events.save(StayEvent.merge(resultStay.getId(), target.getHouseholdNo(),
                targetStay, finalRoom, finalShelter, newTotal));

        MergeResponse body = new MergeResponse(target.getHouseholdNo(), resultStay.getId(),
                finalShelter.getId(), finalRoom.getId(), finalRoom.getRoomNumber(), newTotal,
                tempNos, roomChanged);
        return idempotency.record(request.idempotencyKey(), fingerprint, HttpStatus.CREATED.value(), body);
    }

    /** 统计指定安置点内、本合并事务即将释放的床位（房间 id -> 床位数） */
    private Map<Long, Integer> releaseCredit(List<Stay> tempStays, Long shelterId) {
        Map<Long, Integer> credit = new LinkedHashMap<>();
        for (Stay tempStay : tempStays) {
            if (tempStay.getShelter().getId().equals(shelterId)) {
                credit.merge(tempStay.getRoom().getId(), tempStay.getMemberCount(), Integer::sum);
            }
        }
        return credit;
    }
}
