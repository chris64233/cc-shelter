package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "stays")
public class Stay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shelter_id")
    private Shelter shelter;

    @Column(nullable = false)
    private int memberCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StayStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant endedAt;

    protected Stay() {
    }

    public Stay(Household household, Room room, Shelter shelter, int memberCount) {
        this.household = household;
        this.room = room;
        this.shelter = shelter;
        this.memberCount = memberCount;
        this.status = StayStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public void end() {
        this.status = StayStatus.ENDED;
        this.endedAt = Instant.now();
    }

    /** 合并时新成员并入当前房间，原地增加在住人数 */
    public void addMembers(int count) {
        this.memberCount += count;
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public Room getRoom() {
        return room;
    }

    public Shelter getShelter() {
        return shelter;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public StayStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
