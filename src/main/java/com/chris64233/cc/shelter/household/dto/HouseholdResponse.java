package com.chris64233.cc.shelter.household.dto;

import com.chris64233.cc.shelter.household.Household;
import com.chris64233.cc.shelter.household.HouseholdMember;

import java.util.List;

public record HouseholdResponse(Long id, String householdNumber, List<MemberView> members) {

    public record MemberView(String identityNo, int age, boolean needsAccessibility) {

        static MemberView from(HouseholdMember member) {
            return new MemberView(member.getIdentityNo(), member.getAge(), member.isNeedsAccessibility());
        }
    }

    public static HouseholdResponse from(Household household) {
        return new HouseholdResponse(
                household.getId(),
                household.getHouseholdNumber(),
                household.getMembers().stream().map(MemberView::from).toList());
    }
}
