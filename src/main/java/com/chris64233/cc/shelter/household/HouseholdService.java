package com.chris64233.cc.shelter.household;

import com.chris64233.cc.shelter.common.BusinessException;
import com.chris64233.cc.shelter.household.dto.HouseholdResponse;
import com.chris64233.cc.shelter.household.dto.RegisterHouseholdRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseholdService {

    private final HouseholdRepository householdRepository;

    public HouseholdService(HouseholdRepository householdRepository) {
        this.householdRepository = householdRepository;
    }

    @Transactional
    public HouseholdResponse register(RegisterHouseholdRequest request) {
        Household household = new Household(request.householdNumber());
        request.members().forEach(member -> household.addMember(
                new HouseholdMember(member.identityNo(), member.age(), member.needsAccessibility())));
        return HouseholdResponse.from(householdRepository.save(household));
    }

    @Transactional(readOnly = true)
    public HouseholdResponse getByNumber(String householdNumber) {
        return householdRepository.findByHouseholdNumber(householdNumber)
                .map(HouseholdResponse::from)
                .orElseThrow(() -> BusinessException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNumber));
    }
}
