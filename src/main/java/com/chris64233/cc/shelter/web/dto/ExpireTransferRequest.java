package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 单笔转移超时处理：超过交接时限后释放目标预留，来源入住保持有效。 */
public record ExpireTransferRequest(@NotBlank String idempotencyKey,
                                    @NotBlank String transferNo) {
}
