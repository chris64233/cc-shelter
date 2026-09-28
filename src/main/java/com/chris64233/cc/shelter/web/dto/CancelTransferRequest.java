package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 家庭取消转移：释放目标预留，来源入住保持有效。 */
public record CancelTransferRequest(@NotBlank String transferNo,
                                    String reason) {
}
