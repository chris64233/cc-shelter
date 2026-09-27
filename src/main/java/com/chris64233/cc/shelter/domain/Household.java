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

    /** 临时家庭声明的原家庭号；正式家庭为 null */
    @Column
    private String claimedOriginalHouseholdNo;

    @OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Member> members = new ArrayList<>();

    protected Household() {
    }

    public Household(String householdNo) {
        this(householdNo, HouseholdType.FORMAL, null);
    }

    public Household(String householdNo, HouseholdType type, String claimedOriginalHouseholdNo) {
        this.householdNo = householdNo;
        this.type = type;
        this.claimedOriginalHouseholdNo = claimedOriginalHouseholdNo;
    }

    public void addMember(Member member) {
        members.add(member);
        member.setHousehold(this);
    }

    public boolean needsAccessibleRoom() {
        return members.stream().filter(Member::isCurrentlyStaying).anyMatch(Member::isNeedsAccessible);
    }

    /** 当前仍在住（未退住、未随合并迁出）的成员数 */
    public int stayingMemberCount() {
        return (int) members.stream().filter(Member::isCurrentlyStaying).count();
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

    public boolean isTemporary() {
        return type == HouseholdType.TEMPORARY;
    }

    public String getClaimedOriginalHouseholdNo() {
        return claimedOriginalHouseholdNo;
    }

    public List<Member> getMembers() {
        return Collections.unmodifiableList(members);
    }
}
