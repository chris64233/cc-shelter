package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Household;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseholdRepository extends JpaRepository<Household, Long> {

    Optional<Household> findByHouseholdNo(String householdNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.householdNo = :householdNo")
    Optional<Household> findByHouseholdNoForUpdate(@Param("householdNo") String householdNo);
}
