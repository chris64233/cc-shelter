package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Stay;
import com.chris64233.cc.shelter.domain.StayStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StayRepository extends JpaRepository<Stay, Long> {

    Optional<Stay> findByHouseholdIdAndStatus(Long householdId, StayStatus status);

    Optional<Stay> findByHouseholdHouseholdNoAndStatus(String householdNo, StayStatus status);
}
