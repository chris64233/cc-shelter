package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Room;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByShelterIdOrderByRoomNumberAsc(Long shelterId);

    // 只返回 id，避免实体提前进入持久化上下文导致锁定读取时拿到过期占用数
    @Query("select r.id from Room r where r.shelter.id = :shelterId "
            + "and (r.bedCount - r.occupied) >= :size "
            + "and (:accessibleRequired = false or r.accessible = true) "
            + "order by (r.bedCount - r.occupied) asc, r.roomNumber asc")
    List<Long> findCandidateIds(@Param("shelterId") Long shelterId,
                                @Param("size") int size,
                                @Param("accessibleRequired") boolean accessibleRequired);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);
}
