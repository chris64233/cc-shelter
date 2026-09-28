package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 目标房间预留：活动转移（仅 ACCEPTED）为完整家庭预留一间目标房间。
 *
 * <p>{@code room_id} 唯一约束保证同一目标房间不能被两个家庭重复预留；
 * 预留期间床位数计入 {@code RoomAllocator} 的容量判断，但不改动房间实际占用数，
 * 来源床位在到达确认前绝不提前释放。到达确认或拒绝/取消/超时后删除本行。
 */
@Entity
@Table(name = "transfer_room_reservations",
        uniqueConstraints = @UniqueConstraint(columnNames = "room_id"))
public class TransferRoomReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一个活动转移最多预留一间房，故转移申请 ID 也唯一 */
    @Column(name = "transfer_application_id", nullable = false, unique = true, updatable = false)
    private Long transferApplicationId;

    @Column(name = "room_id", nullable = false, updatable = false)
    private Long roomId;

    @Column(nullable = false, updatable = false)
    private Long shelterId;

    @Column(nullable = false, updatable = false)
    private String householdNo;

    @Column(nullable = false, updatable = false)
    private int bedCount;

    protected TransferRoomReservation() {
    }

    public TransferRoomReservation(Long transferApplicationId, Long roomId, Long shelterId,
                                   String householdNo, int bedCount) {
        this.transferApplicationId = transferApplicationId;
        this.roomId = roomId;
        this.shelterId = shelterId;
        this.householdNo = householdNo;
        this.bedCount = bedCount;
    }

    public Long getId() {
        return id;
    }

    public Long getTransferApplicationId() {
        return transferApplicationId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getShelterId() {
        return shelterId;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public int getBedCount() {
        return bedCount;
    }
}
