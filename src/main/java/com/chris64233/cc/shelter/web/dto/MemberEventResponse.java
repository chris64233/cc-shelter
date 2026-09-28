package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.MemberEvent;
import java.time.Instant;

/**
 * 成员审计事件：可展示身份确认（IDENTITY_VERIFIED）、家庭关系变化（MERGED/CHECKOUT）
 * 与当时所在房间，多个事件按序即为该成员的完整房间迁移链。
 * TRANSFER 事件记录跨安置点迁移的来源房间（shelterId/roomId）与目标房间（to* 字段）。
 */
public record MemberEventResponse(Long eventId,
                                  String identityNo,
                                  Long memberId,
                                  Long stayId,
                                  String type,
                                  String fromHouseholdNo,
                                  String toHouseholdNo,
                                  Long shelterId,
                                  Long roomId,
                                  Integer roomNumber,
                                  Long toShelterId,
                                  Long toRoomId,
                                  Integer toRoomNumber,
                                  String mergeNo,
                                  String transferNo,
                                  String verificationStatus,
                                  Instant occurredAt) {

    public static MemberEventResponse of(MemberEvent event) {
        return new MemberEventResponse(event.getId(), event.getIdentityNo(), event.getMemberId(),
                event.getStayId(), event.getType().name(), event.getFromHouseholdNo(),
                event.getToHouseholdNo(), event.getShelterId(), event.getRoomId(),
                event.getRoomNumber(), event.getToShelterId(), event.getToRoomId(),
                event.getToRoomNumber(), event.getMergeNo(), event.getTransferNo(),
                event.getVerificationStatus() == null ? null : event.getVerificationStatus().name(),
                event.getOccurredAt());
    }
}
