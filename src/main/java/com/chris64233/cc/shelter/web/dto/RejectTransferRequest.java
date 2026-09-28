package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 目标安置点拒绝转移：释放目标预留（如有），来源入住保持有效。 */
public record RejectTransferRequest(@NotBlank String idempotencyKey,
                                    @NotBlank String transferNo,
                                    String reason) {
}
