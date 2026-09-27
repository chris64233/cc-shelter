package com.chris64233.cc.shelter.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 家庭合并申请：申请时只做结构校验并冻结参与方名单；
 * 确认时在同一事务内重新校验唯一性、容量、无障碍和期望入住后一次性迁移。
 */
@Entity
@Table(name = "merge_applications", uniqueConstraints = @UniqueConstraint(columnNames = "merge_no"))
public class MergeApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_no", nullable = false, updatable = false)
    private String mergeNo;

    /** 申请内容指纹：相同业务号重放但内容不同时返回 409 */
    @Column(nullable = false, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MergeStatus status;

    @Column(nullable = false, updatable = false)
    private String targetHouseholdNo;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "merge_application_temporary_households",
            joinColumns = @JoinColumn(name = "merge_application_id"))
    @OrderColumn(name = "position")
    @Column(name = "household_no", nullable = false)
    private List<String> temporaryHouseholdNos = new ArrayList<>();

    /** 非空表示合并同时把整个家庭迁到该安置点的新房间 */
    @Column(updatable = false)
    private Long targetShelterId;

    /** 确认时校验的期望正式家庭入住 ID，防止基于陈旧状态合并 */
    @Column(updatable = false)
    private Long expectedTargetStayId;

    private Long resultStayId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant confirmedAt;

    protected MergeApplication() {
    }

    public MergeApplication(String mergeNo, String requestFingerprint, String targetHouseholdNo,
                            List<String> temporaryHouseholdNos,
                            Long targetShelterId, Long expectedTargetStayId) {
        this.mergeNo = mergeNo;
        this.requestFingerprint = requestFingerprint;
        this.targetHouseholdNo = targetHouseholdNo;
        this.temporaryHouseholdNos = new ArrayList<>(temporaryHouseholdNos);
        this.targetShelterId = targetShelterId;
        this.expectedTargetStayId = expectedTargetStayId;
        this.status = MergeStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markMerged(Long resultStayId) {
        this.status = MergeStatus.MERGED;
        this.resultStayId = resultStayId;
        this.confirmedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getMergeNo() {
        return mergeNo;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public MergeStatus getStatus() {
        return status;
    }

    public String getTargetHouseholdNo() {
        return targetHouseholdNo;
    }

    public List<String> getTemporaryHouseholdNos() {
        return Collections.unmodifiableList(temporaryHouseholdNos);
    }

    public Long getTargetShelterId() {
        return targetShelterId;
    }

    public Long getExpectedTargetStayId() {
        return expectedTargetStayId;
    }

    public Long getResultStayId() {
        return resultStayId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
