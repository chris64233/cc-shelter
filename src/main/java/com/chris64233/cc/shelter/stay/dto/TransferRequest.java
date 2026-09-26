package com.chris64233.cc.shelter.stay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TransferRequest(
        @NotBlank String idempotencyKey,
        @NotNull Long targetShelterId) {
}
