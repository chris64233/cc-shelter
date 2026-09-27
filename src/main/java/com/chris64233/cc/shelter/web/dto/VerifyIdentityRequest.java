package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyIdentityRequest(@NotBlank String idempotencyKey,
                                    @NotBlank String identityNo) {
}
