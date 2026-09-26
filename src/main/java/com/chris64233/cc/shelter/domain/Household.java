package com.chris64233.cc.shelter.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Member> members = new ArrayList<>();

    protected Household() {
    }

    public Household(String householdNo) {
        this.householdNo = householdNo;
    }

    public void addMember(Member member) {
        members.add(member);
        member.setHousehold(this);
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

    public List<Member> getMembers() {
        return Collections.unmodifiableList(members);
    }
}
