package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 身份核验事件：只追加不修改，idemKey 全局唯一保证核验事件幂等。
 */
@Entity
@Table(name = "identity_verifications")
public class IdentityVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String idemKey;

    @Column(nullable = false, updatable = false)
    private String identityNo;

    /** 核验时成员所在家庭（通常为临时家庭） */
    @Column(nullable = false, updatable = false)
    private String householdNo;

    @Column(nullable = false, updatable = false)
    private String result;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected IdentityVerification() {
    }

    public IdentityVerification(String idemKey, String identityNo, String householdNo, String result) {
        this.idemKey = idemKey;
        this.identityNo = identityNo;
        this.householdNo = householdNo;
        this.result = result;
        this.occurredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getIdentityNo() {
        return identityNo;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public String getResult() {
        return result;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
