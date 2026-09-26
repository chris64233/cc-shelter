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
import com.chris64233.cc.shelter.repo.IdempotencyRecordRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
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
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ObjectMapper objectMapper;

    public CheckInService(HouseholdRepository households, ShelterRepository shelters,
                          RoomRepository rooms, StayRepository stays, StayEventRepository events,
                          IdempotencyRecordRepository idempotencyRecords, ObjectMapper objectMapper) {
        this.households = households;
        this.shelters = shelters;
        this.rooms = rooms;
        this.stays = stays;
        this.events = events;
        this.idempotencyRecords = idempotencyRecords;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IdempotentResponse checkIn(CheckInRequest request) {
        String fingerprint = fingerprint("CHECK_IN", request.householdNo(), request.shelterId());
        Optional<IdempotencyRecord> existing = idempotencyRecords.findByIdemKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));

        // 拿到家庭行锁后再次检查，串行化并发重放
        existing = idempotencyRecords.findByIdemKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        if (stays.findByHouseholdIdAndStatus(household.getId(), StayStatus.ACTIVE).isPresent()) {
            throw ApiException.conflict("ALREADY_ACCOMMODATED", "家庭存在有效入住，不能重复安排");
        }
        Shelter shelter = shelters.findById(request.shelterId())
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + request.shelterId()));

        int size = household.getMembers().size();
        Room room = lockSuitableRoom(shelter.getId(), size, household.needsAccessibleRoom())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "没有满足容量和无障碍条件的房间"));

        room.setOccupied(room.getOccupied() + size);
        Stay stay = stays.save(new Stay(household, room, shelter, size));
        events.save(StayEvent.checkIn(stay));
        return record(request.idempotencyKey(), fingerprint, HttpStatus.CREATED.value(), StayResponse.of(stay));
    }

    @Transactional
    public IdempotentResponse transfer(TransferRequest request) {
        String fingerprint = fingerprint("TRANSFER", request.householdNo(),
                request.targetShelterId(), request.expectedStayId());
        Optional<IdempotencyRecord> existing = idempotencyRecords.findByIdemKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        Household household = households.findByHouseholdNoForUpdate(request.householdNo())
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNo()));

        existing = idempotencyRecords.findByIdemKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
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
        Room targetRoom = lockSuitableRoom(target.getId(), size, household.needsAccessibleRoom())
                .orElseThrow(() -> ApiException.conflict("NO_SUITABLE_ROOM", "目标安置点没有满足容量和无障碍条件的房间"));
        Room originRoom = rooms.findByIdForUpdate(current.getRoom().getId())
                .orElseThrow(() -> ApiException.conflict("ROOM_NOT_FOUND", "原房间不存在"));

        originRoom.setOccupied(originRoom.getOccupied() - size);
        targetRoom.setOccupied(targetRoom.getOccupied() + size);
        current.end();
        Stay newStay = stays.save(new Stay(household, targetRoom, target, size));
        events.save(StayEvent.transfer(newStay, current));
        return record(request.idempotencyKey(), fingerprint, HttpStatus.CREATED.value(), StayResponse.of(newStay));
    }

    /**
     * 按“剩余床位最少、房间编号最小”选择候选，并逐个加悲观写锁复核，
     * 保证并发入住不会超卖。
     */
    private Optional<Room> lockSuitableRoom(Long shelterId, int size, boolean accessibleRequired) {
        List<Long> candidateIds = rooms.findCandidateIds(shelterId, size, accessibleRequired);
        for (Long candidateId : candidateIds) {
            Room locked = rooms.findByIdForUpdate(candidateId).orElseThrow();
            if (locked.remainingBeds() >= size && (!accessibleRequired || locked.isAccessible())) {
                return Optional.of(locked);
            }
        }
        return Optional.empty();
    }

    private IdempotentResponse replay(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键携带了不同的请求内容");
        }
        return new IdempotentResponse(record.getResponseStatus(), record.getResponseBody());
    }

    private IdempotentResponse record(String key, String fingerprint, int status, StayResponse body) {
        String json = toJson(body);
        idempotencyRecords.save(new IdempotencyRecord(key, fingerprint, status, json));
        return new IdempotentResponse(status, json);
    }

    private String fingerprint(String operation, Object... parts) {
        StringBuilder builder = new StringBuilder(operation);
        for (Object part : parts) {
            builder.append('|').append(part);
        }
        return builder.toString();
    }

    private String toJson(Object body) {
        return objectMapper.writeValueAsString(body);
    }
}
