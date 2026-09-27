package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.Member;

public record MemberResponse(String identityNo,
                             int age,
                             boolean needsAccessible,
                             String verificationStatus,
                             String declaredHouseholdNo) {

    public static MemberResponse of(Member member) {
        return new MemberResponse(member.getIdentityNo(), member.getAge(), member.isNeedsAccessible(),
                member.getVerificationStatus().name(), member.getDeclaredHouseholdNo());
    }
}
