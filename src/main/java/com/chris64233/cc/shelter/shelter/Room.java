package com.chris64233.cc.shelter.shelter;

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
    @JoinColumn(name = "shelter_id", nullable = false)
    private Shelter shelter;

    @Column(name = "room_number", nullable = false)
    private int roomNumber;

    @Column(nullable = false)
    private int bedCapacity;

    @Column(nullable = false)
    private boolean accessible;

    @Column(nullable = false)
    private int occupiedBeds;

    protected Room() {
    }

    public Room(Shelter shelter, int roomNumber, int bedCapacity, boolean accessible) {
        this.shelter = shelter;
        this.roomNumber = roomNumber;
        this.bedCapacity = bedCapacity;
        this.accessible = accessible;
        this.occupiedBeds = 0;
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

    public int getBedCapacity() {
        return bedCapacity;
    }

    public boolean isAccessible() {
        return accessible;
    }

    public int getOccupiedBeds() {
        return occupiedBeds;
    }

    public int getAvailableBeds() {
        return bedCapacity - occupiedBeds;
    }

    public void occupy(int beds) {
        this.occupiedBeds += beds;
    }

    public void release(int beds) {
        this.occupiedBeds -= beds;
    }
}
