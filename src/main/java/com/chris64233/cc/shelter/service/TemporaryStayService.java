package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.IdempotencyRecord;
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
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.CheckoutRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemporaryStayService {

    private final HouseholdRepository households;
    private final ShelterRepository shelters;
    private final StayRepository stays;
    private final StayEventRepository stayEvents;
    private final MemberRepository members;
    private final MemberEventRepository memberEvents;
    private final RoomAllocator roomAllocator;
    private final IdempotencyStore idempotency;

    public TemporaryStayService(HouseholdRepository households, ShelterRepository shelters,
                                StayRepository stays, StayEventRepository stayEvents,
                                MemberRepository members, MemberEventRepository memberEvents,
                                RoomAllocator roomAllocator, IdempotencyStore idempotency) {
        this.households = households;
        this.shelters = shelters;
        this.stays = stays;
        this.stayEvents = stayEvents;
        this.members = members;
        this.memberEvents = memberEvents;
        this.roomAllocator = roomAllocator;
        this.idempotency = idempotency;
    }

    /** 走散成员以临时家庭身份入住：记录 UNVERIFIED 初始核验状态和临时入住成员事件 */
    @Transactional
    public IdempotentResponse temporaryCheckIn(TemporaryCheckInRequest request) {
        String fingerprint = fingerprint("TEMP_CHECK_IN", request.householdNo(), request.shelterId());
        Optional<IdempotencyRecord> existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));
        existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        if (!household.isTemporary()) {
            throw ApiException.conflict("NOT_TEMPORARY_HOUSEHOLD",
                    "仅临时家庭可走临时入住通道: " + request.householdNo());
        }
        if (stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE).isPresent()) {
            throw ApiException.conflict("ALREADY_ACCOMMODATED", "临时家庭存在有效入住，不能重复安排");
        }
        Shelter shelter = shelters.findById(request.shelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + request.shelterId()));

        int size = household.stayingMemberCount();
        if (size == 0) {
            throw ApiException.conflict("NO_STAYING_MEMBER", "临时家庭没有在住成员，不能安排入住");
        }
        Room room = roomAllocator
                .allocate(shelter.getId(), size, household.needsAccessibleRoom())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "没有满足容量和无障碍条件的房间"));

        room.setOccupied(room.getOccupied() + size);
        Stay stay = stays.save(new Stay(household, room, shelter, size));
        stayEvents.save(StayEvent.checkIn(stay));
        household.getMembers().stream()
                .filter(Member::isCurrentlyStaying)
                .forEach(member -> memberEvents.save(MemberEvent.temporaryCheckIn(member, stay)));
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.CREATED.value(),
                StayResponse.of(stay));
    }

    /** 身份核验事件：幂等键 + 成员身份标识。未登记/已核验/非临时成员分别给出明确错误 */
    @Transactional
    public IdempotentResponse verifyIdentity(VerifyIdentityRequest request) {
        String fingerprint = fingerprint("VERIFY_IDENTITY", request.identityNo());
        Optional<IdempotencyRecord> existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        Member member = members.findFirstByIdentityNoAndDepartedAtIsNull(request.identityNo())
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND",
                        "成员不存在或已不在住: " + request.identityNo()));
        // 锁定成员所属家庭行，串行化同一成员的并发核验
        households.findByHouseholdNoForUpdate(member.getHousehold().getHouseholdNo()).orElseThrow();
        existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        if (!member.getHousehold().isTemporary()) {
            throw ApiException.conflict("NOT_TEMPORARY_MEMBER", "正式登记成员不需要身份核验");
        }
        if (member.getVerificationStatus() == VerificationStatus.VERIFIED) {
            throw ApiException.conflict("IDENTITY_ALREADY_VERIFIED", "成员已完成身份核验");
        }
        if (!member.isCurrentlyStaying()) {
            throw ApiException.conflict("MEMBER_NOT_STAYING", "成员当前不在住，不能核验");
        }

        member.markVerified();
        memberEvents.save(MemberEvent.verified(member));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identityNo", member.getIdentityNo());
        body.put("householdNo", member.getHousehold().getHouseholdNo());
        body.put("verificationStatus", member.getVerificationStatus().name());
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.OK.value(), body);
    }

    /** 成员退住：结束当前入住、释放床位，追加退住事件（家庭与成员两条审计线） */
    @Transactional
    public IdempotentResponse checkout(CheckoutRequest request) {
        String fingerprint = fingerprint("CHECKOUT", request.householdNo(), request.expectedStayId());
        Optional<IdempotencyRecord> existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));
        existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        Stay stay = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY", "家庭当前没有有效入住"));
        if (!stay.getId().equals(request.expectedStayId())) {
            throw ApiException.conflict("STALE_STATE", "入住状态已变化，请刷新后重试");
        }

        int size = stay.getMemberCount();
        // 家庭行锁之后再锁房间，与合并/转移保持相同加锁顺序，避免双重释放床位
        Room room = roomAllocator.lockByIds(List.of(stay.getRoom().getId())).getFirst();
        room.setOccupied(room.getOccupied() - size);
        stay.end();
        stayEvents.save(StayEvent.checkout(stay));
        household.getMembers().stream()
                .filter(Member::isCurrentlyStaying)
                .forEach(member -> {
                    member.markDeparted();
                    memberEvents.save(MemberEvent.checkout(member, stay));
                });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stayId", stay.getId());
        body.put("householdNo", household.getHouseholdNo());
        body.put("memberCount", size);
        body.put("status", stay.getStatus().name());
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.OK.value(), body);
    }

    private String fingerprint(String operation, Object... parts) {
        StringBuilder builder = new StringBuilder(operation);
        for (Object part : parts) {
            builder.append('|').append(part);
        }
        return builder.toString();
    }
}
