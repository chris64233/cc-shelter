package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.CheckOutRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckInService {

    private final HouseholdRepository households;
    private final ShelterRepository shelters;
    private final RoomRepository rooms;
    private final StayRepository stays;
    private final StayEventRepository events;
    private final IdempotencyService idempotency;
    private final RoomAllocationService roomAllocation;

    public CheckInService(HouseholdRepository households, ShelterRepository shelters,
                          RoomRepository rooms, StayRepository stays, StayEventRepository events,
                          IdempotencyService idempotency, RoomAllocationService roomAllocation) {
        this.households = households;
        this.shelters = shelters;
        this.rooms = rooms;
        this.stays = stays;
        this.events = events;
        this.idempotency = idempotency;
        this.roomAllocation = roomAllocation;
    }

    @Transactional
    public IdempotentResponse checkIn(CheckInRequest request) {
        String fingerprint = idempotency.fingerprint("CHECK_IN", request.householdNo(), request.shelterId());
        Optional<IdempotentResponse> existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));

        // 拿到家庭行锁后再次检查，串行化并发重放
        existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE).isPresent()) {
            throw ApiException.conflict("ALREADY_ACCOMMODATED", "家庭存在有效入住，不能重复安排");
        }
        Shelter shelter = shelters.findById(request.shelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + request.shelterId()));

        int size = household.getMembers().size();
        Room room = roomAllocation.lockSuitableRoom(shelter.getId(), size, household.needsAccessibleRoom())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "没有满足容量和无障碍条件的房间"));

        room.setOccupied(room.getOccupied() + size);
        Stay stay = stays.save(new Stay(household, room, shelter, size));
        events.save(StayEvent.checkIn(stay));
        return idempotency.record(request.idempotencyKey(), fingerprint,
                HttpStatus.CREATED.value(), StayResponse.of(stay));
    }

    @Transactional
    public IdempotentResponse transfer(TransferRequest request) {
        String fingerprint = idempotency.fingerprint("TRANSFER", request.householdNo(),
                request.targetShelterId(), request.expectedStayId());
        Optional<IdempotentResponse> existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));

        existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
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

        int size = current.getMemberCount();
        // 先在原事务内锁定目标房间，再释放原房间；目标不足时抛错回滚，原入住完整保留
        Room targetRoom = roomAllocation.lockSuitableRoom(target.getId(), size, household.needsAccessibleRoom())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "目标安置点没有满足容量和无障碍条件的房间"));
        Room originRoom = rooms.findByIdForUpdate(current.getRoom().getId())
                .orElseThrow(() -> ApiException.conflict("ROOM_NOT_FOUND", "原房间不存在"));

        originRoom.setOccupied(originRoom.getOccupied() - size);
        targetRoom.setOccupied(targetRoom.getOccupied() + size);
        current.end();
        Stay newStay = stays.save(new Stay(household, targetRoom, target, size));
        events.save(StayEvent.transfer(newStay, current));
        return idempotency.record(request.idempotencyKey(), fingerprint,
                HttpStatus.CREATED.value(), StayResponse.of(newStay));
    }

    @Transactional
    public IdempotentResponse checkOut(CheckOutRequest request) {
        String fingerprint = idempotency.fingerprint("CHECK_OUT", request.householdNo(), request.expectedStayId());
        Optional<IdempotentResponse> existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));

        existing = idempotency.replayIfExists(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Stay current = stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_STAY", "家庭当前没有有效入住"));
        if (!current.getId().equals(request.expectedStayId())) {
            throw ApiException.conflict("STALE_STATE", "入住状态已变化，请刷新后重试");
        }
        Room room = rooms.findByIdForUpdate(current.getRoom().getId())
                .orElseThrow(() -> ApiException.conflict("ROOM_NOT_FOUND", "原房间不存在"));

        room.setOccupied(room.getOccupied() - current.getMemberCount());
        current.end();
        events.save(StayEvent.checkOut(current));
        return idempotency.record(request.idempotencyKey(), fingerprint,
                HttpStatus.OK.value(), StayResponse.of(current));
    }
}
