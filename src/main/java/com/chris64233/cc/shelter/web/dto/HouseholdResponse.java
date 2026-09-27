package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.Member;
import java.util.List;

public record HouseholdResponse(String householdNo,
                                String type,
                                String claimedOriginalHouseholdNo,
                                List<MemberView> members) {

    public record MemberView(String identityNo,
                             int age,
                             boolean needsAccessible,
                             String verificationStatus,
                             boolean currentlyStaying) {
        static MemberView of(Member member) {
            return new MemberView(member.getIdentityNo(), member.getAge(), member.isNeedsAccessible(),
                    member.getVerificationStatus().name(), member.isCurrentlyStaying());
        }
    }

    public static HouseholdResponse of(Household household) {
        return new HouseholdResponse(household.getHouseholdNo(), household.getType().name(),
                household.getClaimedOriginalHouseholdNo(),
                household.getMembers().stream().map(MemberView::of).toList());
    }
}
