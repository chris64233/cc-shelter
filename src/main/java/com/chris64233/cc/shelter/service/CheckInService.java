package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.IdempotencyRecord;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckInService {

    private final HouseholdRepository households;
    private final ShelterRepository shelters;
    private final StayRepository stays;
    private final StayEventRepository events;
    private final RoomAllocator roomAllocator;
    private final IdempotencyStore idempotency;

    public CheckInService(HouseholdRepository households, ShelterRepository shelters,
                          StayRepository stays, StayEventRepository events,
                          RoomAllocator roomAllocator, IdempotencyStore idempotency) {
        this.households = households;
        this.shelters = shelters;
        this.stays = stays;
        this.events = events;
        this.roomAllocator = roomAllocator;
        this.idempotency = idempotency;
    }

    @Transactional
    public IdempotentResponse checkIn(CheckInRequest request) {
        String fingerprint = fingerprint("CHECK_IN", request.householdNo(), request.shelterId());
        Optional<IdempotencyRecord> existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));

        // 拿到家庭行锁后再次检查，串行化并发重放
        existing = idempotency.find(request.idempotencyKey());
        if (existing.isPresent()) {
            return idempotency.replay(existing.get(), fingerprint);
        }

        if (stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE).isPresent()) {
            throw ApiException.conflict("ALREADY_ACCOMMODATED", "家庭存在有效入住，不能重复安排");
        }
        if (household.isTemporary()) {
            throw ApiException.conflict("TEMPORARY_HOUSEHOLD_USE_TEMP_CHANNEL",
                    "临时家庭必须走临时入住通道: " + request.householdNo());
        }
        Shelter shelter = shelters.findById(request.shelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + request.shelterId()));

        int size = household.stayingMemberCount();
        if (size == 0) {
            throw ApiException.conflict("NO_STAYING_MEMBER", "家庭没有在住成员，不能安排入住");
        }
        Room room = roomAllocator
                .allocate(shelter.getId(), size, household.needsAccessibleRoom())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "没有满足容量和无障碍条件的房间"));

        room.setOccupied(room.getOccupied() + size);
        Stay stay = stays.save(new Stay(household, room, shelter, size));
        events.save(StayEvent.checkIn(stay));
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.CREATED.value(),
                StayResponse.of(stay));
    }

    @Transactional
    public IdempotentResponse transfer(TransferRequest request) {
        String fingerprint = fingerprint("TRANSFER", request.householdNo(),
                request.targetShelterId(), request.expectedStayId());
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

        Stay current = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY", "家庭当前没有有效入住"));
        if (!current.getId().equals(request.expectedStayId())) {
            throw ApiException.conflict("STALE_STATE", "入住状态已变化，请刷新后重试");
        }
        if (current.getShelter().getId().equals(request.targetShelterId())) {
            throw ApiException.conflict("SAME_SHELTER", "目标安置点与当前安置点相同");
        }
        Shelter target = shelters.findById(request.targetShelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "目标安置点不存在: " + request.targetShelterId()));

        int size = household.stayingMemberCount();
        // 目标房间与原房间按主键统一顺序加锁，再在锁内复核容量；
        // 目标不足时抛错回滚，原入住完整保留，也不会与并发事务形成锁序倒置
        Room targetRoom = roomAllocator
                .allocate(target.getId(), size, household.needsAccessibleRoom(),
                        java.util.Map.of(), List.of(current.getRoom().getId()))
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "目标安置点没有满足容量和无障碍条件的房间"));
        Room originRoom = current.getRoom();

        originRoom.setOccupied(originRoom.getOccupied() - size);
        targetRoom.setOccupied(targetRoom.getOccupied() + size);
        current.end();
        Stay newStay = stays.save(new Stay(household, targetRoom, target, size));
        events.save(StayEvent.transfer(newStay, current));
        return idempotency.save(request.idempotencyKey(), fingerprint, HttpStatus.CREATED.value(),
                StayResponse.of(newStay));
    }

    private String fingerprint(String operation, Object... parts) {
        StringBuilder builder = new StringBuilder(operation);
        for (Object part : parts) {
            builder.append('|').append(part);
        }
        return builder.toString();
    }
}
