package com.chris64233.cc.shelter.stay;

import com.chris64233.cc.shelter.common.BusinessException;
import com.chris64233.cc.shelter.household.Household;
import com.chris64233.cc.shelter.household.HouseholdRepository;
import com.chris64233.cc.shelter.stay.dto.StayEventResponse;
import com.chris64233.cc.shelter.stay.dto.StayResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StayQueryService {

    private final HouseholdRepository householdRepository;
    private final StayRepository stayRepository;
    private final StayEventRepository stayEventRepository;

    public StayQueryService(HouseholdRepository householdRepository,
                            StayRepository stayRepository,
                            StayEventRepository stayEventRepository) {
        this.householdRepository = householdRepository;
        this.stayRepository = stayRepository;
        this.stayEventRepository = stayEventRepository;
    }

    @Transactional(readOnly = true)
    public StayResponse currentStay(String householdNumber) {
        Household household = householdRepository.findByHouseholdNumber(householdNumber)
                .orElseThrow(() -> BusinessException.notFound(
                        "HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNumber));
        return stayRepository.findByActiveHouseholdId(household.getId())
                .map(StayResponse::from)
                .orElseThrow(() -> BusinessException.notFound(
                        "NO_ACTIVE_STAY", "家庭当前没有有效入住: " + householdNumber));
    }

    @Transactional(readOnly = true)
    public List<StayEventResponse> events(String householdNumber) {
        if (householdRepository.findByHouseholdNumber(householdNumber).isEmpty()) {
            throw BusinessException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNumber);
        }
        return stayEventRepository.findByHouseholdNumberOrderByIdAsc(householdNumber).stream()
                .map(StayEventResponse::from)
                .toList();
    }
}
