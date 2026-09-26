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

@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    @Column(nullable = false, unique = true)
    private String identityNo;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false)
    private boolean needsAccessible;

    protected Member() {
    }

    public Member(String identityNo, int age, boolean needsAccessible) {
        this.identityNo = identityNo;
        this.age = age;
        this.needsAccessible = needsAccessible;
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    void setHousehold(Household household) {
        this.household = household;
    }

    public String getIdentityNo() {
        return identityNo;
    }

    public int getAge() {
        return age;
    }

    public boolean isNeedsAccessible() {
        return needsAccessible;
    }
}
