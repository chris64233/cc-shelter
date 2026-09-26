package com.chris64233.cc.shelter.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "household_members")
public class HouseholdMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, unique = true)
    private String identityNo;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false)
    private boolean needsAccessibility;

    protected HouseholdMember() {
    }

    public HouseholdMember(String identityNo, int age, boolean needsAccessibility) {
        this.identityNo = identityNo;
        this.age = age;
        this.needsAccessibility = needsAccessibility;
    }

    void assignTo(Household household) {
        this.household = household;
    }

    public Long getId() {
        return id;
    }

    public String getIdentityNo() {
        return identityNo;
    }

    public int getAge() {
        return age;
    }

    public boolean isNeedsAccessibility() {
        return needsAccessibility;
    }
}
