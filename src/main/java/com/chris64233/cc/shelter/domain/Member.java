package com.chris64233.cc.shelter.domain;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus verificationStatus;

    /** 走散成员声明的原家庭号，仅临时成员有值 */
    @Column
    private String declaredHouseholdNo;

    protected Member() {
    }

    public Member(String identityNo, int age, boolean needsAccessible) {
        this.identityNo = identityNo;
        this.age = age;
        this.needsAccessible = needsAccessible;
        this.verificationStatus = VerificationStatus.VERIFIED;
    }

    public static Member temporary(String identityNo, int age, boolean needsAccessible, String declaredHouseholdNo) {
        Member member = new Member(identityNo, age, needsAccessible);
        member.verificationStatus = VerificationStatus.UNVERIFIED;
        member.declaredHouseholdNo = declaredHouseholdNo;
        return member;
    }

    public void verify() {
        this.verificationStatus = VerificationStatus.VERIFIED;
    }

    public boolean isVerified() {
        return verificationStatus == VerificationStatus.VERIFIED;
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

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public String getDeclaredHouseholdNo() {
        return declaredHouseholdNo;
    }
}
