package com.chris64233.cc.shelter.household.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MemberRequest(
        @NotBlank String identityNo,
        @NotNull @Min(0) @Max(150) Integer age,
        boolean needsAccessibility) {
}
