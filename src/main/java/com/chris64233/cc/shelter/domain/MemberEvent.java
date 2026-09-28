package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 成员维度的不可变事件：记录临时入住、身份核验、家庭关系变化（合并/退住）以及当时所在房间，
 * 与按家庭记录的 {@link StayEvent} 互补，可还原单个成员的身份确认过程与房间迁移链。
 */
@Entity
@Table(name = "member_events")
public class MemberEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String identityNo;

    @Column(nullable = false, updatable = false)
    private Long memberId;

    @Column(updatable = false)
    private Long stayId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MemberEventType type;

    @Column(updatable = false)
    private String fromHouseholdNo;

    @Column(updatable = false)
    private String toHouseholdNo;

    @Column(updatable = false)
    private Long shelterId;

    @Column(updatable = false)
    private Long roomId;

    @Column(updatable = false)
    private Integer roomNumber;

    @Column(updatable = false)
    private String mergeNo;

    @Column(updatable = false)
    private String transferNo;

    @Column(updatable = false)
    private String handoverNo;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private VerificationStatus verificationStatus;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected MemberEvent() {
    }

    public static MemberEvent temporaryCheckIn(Member member, Stay stay) {
        MemberEvent event = base(member, MemberEventType.TEMPORARY_CHECK_IN, stay);
        event.toHouseholdNo = stay.getHousehold().getHouseholdNo();
        fillRoom(event, stay);
        event.verificationStatus = VerificationStatus.UNVERIFIED;
        return event;
    }

    public static MemberEvent verified(Member member) {
        MemberEvent event = base(member, MemberEventType.IDENTITY_VERIFIED, null);
        event.toHouseholdNo = member.getHousehold().getHouseholdNo();
        event.verificationStatus = VerificationStatus.VERIFIED;
        return event;
    }

    public static MemberEvent merged(Member member, Household from, Stay newStay, String mergeNo) {
        MemberEvent event = base(member, MemberEventType.MERGED, newStay);
        event.fromHouseholdNo = from.getHouseholdNo();
        event.toHouseholdNo = newStay.getHousehold().getHouseholdNo();
        fillRoom(event, newStay);
        event.mergeNo = mergeNo;
        event.verificationStatus = VerificationStatus.VERIFIED;
        return event;
    }

    public static MemberEvent checkout(Member member, Stay stay) {
        MemberEvent event = base(member, MemberEventType.CHECKOUT, stay);
        event.fromHouseholdNo = stay.getHousehold().getHouseholdNo();
        event.shelterId = stay.getShelter().getId();
        event.roomId = stay.getRoom().getId();
        event.roomNumber = stay.getRoom().getRoomNumber();
        event.verificationStatus = member.getVerificationStatus();
        return event;
    }

    /** 随家庭跨安置点转移到达：记录原/新房间与转移业务号、交接事件号 */
    public static MemberEvent transferred(Member member, Stay previousStay, Stay newStay,
                                          String transferNo, String handoverNo) {
        MemberEvent event = base(member, MemberEventType.TRANSFERRED, newStay);
        event.fromHouseholdNo = previousStay.getHousehold().getHouseholdNo();
        event.toHouseholdNo = newStay.getHousehold().getHouseholdNo();
        event.fillTransferRooms(previousStay, newStay);
        event.transferNo = transferNo;
        event.handoverNo = handoverNo;
        event.verificationStatus = member.getVerificationStatus();
        return event;
    }

    private void fillTransferRooms(Stay previousStay, Stay newStay) {
        // shelterId/roomId/roomNumber 记录到达后的目标房间（与 MERGED 一致），
        // 原房间通过同序号的家庭 StayEvent 的 from* 字段查询
        this.shelterId = newStay.getShelter().getId();
        this.roomId = newStay.getRoom().getId();
        this.roomNumber = newStay.getRoom().getRoomNumber();
    }

    private static MemberEvent base(Member member, MemberEventType type, Stay stay) {
        MemberEvent event = new MemberEvent();
        event.identityNo = member.getIdentityNo();
        event.memberId = member.getId();
        event.stayId = stay == null ? null : stay.getId();
        event.type = type;
        event.occurredAt = Instant.now();
        return event;
    }

    private static void fillRoom(MemberEvent event, Stay stay) {
        event.shelterId = stay.getShelter().getId();
        event.roomId = stay.getRoom().getId();
        event.roomNumber = stay.getRoom().getRoomNumber();
    }

    public Long getId() {
        return id;
    }

    public String getIdentityNo() {
        return identityNo;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getStayId() {
        return stayId;
    }

    public MemberEventType getType() {
        return type;
    }

    public String getFromHouseholdNo() {
        return fromHouseholdNo;
    }

    public String getToHouseholdNo() {
        return toHouseholdNo;
    }

    public Long getShelterId() {
        return shelterId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Integer getRoomNumber() {
        return roomNumber;
    }

    public String getMergeNo() {
        return mergeNo;
    }

    public String getTransferNo() {
        return transferNo;
    }

    public String getHandoverNo() {
        return handoverNo;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
