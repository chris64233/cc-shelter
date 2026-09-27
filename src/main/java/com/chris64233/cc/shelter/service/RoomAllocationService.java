package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.repo.RoomRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RoomAllocationService {

    private final RoomRepository rooms;

    public RoomAllocationService(RoomRepository rooms) {
        this.rooms = rooms;
    }

    /**
     * 按“剩余床位最少、房间编号最小”选择候选，并逐个加悲观写锁复核，
     * 保证并发入住不会超卖。
     */
    public Optional<Room> lockSuitableRoom(Long shelterId, int size, boolean accessibleRequired) {
        return lockSuitableRoom(shelterId, size, accessibleRequired, Map.of());
    }

    /**
     * 合并选房：releaseCredit 记录本事务内即将释放的床位（房间 id -> 床位数），
     * 这些床位计入有效剩余，使全家可以迁入临时成员即将腾出的房间。
     * 锁定后按“有效剩余床位最少、房间编号最小”稳定选择并复核。
     */
    public Optional<Room> lockSuitableRoom(Long shelterId, int size, boolean accessibleRequired,
                                           Map<Long, Integer> releaseCredit) {
        int creditTotal = releaseCredit.values().stream().mapToInt(Integer::intValue).sum();
        // size - creditTotal 是可行房间的剩余床位下界，先取超集再逐个锁定复核
        List<Long> candidateIds = rooms.findCandidateIds(shelterId, size - creditTotal, accessibleRequired);
        Room best = null;
        int bestEffective = Integer.MAX_VALUE;
        for (Long candidateId : candidateIds) {
            Room locked = rooms.findByIdForUpdate(candidateId).orElseThrow();
            if (accessibleRequired && !locked.isAccessible()) {
                continue;
            }
            int effective = locked.remainingBeds() + releaseCredit.getOrDefault(candidateId, 0);
            if (effective >= size && (effective < bestEffective
                    || (effective == bestEffective && best != null
                        && locked.getRoomNumber() < best.getRoomNumber()))) {
                best = locked;
                bestEffective = effective;
            }
        }
        return Optional.ofNullable(best);
    }
}
