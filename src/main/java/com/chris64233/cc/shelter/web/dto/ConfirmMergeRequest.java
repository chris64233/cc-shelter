package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认合并：重新校验时以 expectedTargetStayId 防止基于陈旧状态确认。
 */
public record ConfirmMergeRequest(@NotBlank String idempotencyKey,
                                  @NotBlank String mergeNo,
                                  @NotNull Long expectedTargetStayId) {
}
