package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StayRepository extends JpaRepository<Stay, Long> {

    Optional<Stay> findByHouseholdIdAndStatus(Long householdId, StayStatus status);

    Optional<Stay> findByHouseholdHouseholdNoAndStatus(String householdNo, StayStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stay s where s.id = :id")
    Optional<Stay> findByIdForUpdate(@Param("id") Long id);
}
