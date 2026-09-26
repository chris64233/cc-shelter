package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String idemKey;

    @Column(nullable = false)
    private String requestFingerprint;

    @Column(nullable = false)
    private int responseStatus;

    @Lob
    @Column(nullable = false)
    private String responseBody;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idemKey, String requestFingerprint, int responseStatus, String responseBody) {
        this.idemKey = idemKey;
        this.requestFingerprint = requestFingerprint;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = Instant.now();
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
