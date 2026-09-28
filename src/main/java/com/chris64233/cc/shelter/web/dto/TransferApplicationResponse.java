package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.TransferApplication;
import com.chris64233.cc.shelter.domain.TransferMemberSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * 转移申请进度：含状态、两端安置点/房间及其占用快照、冻结成员交接清单与各阶段时间。
 * 房间占用字段是查询时刻的实时快照（活动期间来源房间 occupied 不变，目标房间 reservedBeds 增加）。
 */
public record TransferApplicationResponse(String transferNo,
                                          String status,
                                          String householdNo,
                                          Long originShelterId,
                                          Long targetShelterId,
                                          Long originStayId,
                                          Long originRoomId,
                                          Integer originRoomNumber,
                                          Integer originBedCount,
                                          Integer originOccupied,
                                          Long reservedRoomId,
                                          Long targetRoomId,
                                          Integer targetRoomNumber,
                                          Integer targetBedCount,
                                          Integer targetOccupied,
                                          Integer targetReservedBeds,
                                          int requiredBedCount,
                                          boolean requiresAccessible,
                                          Instant plannedArrivalAt,
                                          String handoverNo,
                                          Long resultStayId,
                                          String endReason,
                                          Instant createdAt,
                                          Instant acceptedAt,
                                          Instant arrivedAt,
                                          Instant endedAt,
                                          List<FrozenMemberView> members) {

    public record FrozenMemberView(Long memberId,
                                   String identityNo,
                                   String verificationStatus,
                                   boolean needsAccessible) {
        static FrozenMemberView of(TransferMemberSnapshot snapshot) {
            return new FrozenMemberView(snapshot.getMemberId(), snapshot.getIdentityNo(),
                    snapshot.getVerificationStatus().name(), snapshot.isNeedsAccessible());
        }
    }

    public static TransferApplicationResponse of(TransferApplication application,
                                                 Room originRoom, Room targetRoom) {
        return new TransferApplicationResponse(
                application.getTransferNo(),
                application.getStatus().name(),
                application.getHouseholdNo(),
                application.getOriginShelterId(),
                application.getTargetShelterId(),
                application.getOriginStayId(),
                application.getOriginRoomId(),
                originRoom == null ? null : originRoom.getRoomNumber(),
                originRoom == null ? null : originRoom.getBedCount(),
                originRoom == null ? null : originRoom.getOccupied(),
                application.getReservedRoomId(),
                application.getTargetRoomId(),
                targetRoom == null ? null : targetRoom.getRoomNumber(),
                targetRoom == null ? null : targetRoom.getBedCount(),
                targetRoom == null ? null : targetRoom.getOccupied(),
                targetRoom == null ? null : targetRoom.getReservedBeds(),
                application.getRequiredBedCount(),
                application.isRequiresAccessible(),
                application.getPlannedArrivalAt(),
                application.getHandoverNo(),
                application.getResultStayId(),
                application.getEndReason(),
                application.getCreatedAt(),
                application.getAcceptedAt(),
                application.getArrivedAt(),
                application.getEndedAt(),
                application.getMembers().stream().map(FrozenMemberView::of).toList());
    }
}
