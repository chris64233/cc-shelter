package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"shelter_id", "room_number"}))
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shelter_id")
    private Shelter shelter;

    @Column(name = "room_number", nullable = false)
    private int roomNumber;

    @Column(nullable = false)
    private int bedCount;

    @Column(nullable = false)
    private boolean accessible;

    @Column(nullable = false)
    private int occupied;

    /**
     * 已被活动转移申请预留、家庭尚未到达的床位数。
     * 预留与实际占用相互独立：接受转移只增加预留，到达确认时把预留转为占用；
     * 普通入住选房时必须把预留床位视为不可用，防止提前吃掉已承诺给转移家庭的床位。
     */
    @Column(name = "reserved_beds", nullable = false)
    private int reservedBeds;

    protected Room() {
    }

    public Room(Shelter shelter, int roomNumber, int bedCount, boolean accessible) {
        this.shelter = shelter;
        this.roomNumber = roomNumber;
        this.bedCount = bedCount;
        this.accessible = accessible;
        this.occupied = 0;
        this.reservedBeds = 0;
    }

    public int remainingBeds() {
        return bedCount - occupied;
    }

    /** 扣掉已实际占用和已预留后的可分配床位 */
    public int availableBeds() {
        return bedCount - occupied - reservedBeds;
    }

    public Long getId() {
        return id;
    }

    public Shelter getShelter() {
        return shelter;
    }

    public int getRoomNumber() {
        return roomNumber;
    }

    public int getBedCount() {
        return bedCount;
    }

    public boolean isAccessible() {
        return accessible;
    }

    public int getOccupied() {
        return occupied;
    }

    public void setOccupied(int occupied) {
        this.occupied = occupied;
    }

    public int getReservedBeds() {
        return reservedBeds;
    }

    public void setReservedBeds(int reservedBeds) {
        this.reservedBeds = reservedBeds;
    }
}
