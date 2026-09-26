package com.chris64233.cc.shelter.stay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestFingerprint;

    @Column(nullable = false)
    private Long stayId;

    @Column(nullable = false)
    private int httpStatus;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String requestFingerprint, Long stayId, int httpStatus) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.stayId = stayId;
        this.httpStatus = httpStatus;
        this.createdAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Long getStayId() {
        return stayId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
