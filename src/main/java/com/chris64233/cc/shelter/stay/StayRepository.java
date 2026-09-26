package com.chris64233.cc.shelter.stay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StayRepository extends JpaRepository<Stay, Long> {

    Optional<Stay> findByActiveHouseholdId(Long householdId);
}
