package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.TransferRoomReservation;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.TransferRoomReservationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 房间加锁与选择的统一入口。
 *
 * <p>所有房间悲观写锁都按房间主键升序获取，家庭转移/合并也把原房间一并加入同一批锁，
 * 保证任意并发事务之间锁序一致，不会出现锁序倒置死锁。
 * 选择规则沿用“安置后剩余床位最少、房间编号最小”，容量复核时同时计入：
 * 本事务即将释放的床位，以及活动转移已经预留（尚未到达）的床位。
 *
 * <p>预留表在拿到房间行锁之后读取：并发的“目标接受”必须持有同一批房间锁才能写入预留，
 * 因此锁内读到的预留集合与房间占用数是一致快照，不会漏判并发预留。
 */
@Component
public class RoomAllocator {

    private final RoomRepository rooms;
    private final TransferRoomReservationRepository reservations;

    public RoomAllocator(RoomRepository rooms, TransferRoomReservationRepository reservations) {
        this.rooms = rooms;
        this.reservations = reservations;
    }

    public Optional<Room> allocate(Long shelterId, int requiredSize, boolean accessibleRequired) {
        return allocate(shelterId, requiredSize, accessibleRequired, Map.of(), List.of());
    }

    /**
     * @param releasingBedsByRoom 本事务中即将结束的入住释放的床位数（房间 id -> 释放数）
     * @param alsoLockRoomIds     事务中还要修改占用数的其它房间（如原房间），一起按序加锁
     */
    public Optional<Room> allocate(Long shelterId, int requiredSize, boolean accessibleRequired,
                                   Map<Long, Integer> releasingBedsByRoom,
                                   Collection<Long> alsoLockRoomIds) {
        List<Room> locked = lockShelterRooms(shelterId, alsoLockRoomIds);
        Map<Long, Integer> held = heldBeds(shelterId);

        return locked.stream()
                .filter(r -> r.getShelter().getId().equals(shelterId))
                .filter(r -> !accessibleRequired || r.isAccessible())
                .filter(r -> r.getBedCount() - effectiveOccupied(r, releasingBedsByRoom, held) >= 0)
                .filter(r -> r.getBedCount() - effectiveOccupied(r, releasingBedsByRoom, held)
                        >= requiredSize)
                .min(Comparator
                        .comparingInt((Room r) -> r.getBedCount()
                                - effectiveOccupied(r, releasingBedsByRoom, held) - requiredSize)
                        .thenComparingInt(Room::getRoomNumber));
    }

    /**
     * 为一笔活动转移预留房间：在满足容量/无障碍的候选中按统一规则选房，
     * 但**排除已被其它活动转移预留的房间**——同一目标房间不允许两个家庭重复预留，
     * 即使该房间物理床位仍有剩余。容量复核仍计入房间实际占用与其它预留床位。
     */
    public Optional<Room> reserve(Long shelterId, int requiredSize, boolean accessibleRequired) {
        List<Room> locked = lockShelterRooms(shelterId, List.of());
        Map<Long, Integer> held = heldBeds(shelterId);

        return locked.stream()
                .filter(r -> r.getShelter().getId().equals(shelterId))
                .filter(r -> !held.containsKey(r.getId()))
                .filter(r -> !accessibleRequired || r.isAccessible())
                .filter(r -> r.getBedCount() - r.getOccupied() >= requiredSize)
                .min(Comparator
                        .comparingInt((Room r) -> r.getBedCount() - r.getOccupied() - requiredSize)
                        .thenComparingInt(Room::getRoomNumber));
    }

    /**
     * 按主键升序锁定指定安置点内的特定房间，并复核容量与无障碍条件；
     * 该房间已被其它活动转移预留时直接拒绝（同一房间不可重复预留）。
     */
    public Optional<Room> reserveSpecific(Long shelterId, int roomNumber, int requiredSize,
                                          boolean accessibleRequired,
                                          Map<Long, Integer> releasingBedsByRoom,
                                          Collection<Long> alsoLockRoomIds) {
        List<Room> locked = lockShelterRooms(shelterId, alsoLockRoomIds);
        Map<Long, Integer> held = heldBeds(shelterId);

        return locked.stream()
                .filter(r -> r.getShelter().getId().equals(shelterId))
                .filter(r -> r.getRoomNumber() == roomNumber)
                .filter(r -> !held.containsKey(r.getId()))
                .filter(r -> !accessibleRequired || r.isAccessible())
                .filter(r -> r.getBedCount() - effectiveOccupied(r, releasingBedsByRoom, held)
                        >= requiredSize)
                .findFirst();
    }

    private List<Room> lockShelterRooms(Long shelterId, Collection<Long> alsoLockRoomIds) {
        TreeSet<Long> lockIds = new TreeSet<>(rooms.findIdsByShelterId(shelterId));
        lockIds.addAll(alsoLockRoomIds);

        List<Room> locked = new ArrayList<>(lockIds.size());
        for (Long id : lockIds) {
            rooms.findByIdForUpdate(id).ifPresent(locked::add);
        }
        return locked;
    }

    /** 必须在房间行锁内调用：汇总目标安置点各房间已被活动转移预留的床位数 */
    private Map<Long, Integer> heldBeds(Long shelterId) {
        Map<Long, Integer> held = new HashMap<>();
        for (TransferRoomReservation reservation : reservations.findByShelterId(shelterId)) {
            held.merge(reservation.getRoomId(), reservation.getBedCount(), Integer::sum);
        }
        return held;
    }

    private int effectiveOccupied(Room room, Map<Long, Integer> releasing, Map<Long, Integer> held) {
        return room.getOccupied()
                + held.getOrDefault(room.getId(), 0)
                - releasing.getOrDefault(room.getId(), 0);
    }

    /** 按主键升序锁定给定房间，返回锁定后的实体 */
    public List<Room> lockByIds(Collection<Long> roomIds) {
        List<Room> locked = new ArrayList<>();
        for (Long id : new TreeSet<>(roomIds)) {
            rooms.findByIdForUpdate(id).ifPresent(locked::add);
        }
        return locked;
    }
}
