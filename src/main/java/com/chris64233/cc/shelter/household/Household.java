package com.chris64233.cc.shelter.household;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "households")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String householdNumber;

    @OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HouseholdMember> members = new ArrayList<>();

    protected Household() {
    }

    public Household(String householdNumber) {
        this.householdNumber = householdNumber;
    }

    public void addMember(HouseholdMember member) {
        members.add(member);
        member.assignTo(this);
    }

    public Long getId() {
        return id;
    }

    public String getHouseholdNumber() {
        return householdNumber;
    }

    public List<HouseholdMember> getMembers() {
        return members;
    }
}
