package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record RegisterHouseholdRequest(@NotBlank String householdNo,
                                       @NotEmpty List<@Valid MemberDto> members) {
}
