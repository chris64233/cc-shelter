package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 单个走散成员以临时家庭身份入住前的登记。
 * 临时家庭必须声明原家庭号，成员初始身份核验状态为 UNVERIFIED。
 */
public record RegisterTemporaryHouseholdRequest(@NotBlank String householdNo,
                                                @NotBlank String claimedOriginalHouseholdNo,
                                                @NotEmpty List<@Valid MemberDto> members) {
}
