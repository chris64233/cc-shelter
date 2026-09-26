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
 * 不可变事件：只在入住或转移发生时追加，不提供任何修改入口。
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

    @Column(nullable = false, updatable = false)
    private Long toShelterId;

    @Column(nullable = false, updatable = false)
    private Long toRoomId;

    @Column(nullable = false, updatable = false)
    private int toRoomNumber;

    @Column(nullable = false, updatable = false)
    private int memberCount;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected StayEvent() {
    }

    public static StayEvent checkIn(Stay stay) {
        StayEvent event = new StayEvent();
        event.stayId = stay.getId();
        event.householdNo = stay.getHousehold().getHouseholdNo();
        event.type = StayEventType.CHECK_IN;
        event.toShelterId = stay.getShelter().getId();
        event.toRoomId = stay.getRoom().getId();
        event.toRoomNumber = stay.getRoom().getRoomNumber();
        event.memberCount = stay.getMemberCount();
        event.occurredAt = Instant.now();
        return event;
    }

    public static StayEvent transfer(Stay newStay, Stay previousStay) {
        StayEvent event = new StayEvent();
        event.stayId = newStay.getId();
        event.householdNo = newStay.getHousehold().getHouseholdNo();
        event.type = StayEventType.TRANSFER;
        event.fromShelterId = previousStay.getShelter().getId();
        event.fromRoomId = previousStay.getRoom().getId();
        event.fromRoomNumber = previousStay.getRoom().getRoomNumber();
        event.toShelterId = newStay.getShelter().getId();
        event.toRoomId = newStay.getRoom().getId();
        event.toRoomNumber = newStay.getRoom().getRoomNumber();
        event.memberCount = newStay.getMemberCount();
        event.occurredAt = Instant.now();
        return event;
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

    public int getToRoomNumber() {
        return toRoomNumber;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
