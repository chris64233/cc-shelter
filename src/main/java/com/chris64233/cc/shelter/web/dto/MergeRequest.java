package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record MergeRequest(@NotBlank String idempotencyKey,
                           @NotBlank String targetHouseholdNo,
                           @NotEmpty List<@NotBlank String> temporaryHouseholdNos,
                           Long targetShelterId) {
}
