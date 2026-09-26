package com.chris64233.cc.shelter.household;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HouseholdRepository extends JpaRepository<Household, Long> {

    Optional<Household> findByHouseholdNumber(String householdNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.householdNumber = :householdNumber")
    Optional<Household> findByHouseholdNumberForUpdate(@Param("householdNumber") String householdNumber);
}
