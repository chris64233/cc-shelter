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
 * 家庭跨安置点整体转移申请（两阶段交接）。
 *
 * <p>创建时冻结家庭在住成员清单、来源入住 ID 与来源安置点；目标安置点接受后预留一间
 * 满足容量/无障碍条件的房间，但在到达确认前不释放来源床位；到达确认在同一事务内
 * 一次性结束来源入住、建立目标入住。拒绝/取消/超时只释放目标预留，来源入住保持有效。
 *
 * <p>{@code reservedRoomId} 是活动预留的实时指针：接受时写入、终结时清空，带唯一约束，
 * 数据库层兜底“同一目标房间不能被两个家庭重复预留”；{@code targetRoomId} 保留最后一次
 * 预留房间作为历史信息。
 */
@Entity
@Table(name = "transfer_applications", uniqueConstraints = {
        @UniqueConstraint(name = "uk_transfer_no", columnNames = "transfer_no"),
        @UniqueConstraint(name = "uk_transfer_reserved_room", columnNames = "reserved_room_id")
})
public class TransferApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部业务号：创建幂等键，相同业务号内容一致返回原结果，内容不同 409 */
    @Column(name = "transfer_no", nullable = false, updatable = false)
    private String transferNo;

    /** 申请内容指纹：相同业务号重放但内容不同时返回 409 */
    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "household_no", nullable = false, updatable = false)
    private String householdNo;

    @Column(name = "origin_shelter_id", nullable = false, updatable = false)
    private Long originShelterId;

    @Column(name = "target_shelter_id", nullable = false, updatable = false)
    private Long targetShelterId;

    /** 冻结的来源入住 ID，到达确认时必须仍是当前有效入住 */
    @Column(name = "origin_stay_id", nullable = false, updatable = false)
    private Long originStayId;

    /** 冻结的来源房间（两端占用查询与释放时使用） */
    @Column(name = "origin_room_id", nullable = false, updatable = false)
    private Long originRoomId;

    /** 申请时冻结的家庭在住成员清单（身份标识、核验状态、无障碍需求） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "transfer_application_members",
            joinColumns = @JoinColumn(name = "transfer_application_id"))
    @OrderColumn(name = "position")
    private List<TransferMemberSnapshot> members = new ArrayList<>();

    /** 目标房间要求：最少床位数（= 冻结成员数） */
    @Column(name = "required_bed_count", nullable = false, updatable = false)
    private int requiredBedCount;

    /** 目标房间要求：是否必须支持无障碍 */
    @Column(name = "requires_accessible", nullable = false, updatable = false)
    private boolean requiresAccessible;

    @Column(name = "planned_arrival_at", nullable = false, updatable = false)
    private Instant plannedArrivalAt;

    /** 活动预留指针：ACCEPTED 期间非空，终结时清空；唯一约束防止同房间重复预留 */
    @Column(name = "reserved_room_id")
    private Long reservedRoomId;

    /** 接受时选定的目标房间，保留为历史信息 */
    @Column(name = "target_room_id")
    private Long targetRoomId;

    /** 到达确认的交接事件号（同时是确认操作的幂等键） */
    @Column(name = "handover_no")
    private String handoverNo;

    @Column(name = "result_stay_id")
    private Long resultStayId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** 拒绝原因 / 取消原因等终态说明 */
    @Column(name = "end_reason")
    private String endReason;

    protected TransferApplication() {
    }

    public TransferApplication(String transferNo, String requestFingerprint, String householdNo,
                               Long originShelterId, Long targetShelterId,
                               Long originStayId, Long originRoomId,
                               List<TransferMemberSnapshot> frozenMembers,
                               int requiredBedCount, boolean requiresAccessible,
                               Instant plannedArrivalAt) {
        this.transferNo = transferNo;
        this.requestFingerprint = requestFingerprint;
        this.householdNo = householdNo;
        this.originShelterId = originShelterId;
        this.targetShelterId = targetShelterId;
        this.originStayId = originStayId;
        this.originRoomId = originRoomId;
        this.members = new ArrayList<>(frozenMembers);
        this.requiredBedCount = requiredBedCount;
        this.requiresAccessible = requiresAccessible;
        this.plannedArrivalAt = plannedArrivalAt;
        this.status = TransferStatus.REQUESTED;
        this.createdAt = Instant.now();
    }

    public void markAccepted(Long roomId) {
        this.status = TransferStatus.ACCEPTED;
        this.reservedRoomId = roomId;
        this.targetRoomId = roomId;
        this.acceptedAt = Instant.now();
    }

    public void markRejected() {
        this.status = TransferStatus.REJECTED;
        this.reservedRoomId = null;
        this.endedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = TransferStatus.CANCELLED;
        this.reservedRoomId = null;
        this.endedAt = Instant.now();
    }

    public void markTimedOut() {
        this.status = TransferStatus.TIMED_OUT;
        this.reservedRoomId = null;
        this.endedAt = Instant.now();
    }

    /** 到达确认完成：记录交接事件号与结果入住，清空活动预留指针 */
    public void markArrived(String handoverNo, Long resultStayId) {
        this.status = TransferStatus.COMPLETED;
        this.handoverNo = handoverNo;
        this.resultStayId = resultStayId;
        this.reservedRoomId = null;
        this.arrivedAt = Instant.now();
        this.endedAt = this.arrivedAt;
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

    public Long getOriginStayId() {
        return originStayId;
    }

    public Long getOriginRoomId() {
        return originRoomId;
    }

    public List<TransferMemberSnapshot> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public int getRequiredBedCount() {
        return requiredBedCount;
    }

    public boolean isRequiresAccessible() {
        return requiresAccessible;
    }

    public Instant getPlannedArrivalAt() {
        return plannedArrivalAt;
    }

    public Long getReservedRoomId() {
        return reservedRoomId;
    }

    public Long getTargetRoomId() {
        return targetRoomId;
    }

    public String getHandoverNo() {
        return handoverNo;
    }

    public Long getResultStayId() {
        return resultStayId;
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

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }
}
