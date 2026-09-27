package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 创建家庭合并申请（业务号 mergeNo 幂等）：把若干临时入住并入一个正式家庭；
 * targetShelterId 非空时表示原家庭房间不足，在同一合并事务里把整个家庭迁到该安置点。
 */
public record CreateMergeRequest(@NotBlank String mergeNo,
                                 @NotBlank String targetHouseholdNo,
                                 @NotEmpty List<@NotBlank String> temporaryHouseholdNos,
                                 Long targetShelterId) {
}
