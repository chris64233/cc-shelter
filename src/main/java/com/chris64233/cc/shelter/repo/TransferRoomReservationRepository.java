package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.TransferRoomReservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRoomReservationRepository extends JpaRepository<TransferRoomReservation, Long> {

    Optional<TransferRoomReservation> findByTransferApplicationId(Long transferApplicationId);

    boolean existsByRoomId(Long roomId);

    List<TransferRoomReservation> findByShelterId(Long shelterId);

    @Modifying
    @Query("delete from TransferRoomReservation r where r.transferApplicationId = :applicationId")
    int deleteByTransferApplicationId(@Param("applicationId") Long applicationId);
}
