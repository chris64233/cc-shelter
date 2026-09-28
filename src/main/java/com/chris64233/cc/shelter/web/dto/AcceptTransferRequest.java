package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 目标安置点接受转移：接受只形成待交接（ACCEPTED）状态并预留房间，
 * 不提前释放来源床位。幂等键 + 转移业务号。
 */
public record AcceptTransferRequest(@NotBlank String idempotencyKey,
                                    @NotBlank String transferNo) {
}
