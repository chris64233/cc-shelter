package com.chris64233.cc.shelter.stay;

import com.chris64233.cc.shelter.common.BusinessException;
import com.chris64233.cc.shelter.household.Household;
import com.chris64233.cc.shelter.household.HouseholdRepository;
import com.chris64233.cc.shelter.shelter.Room;
import com.chris64233.cc.shelter.shelter.RoomRepository;
import com.chris64233.cc.shelter.shelter.Shelter;
import com.chris64233.cc.shelter.shelter.ShelterRepository;
import com.chris64233.cc.shelter.stay.dto.CheckInRequest;
import com.chris64233.cc.shelter.stay.dto.StayResponse;
import com.chris64233.cc.shelter.stay.dto.TransferRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

@Service
public class StayService {

    private final HouseholdRepository householdRepository;
    private final ShelterRepository shelterRepository;
    private final RoomRepository roomRepository;
    private final StayRepository stayRepository;
    private final StayEventRepository stayEventRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionTemplate transactionTemplate;

    public StayService(HouseholdRepository householdRepository,
                       ShelterRepository shelterRepository,
                       RoomRepository roomRepository,
                       StayRepository stayRepository,
                       StayEventRepository stayEventRepository,
                       IdempotencyRecordRepository idempotencyRecordRepository,
                       PlatformTransactionManager transactionManager) {
        this.householdRepository = householdRepository;
        this.shelterRepository = shelterRepository;
        this.roomRepository = roomRepository;
        this.stayRepository = stayRepository;
        this.stayEventRepository = stayEventRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public StayOutcome checkIn(CheckInRequest request) {
        String fingerprint = request.householdNumber() + "|" + request.shelterId();
        try {
            return transactionTemplate.execute(status -> doCheckIn(request, fingerprint));
        } catch (DataIntegrityViolationException concurrentInsert) {
            return recoverFromConcurrentKey(request.idempotencyKey(), fingerprint, concurrentInsert);
        }
    }

    public StayOutcome transfer(String householdNumber, TransferRequest request) {
        String fingerprint = householdNumber + "|" + request.targetShelterId();
        try {
            return transactionTemplate.execute(status -> doTransfer(householdNumber, request, fingerprint));
        } catch (DataIntegrityViolationException concurrentInsert) {
            return recoverFromConcurrentKey(request.idempotencyKey(), fingerprint, concurrentInsert);
        }
    }

    private StayOutcome doCheckIn(CheckInRequest request, String fingerprint) {
        var existing = idempotencyRecordRepository.findById(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        Household household = householdRepository.findByHouseholdNumberForUpdate(request.householdNumber())
                .orElseThrow(() -> BusinessException.notFound(
                        "HOUSEHOLD_NOT_FOUND", "家庭不存在: " + request.householdNumber()));

        // 加锁期间可能有同键请求已提交，需复查幂等记录
        var rechecked = idempotencyRecordRepository.findById(request.idempotencyKey());
        if (rechecked.isPresent()) {
            return replay(rechecked.get(), fingerprint);
        }

        stayRepository.findByActiveHouseholdId(household.getId()).ifPresent(stay -> {
            throw BusinessException.conflict("HOUSEHOLD_ALREADY_CHECKED_IN",
                    "家庭已有有效入住: " + request.householdNumber());
        });

        Shelter shelter = shelterRepository.findById(request.shelterId())
                .orElseThrow(() -> BusinessException.notFound(
                        "SHELTER_NOT_FOUND", "安置点不存在: " + request.shelterId()));

        int memberCount = household.getMembers().size();
        boolean needsAccessibility = household.getMembers().stream().anyMatch(m -> m.isNeedsAccessibility());

        List<Room> rooms = roomRepository.findAllByShelterIdForUpdate(shelter.getId());
        Room selected = selectRoom(rooms, memberCount, needsAccessibility)
                .orElseThrow(() -> BusinessException.unprocessable("NO_SUITABLE_ROOM",
                        "安置点内没有满足条件的房间: " + request.shelterId()));

        selected.occupy(memberCount);
        Stay stay = stayRepository.save(new Stay(household, shelter, selected, memberCount));
        stayEventRepository.save(StayEvent.checkIn(
                household.getHouseholdNumber(), shelter.getId(), selected.getId(), memberCount));

        int httpStatus = HttpStatus.CREATED.value();
        idempotencyRecordRepository.saveAndFlush(
                new IdempotencyRecord(request.idempotencyKey(), fingerprint, stay.getId(), httpStatus));
        return new StayOutcome(StayResponse.from(stay), httpStatus);
    }

    private StayOutcome doTransfer(String householdNumber, TransferRequest request, String fingerprint) {
        var existing = idempotencyRecordRepository.findById(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        Household household = householdRepository.findByHouseholdNumberForUpdate(householdNumber)
                .orElseThrow(() -> BusinessException.notFound(
                        "HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNumber));

        // 加锁期间可能有同键请求已提交，需复查幂等记录
        var rechecked = idempotencyRecordRepository.findById(request.idempotencyKey());
        if (rechecked.isPresent()) {
            return replay(rechecked.get(), fingerprint);
        }

        Stay activeStay = stayRepository.findByActiveHouseholdId(household.getId())
                .orElseThrow(() -> BusinessException.conflict(
                        "NO_ACTIVE_STAY", "家庭当前没有有效入住: " + householdNumber));

        Long sourceShelterId = activeStay.getShelter().getId();
        if (sourceShelterId.equals(request.targetShelterId())) {
            throw BusinessException.unprocessable("SAME_SHELTER_TRANSFER", "目标安置点与当前安置点相同");
        }

        Shelter targetShelter = shelterRepository.findById(request.targetShelterId())
                .orElseThrow(() -> BusinessException.notFound(
                        "SHELTER_NOT_FOUND", "安置点不存在: " + request.targetShelterId()));

        // 按房间 id 升序统一加锁，避免并发转移相互死锁
        List<Long> shelterIds = List.copyOf(new TreeSet<>(List.of(sourceShelterId, targetShelter.getId())));
        List<Room> lockedRooms = roomRepository.findAllByShelterIdInForUpdate(shelterIds);

        Room sourceRoom = lockedRooms.stream()
                .filter(room -> room.getId().equals(activeStay.getRoom().getId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.conflict("STALE_STAY_STATE", "原入住房间状态异常"));

        boolean needsAccessibility = household.getMembers().stream().anyMatch(m -> m.isNeedsAccessibility());
        Room targetRoom = selectRoom(
                        lockedRooms.stream()
                                .filter(room -> room.getShelter().getId().equals(targetShelter.getId()))
                                .toList(),
                        activeStay.getMemberCount(), needsAccessibility)
                .orElseThrow(() -> BusinessException.unprocessable("NO_SUITABLE_ROOM",
                        "目标安置点没有满足条件的房间，原入住保持不变: " + request.targetShelterId()));

        sourceRoom.release(activeStay.getMemberCount());
        targetRoom.occupy(activeStay.getMemberCount());

        activeStay.endAsTransferred();
        stayRepository.saveAndFlush(activeStay);

        Stay newStay = stayRepository.save(
                new Stay(household, targetShelter, targetRoom, activeStay.getMemberCount()));
        stayEventRepository.save(StayEvent.transfer(
                householdNumber, sourceShelterId, sourceRoom.getId(),
                targetShelter.getId(), targetRoom.getId(), activeStay.getMemberCount()));

        int httpStatus = HttpStatus.CREATED.value();
        idempotencyRecordRepository.saveAndFlush(
                new IdempotencyRecord(request.idempotencyKey(), fingerprint, newStay.getId(), httpStatus));
        return new StayOutcome(StayResponse.from(newStay), httpStatus);
    }

    /**
     * 选房规则：剩余床位足够、无障碍匹配，按入住后剩余床位最少、房间号最小稳定选择。
     */
    private java.util.Optional<Room> selectRoom(List<Room> rooms, int memberCount, boolean needsAccessibility) {
        return rooms.stream()
                .filter(room -> room.getAvailableBeds() >= memberCount)
                .filter(room -> !needsAccessibility || room.isAccessible())
                .min(Comparator.comparingInt(Room::getAvailableBeds)
                        .thenComparingInt(Room::getRoomNumber));
    }

    private StayOutcome replay(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "幂等键已被不同内容的请求使用");
        }
        Stay stay = stayRepository.findById(record.getStayId())
                .orElseThrow(() -> BusinessException.conflict("STALE_STAY_STATE", "幂等记录对应的入住不存在"));
        return new StayOutcome(StayResponse.from(stay), record.getHttpStatus());
    }

    private StayOutcome recoverFromConcurrentKey(String idempotencyKey, String fingerprint,
                                                 DataIntegrityViolationException cause) {
        return transactionTemplate.execute(status -> idempotencyRecordRepository.findById(idempotencyKey)
                .map(record -> replay(record, fingerprint))
                .orElseThrow(() -> BusinessException.conflict(
                        "CONCURRENT_MODIFICATION", "并发操作冲突，请重试")));
    }
}
