package com.chris64233.cc.shelter.stay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StayEventRepository extends JpaRepository<StayEvent, Long> {

    List<StayEvent> findByHouseholdNumberOrderByIdAsc(String householdNumber);
}
