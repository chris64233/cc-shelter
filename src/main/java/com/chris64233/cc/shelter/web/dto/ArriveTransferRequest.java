package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 到达确认（交接）：以交接事件号 handoverNo 幂等。
 * 确认事务内重新核对冻结清单与期望来源入住，任一变化则整笔失败，
 * 成功时一次性结束来源入住、启用目标入住并完成全部成员交接。
 */
public record ArriveTransferRequest(@NotBlank String handoverNo,
                                    @NotBlank String transferNo,
                                    @NotNull Long expectedOriginStayId) {
}
