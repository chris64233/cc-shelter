package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.StayEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StayEventRepository extends JpaRepository<StayEvent, Long> {

    List<StayEvent> findByHouseholdNoOrderByIdAsc(String householdNo);
}
