package com.chris64233.cc.shelter.stay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stay_events")
public class StayEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String householdNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StayEventType type;

    private Long fromShelterId;

    private Long fromRoomId;

    private Long toShelterId;

    private Long toRoomId;

    @Column(nullable = false)
    private int memberCount;

    @Column(nullable = false)
    private Instant occurredAt;

    protected StayEvent() {
    }

    private StayEvent(String householdNumber, StayEventType type,
                      Long fromShelterId, Long fromRoomId,
                      Long toShelterId, Long toRoomId, int memberCount) {
        this.householdNumber = householdNumber;
        this.type = type;
        this.fromShelterId = fromShelterId;
        this.fromRoomId = fromRoomId;
        this.toShelterId = toShelterId;
        this.toRoomId = toRoomId;
        this.memberCount = memberCount;
        this.occurredAt = Instant.now();
    }

    public static StayEvent checkIn(String householdNumber, Long shelterId, Long roomId, int memberCount) {
        return new StayEvent(householdNumber, StayEventType.CHECK_IN, null, null, shelterId, roomId, memberCount);
    }

    public static StayEvent transfer(String householdNumber,
                                     Long fromShelterId, Long fromRoomId,
                                     Long toShelterId, Long toRoomId, int memberCount) {
        return new StayEvent(householdNumber, StayEventType.TRANSFER,
                fromShelterId, fromRoomId, toShelterId, toRoomId, memberCount);
    }

    public Long getId() {
        return id;
    }

    public String getHouseholdNumber() {
        return householdNumber;
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

    public Long getToShelterId() {
        return toShelterId;
    }

    public Long getToRoomId() {
        return toRoomId;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
