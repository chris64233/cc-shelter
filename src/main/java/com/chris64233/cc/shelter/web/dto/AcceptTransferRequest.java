package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 目标安置点接受转移：业务号幂等（同号同操作返回原结果）。
 * 接受只预留房间、形成待交接状态，不释放来源床位。
 */
public record AcceptTransferRequest(@NotBlank String transferNo) {
}
