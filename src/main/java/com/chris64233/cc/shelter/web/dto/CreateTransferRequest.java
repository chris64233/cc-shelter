package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 创建跨安置点家庭转移申请（外部业务号 transferNo 幂等）：
 * 以正式家庭为单位，记录目标安置点、目标房间要求、计划到达时间。
 */
public record CreateTransferRequest(@NotBlank String transferNo,
                                    @NotBlank String householdNo,
                                    @NotNull Long targetShelterId,
                                    @Positive Integer requiredBedCount,
                                    Boolean requiresAccessible,
                                    @NotNull Instant plannedArrivalAt) {

    public boolean isRequiresAccessible() {
        return Boolean.TRUE.equals(requiresAccessible);
    }
}
