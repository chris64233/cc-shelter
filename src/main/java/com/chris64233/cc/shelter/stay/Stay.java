package com.chris64233.cc.shelter.stay;

import com.chris64233.cc.shelter.household.Household;
import com.chris64233.cc.shelter.shelter.Room;
import com.chris64233.cc.shelter.shelter.Shelter;
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
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false)
    private String householdNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shelter_id", nullable = false)
    private Shelter shelter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(nullable = false)
    private int memberCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StayStatus status;

    /**
     * 仅在入住有效期间等于 household.id，结束后置空。
     * 配合唯一约束保证同一家庭至多一条有效入住。
     */
    @Column(unique = true)
    private Long activeHouseholdId;

    @Column(nullable = false)
    private Instant checkedInAt;

    private Instant endedAt;

    protected Stay() {
    }

    public Stay(Household household, Shelter shelter, Room room, int memberCount) {
        this.household = household;
        this.householdNumber = household.getHouseholdNumber();
        this.shelter = shelter;
        this.room = room;
        this.memberCount = memberCount;
        this.status = StayStatus.ACTIVE;
        this.activeHouseholdId = household.getId();
        this.checkedInAt = Instant.now();
    }

    public void endAsTransferred() {
        this.status = StayStatus.TRANSFERRED;
        this.activeHouseholdId = null;
        this.endedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public String getHouseholdNumber() {
        return householdNumber;
    }

    public Shelter getShelter() {
        return shelter;
    }

    public Room getRoom() {
        return room;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public StayStatus getStatus() {
        return status;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
