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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 家庭跨安置点整体转移申请。
 *
 * <p>三阶段：{@link TransferStatus#REQUESTED}（申请已冻结成员与入住关系）→
 * {@link TransferStatus#ACCEPTED}（目标已预留房间，来源床位仍有效）→
 * {@link TransferStatus#ARRIVED}（到达确认，两端入住原子切换，终态不可撤销）。
 * 拒绝/取消/超时为异常终态，只释放目标预留，来源入住始终保持有效。
 */
@Entity
@Table(name = "transfer_applications", uniqueConstraints = @UniqueConstraint(columnNames = "transfer_no"))
public class TransferApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_no", nullable = false, updatable = false)
    private String transferNo;

    /** 申请内容指纹：相同转移业务号重放但内容不同时返回 409 */
    @Column(nullable = false, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(nullable = false, updatable = false)
    private String householdNo;

    @Column(nullable = false, updatable = false)
    private Long originShelterId;

    @Column(nullable = false, updatable = false)
    private Long targetShelterId;

    /** 目标房间要求：指定房间号时接受方必须预留该房间；null 表示由目标按统一规则选房 */
    @Column(updatable = false)
    private Integer targetRoomNumber;

    /** 冻结时的在住成员数，目标预留与到达确认都以该容量复核 */
    @Column(nullable = false, updatable = false)
    private int frozenMemberCount;

    /** 冻结时家庭是否有无障碍需求 */
    @Column(nullable = false, updatable = false)
    private boolean frozenAccessibleRequired;

    /** 冻结的在住成员清单（身份标识、年龄、无障碍需求、核验状态快照） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "transfer_frozen_members",
            joinColumns = @JoinColumn(name = "transfer_application_id"))
    @OrderColumn(name = "position")
    private List<FrozenMember> frozenMembers = new ArrayList<>();

    /** 冻结的来源有效入住 ID，到达确认时必须仍是该入住 */
    @Column(nullable = false, updatable = false)
    private Long originStayId;

    @Column(nullable = false, updatable = false)
    private Long originRoomId;

    @Column(nullable = false, updatable = false)
    private Instant plannedArrivalAt;

    /** 外部业务号：外部调度/转运系统的关联单号，原样留存用于对账 */
    @Column(nullable = false, updatable = false)
    private String externalBusinessNo;

    /** 交接时限：超过后申请可被置为 EXPIRED 并释放预留（等于计划到达时间） */
    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    /** 接受后为家庭预留的目标房间；异常终态时预留已释放，值保留用于审计 */
    private Long reservedRoomId;

    private Long resultStayId;

    /** 到达确认（交接）事件号，全局唯一；相同事件号 + 相同内容返回首次结果 */
    @Column(unique = true)
    private String handoverNo;

    @Column
    private String rejectReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant acceptedAt;
    private Instant arrivedAt;
    private Instant finishedAt;

    protected TransferApplication() {
    }

    public TransferApplication(String transferNo, String requestFingerprint, String householdNo,
                               Long originShelterId, Long targetShelterId, Integer targetRoomNumber,
                               List<FrozenMember> frozenMembers, boolean accessibleRequired,
                               Long originStayId, Long originRoomId,
                               Instant plannedArrivalAt, String externalBusinessNo,
                               Instant expiresAt) {
        this.transferNo = transferNo;
        this.requestFingerprint = requestFingerprint;
        this.householdNo = householdNo;
        this.originShelterId = originShelterId;
        this.targetShelterId = targetShelterId;
        this.targetRoomNumber = targetRoomNumber;
        this.frozenMembers = new ArrayList<>(frozenMembers);
        this.frozenMemberCount = frozenMembers.size();
        this.frozenAccessibleRequired = accessibleRequired;
        this.originStayId = originStayId;
        this.originRoomId = originRoomId;
        this.plannedArrivalAt = plannedArrivalAt;
        this.externalBusinessNo = externalBusinessNo;
        this.expiresAt = expiresAt;
        this.status = TransferStatus.REQUESTED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public void markAccepted(Long reservedRoomId) {
        this.status = TransferStatus.ACCEPTED;
        this.reservedRoomId = reservedRoomId;
        this.acceptedAt = Instant.now();
    }

    public void markArrived(String handoverNo, Long resultStayId) {
        this.status = TransferStatus.ARRIVED;
        this.handoverNo = handoverNo;
        this.resultStayId = resultStayId;
        this.arrivedAt = Instant.now();
        this.finishedAt = this.arrivedAt;
    }

    public void markRejected(String reason) {
        this.status = TransferStatus.REJECTED;
        this.rejectReason = reason;
        this.finishedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = TransferStatus.CANCELLED;
        this.finishedAt = Instant.now();
    }

    public void markExpired() {
        this.status = TransferStatus.EXPIRED;
        this.finishedAt = Instant.now();
    }

    public boolean isActive() {
        return status == TransferStatus.REQUESTED || status == TransferStatus.ACCEPTED;
    }

    public boolean isExpirable(Instant now) {
        return isActive() && !now.isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getTransferNo() {
        return transferNo;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public Long getOriginShelterId() {
        return originShelterId;
    }

    public Long getTargetShelterId() {
        return targetShelterId;
    }

    public Integer getTargetRoomNumber() {
        return targetRoomNumber;
    }

    public int getFrozenMemberCount() {
        return frozenMemberCount;
    }

    public boolean isFrozenAccessibleRequired() {
        return frozenAccessibleRequired;
    }

    public List<FrozenMember> getFrozenMembers() {
        return Collections.unmodifiableList(frozenMembers);
    }

    public Long getOriginStayId() {
        return originStayId;
    }

    public Long getOriginRoomId() {
        return originRoomId;
    }

    public Instant getPlannedArrivalAt() {
        return plannedArrivalAt;
    }

    public String getExternalBusinessNo() {
        return externalBusinessNo;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getReservedRoomId() {
        return reservedRoomId;
    }

    public Long getResultStayId() {
        return resultStayId;
    }

    public String getHandoverNo() {
        return handoverNo;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
