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
 * 不可变事件：只在入住、转移、合并或退住发生时追加，不提供任何修改入口。
 * 退住事件没有目标房间，目标房间字段允许为空。
 */
@Entity
@Table(name = "stay_events")
public class StayEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long stayId;

    @Column(nullable = false, updatable = false)
    private String householdNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private StayEventType type;

    @Column(updatable = false)
    private Long fromShelterId;

    @Column(updatable = false)
    private Long fromRoomId;

    @Column(updatable = false)
    private Integer fromRoomNumber;

    @Column(updatable = false)
    private Long toShelterId;

    @Column(updatable = false)
    private Long toRoomId;

    @Column(updatable = false)
    private Integer toRoomNumber;

    @Column(nullable = false, updatable = false)
    private int memberCount;

    /** 关联的合并业务号，非合并事件为 null */
    @Column(updatable = false)
    private String mergeNo;

    /** 关联的转移业务号（三阶段家庭转移），即时转移与其它事件为 null */
    @Column(updatable = false)
    private String transferNo;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected StayEvent() {
    }

    public static StayEvent checkIn(Stay stay) {
        StayEvent event = base(stay, StayEventType.CHECK_IN);
        fillTo(event, stay);
        return event;
    }

    public static StayEvent transfer(Stay newStay, Stay previousStay) {
        StayEvent event = base(newStay, StayEventType.TRANSFER);
        fillFrom(event, previousStay);
        fillTo(event, newStay);
        return event;
    }

    /** 三阶段家庭转移到达确认产生的转移事件，带转移业务号 */
    public static StayEvent transfer(Stay newStay, Stay previousStay, String transferNo) {
        StayEvent event = transfer(newStay, previousStay);
        event.transferNo = transferNo;
        return event;
    }

    /** 正式家庭作为合并目标的新入住事件 */
    public static StayEvent merge(Stay newStay, Stay previousStay, String mergeNo) {
        StayEvent event = base(newStay, StayEventType.MERGE);
        fillFrom(event, previousStay);
        fillTo(event, newStay);
        event.mergeNo = mergeNo;
        return event;
    }

    /** 临时家庭被并入正式家庭，原入住结束的事件（保留原临时入住痕迹） */
    public static StayEvent tempMerged(Stay endedStay, Stay targetStay, String mergeNo) {
        StayEvent event = base(endedStay, StayEventType.TEMP_MERGED);
        fillFrom(event, endedStay);
        event.toShelterId = targetStay.getShelter().getId();
        event.toRoomId = targetStay.getRoom().getId();
        event.toRoomNumber = targetStay.getRoom().getRoomNumber();
        event.mergeNo = mergeNo;
        return event;
    }

    public static StayEvent checkout(Stay stay) {
        StayEvent event = base(stay, StayEventType.CHECKOUT);
        fillFrom(event, stay);
        return event;
    }

    private static StayEvent base(Stay stay, StayEventType type) {
        StayEvent event = new StayEvent();
        event.stayId = stay.getId();
        event.householdNo = stay.getHousehold().getHouseholdNo();
        event.type = type;
        event.memberCount = stay.getMemberCount();
        event.occurredAt = Instant.now();
        return event;
    }

    private static void fillFrom(StayEvent event, Stay stay) {
        event.fromShelterId = stay.getShelter().getId();
        event.fromRoomId = stay.getRoom().getId();
        event.fromRoomNumber = stay.getRoom().getRoomNumber();
    }

    private static void fillTo(StayEvent event, Stay stay) {
        event.toShelterId = stay.getShelter().getId();
        event.toRoomId = stay.getRoom().getId();
        event.toRoomNumber = stay.getRoom().getRoomNumber();
    }

    public Long getId() {
        return id;
    }

    public Long getStayId() {
        return stayId;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public StayEventType getType() {
        return type;
    }

    public Long getFromShelterId() {
        return fromShelterId;
    }

    public Long getFromRoomId() {
        return fromRoomId;
    }

    public Integer getFromRoomNumber() {
        return fromRoomNumber;
    }

    public Long getToShelterId() {
        return toShelterId;
    }

    public Long getToRoomId() {
        return toRoomId;
    }

    public Integer getToRoomNumber() {
        return toRoomNumber;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public String getMergeNo() {
        return mergeNo;
    }

    public String getTransferNo() {
        return transferNo;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
