package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TransferRequest(@NotBlank String idempotencyKey,
                              @NotBlank String householdNo,
                              @NotNull Long targetShelterId,
                              @NotNull Long expectedStayId) {
}
