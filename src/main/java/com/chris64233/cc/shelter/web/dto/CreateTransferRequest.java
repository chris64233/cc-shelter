package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 创建跨安置点家庭整体转移申请（转移业务号 transferNo 幂等）。
 *
 * @param targetRoomNumber 目标房间要求：指定时目标安置点必须预留该房间；为空则由目标按统一规则选房
 * @param plannedArrivalAt 计划到达时间
 * @param externalBusinessNo 外部业务号，原样回显，便于与外部调度系统对账
 */
public record CreateTransferRequest(@NotBlank String transferNo,
                                    @NotBlank String householdNo,
                                    @NotNull Long targetShelterId,
                                    Integer targetRoomNumber,
                                    @NotNull Instant plannedArrivalAt,
                                    @NotBlank String externalBusinessNo) {
}
