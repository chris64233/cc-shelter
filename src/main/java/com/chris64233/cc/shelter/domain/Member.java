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
import java.time.Instant;

@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    /**
     * 身份标识。合并后旧成员行保留（departedAt 非空），同一身份标识会出现历史行与当前行，
     * 因此“在住成员身份唯一”由服务层保证（不存在两行 departedAt 为 null 的同号成员）。
     */
    @Column(nullable = false)
    private String identityNo;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false)
    private boolean needsAccessible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus verificationStatus;

    /** 离开当前家庭的时间：退住或随合并迁出；null 表示仍在当前家庭 */
    @Column
    private Instant departedAt;

    protected Member() {
    }

    public Member(String identityNo, int age, boolean needsAccessible) {
        this(identityNo, age, needsAccessible, VerificationStatus.NOT_REQUIRED);
    }

    public Member(String identityNo, int age, boolean needsAccessible, VerificationStatus verificationStatus) {
        this.identityNo = identityNo;
        this.age = age;
        this.needsAccessible = needsAccessible;
        this.verificationStatus = verificationStatus;
    }

    public boolean isCurrentlyStaying() {
        return departedAt == null;
    }

    public void markVerified() {
        this.verificationStatus = VerificationStatus.VERIFIED;
    }

    public void markDeparted() {
        this.departedAt = Instant.now();
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

    public Instant getDepartedAt() {
        return departedAt;
    }
}
