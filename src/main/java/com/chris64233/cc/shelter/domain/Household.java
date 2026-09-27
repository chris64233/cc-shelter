package com.chris64233.cc.shelter.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "households")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String householdNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HouseholdType type;

    @OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Member> members = new ArrayList<>();

    protected Household() {
    }

    public Household(String householdNo) {
        this(householdNo, HouseholdType.FORMAL);
    }

    private Household(String householdNo, HouseholdType type) {
        this.householdNo = householdNo;
        this.type = type;
    }

    public static Household temporary(String householdNo) {
        return new Household(householdNo, HouseholdType.TEMPORARY);
    }

    public void addMember(Member member) {
        members.add(member);
        member.setHousehold(this);
    }

    /**
     * 把成员从原家庭摘出。调用方需先把成员加入新家庭，避免 orphanRemoval 在 flush 时误删。
     */
    public void removeMember(Member member) {
        members.remove(member);
    }

    public boolean isTemporary() {
        return type == HouseholdType.TEMPORARY;
    }

    public boolean needsAccessibleRoom() {
        return members.stream().anyMatch(Member::isNeedsAccessible);
    }

    public Long getId() {
        return id;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public HouseholdType getType() {
        return type;
    }

    public List<Member> getMembers() {
        return Collections.unmodifiableList(members);
    }
}
