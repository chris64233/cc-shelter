package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TemporaryCheckInRequest(@NotBlank String idempotencyKey,
                                      @NotBlank String identityNo,
                                      @Min(0) int age,
                                      Boolean needsAccessible,
                                      @NotBlank String declaredHouseholdNo,
                                      @NotNull Long shelterId) {

    public boolean requiresAccessible() {
        return Boolean.TRUE.equals(needsAccessible);
    }
}
