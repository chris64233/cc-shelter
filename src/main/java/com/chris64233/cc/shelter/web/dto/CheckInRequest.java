package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CheckInRequest(@NotBlank String idempotencyKey,
                             @NotBlank String householdNo,
                             @NotNull Long shelterId) {
}
