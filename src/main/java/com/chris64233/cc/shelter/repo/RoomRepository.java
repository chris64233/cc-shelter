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

    @Query("select r.id from Room r where r.shelter.id = :shelterId")
    List<Long> findIdsByShelterId(@Param("shelterId") Long shelterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);
}
