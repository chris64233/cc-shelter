package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 目标安置点拒绝转移：预留未形成，申请终结，来源入住保持有效。 */
public record RejectTransferRequest(@NotBlank String transferNo,
                                    String reason) {
}
