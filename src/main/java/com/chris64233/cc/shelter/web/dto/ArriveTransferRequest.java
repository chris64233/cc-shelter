package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 到达确认（交接）：交接事件号 handoverNo 幂等。
 * expectedOriginStayId 防止基于陈旧状态确认：必须等于申请冻结的来源入住，
 * 且该入住仍是家庭当前有效入住。
 */
public record ArriveTransferRequest(@NotBlank String transferNo,
                                    @NotBlank String handoverNo,
                                    @NotNull Long expectedOriginStayId) {
}
