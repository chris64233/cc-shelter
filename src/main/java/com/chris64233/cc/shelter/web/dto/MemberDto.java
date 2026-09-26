package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record MemberDto(@NotBlank String identityNo,
                        @Min(0) int age,
                        Boolean needsAccessible) {

    public boolean requiresAccessible() {
        return Boolean.TRUE.equals(needsAccessible);
    }
}
