package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.repo.RoomRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * 选择规则沿用“安置后剩余床位最少、房间编号最小”，容量复核时计入本事务即将释放的床位。
 * 被活动转移申请整间预留的房间（reservedBeds &gt; 0）对普通入住与其它接受操作都不可选：
 * 一个房间在交接完成前只承诺给一个家庭，杜绝混住与重复预留。
 */
@Component
public class RoomAllocator {

    private final RoomRepository rooms;

    public RoomAllocator(RoomRepository rooms) {
        this.rooms = rooms;
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
        return choose(shelterId, requiredSize, accessibleRequired, releasingBedsByRoom, alsoLockRoomIds,
                false);
    }

    /**
     * 为跨安置点转移预留房间：一间房在交接完成前只能被一笔转移预留
     * （reservedBeds &gt; 0 的房间直接排除），杜绝同一房间被两个家庭重复预留；
     * 容量按床位数扣减当前实际占用计算，本事务不释放任何来源床位（来源床位在到达确认前保持有效）。
     * 普通入住仍可使用预留房间的剩余床位（见 {@link #allocate}），与现有多家庭同住规则一致。
     */
    public Optional<Room> reserve(Long shelterId, int requiredSize, boolean accessibleRequired,
                                  Collection<Long> alsoLockRoomIds) {
        return choose(shelterId, requiredSize, accessibleRequired, Map.of(), alsoLockRoomIds, true);
    }

    private Optional<Room> choose(Long shelterId, int requiredSize, boolean accessibleRequired,
                                  Map<Long, Integer> releasingBedsByRoom,
                                  Collection<Long> alsoLockRoomIds,
                                  boolean excludeReservedRooms) {
        TreeSet<Long> lockIds = new TreeSet<>(rooms.findIdsByShelterId(shelterId));
        lockIds.addAll(alsoLockRoomIds);

        List<Room> locked = new ArrayList<>(lockIds.size());
        for (Long id : lockIds) {
            rooms.findByIdForUpdate(id).ifPresent(locked::add);
        }

        return locked.stream()
                .filter(r -> r.getShelter().getId().equals(shelterId))
                .filter(r -> !accessibleRequired || r.isAccessible())
                .filter(r -> !excludeReservedRooms || r.getReservedBeds() == 0)
                .filter(r -> r.getBedCount() - r.getOccupied()
                        - (excludeReservedRooms ? 0 : r.getReservedBeds())
                        + releasingBedsByRoom.getOrDefault(r.getId(), 0) >= requiredSize)
                .min(Comparator
                        .comparingInt((Room r) -> r.getBedCount() - r.getOccupied()
                                - (excludeReservedRooms ? 0 : r.getReservedBeds())
                                + releasingBedsByRoom.getOrDefault(r.getId(), 0) - requiredSize)
                        .thenComparingInt(Room::getRoomNumber));
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
