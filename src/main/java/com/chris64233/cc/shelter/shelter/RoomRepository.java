package com.chris64233.cc.shelter.shelter;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByShelterIdOrderByRoomNumberAsc(Long shelterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.shelter.id = :shelterId order by r.id")
    List<Room> findAllByShelterIdForUpdate(@Param("shelterId") Long shelterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.shelter.id in :shelterIds order by r.id")
    List<Room> findAllByShelterIdInForUpdate(@Param("shelterIds") Collection<Long> shelterIds);
}
