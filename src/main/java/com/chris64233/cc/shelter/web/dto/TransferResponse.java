package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.TransferApplication;
import java.time.Instant;

/** 转移进度：申请全量状态与两端房间、冻结人数、外部业务号、交接结果 */
public record TransferResponse(Long transferApplicationId,
                               String transferNo,
                               String status,
                               String householdNo,
                               Long originShelterId,
                               Long targetShelterId,
                               Integer targetRoomNumber,
                               Long originStayId,
                               Long originRoomId,
                               Long reservedRoomId,
                               int frozenMemberCount,
                               boolean accessibleRequired,
                               Instant plannedArrivalAt,
                               String externalBusinessNo,
                               Instant expiresAt,
                               Long resultStayId,
                               String handoverNo,
                               String rejectReason,
                               Instant createdAt,
                               Instant acceptedAt,
                               Instant arrivedAt,
                               Instant finishedAt,
                               Object frozenMembers) {

    public static TransferResponse of(TransferApplication application, Object frozenMembers) {
        return new TransferResponse(
                application.getId(),
                application.getTransferNo(),
                application.getStatus().name(),
                application.getHouseholdNo(),
                application.getOriginShelterId(),
                application.getTargetShelterId(),
                application.getTargetRoomNumber(),
                application.getOriginStayId(),
                application.getOriginRoomId(),
                application.getReservedRoomId(),
                application.getFrozenMemberCount(),
                application.isFrozenAccessibleRequired(),
                application.getPlannedArrivalAt(),
                application.getExternalBusinessNo(),
                application.getExpiresAt(),
                application.getResultStayId(),
                application.getHandoverNo(),
                application.getRejectReason(),
                application.getCreatedAt(),
                application.getAcceptedAt(),
                application.getArrivedAt(),
                application.getFinishedAt(),
                frozenMembers);
    }
}
