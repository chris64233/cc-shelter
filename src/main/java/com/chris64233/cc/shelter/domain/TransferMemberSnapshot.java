package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * 转移申请创建时冻结的单个成员快照。
 * 到达确认必须逐项重新核对：成员仍在同一家庭且未离开、身份核验状态未变化；
 * 任何成员发生退住或被合并迁出，旧的到达确认即失败。
 */
@Embeddable
public class TransferMemberSnapshot {

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "identity_no", nullable = false, updatable = false)
    private String identityNo;

    @Column(name = "verification_status", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    private VerificationStatus verificationStatus;

    @Column(name = "needs_accessible", nullable = false, updatable = false)
    private boolean needsAccessible;

    protected TransferMemberSnapshot() {
    }

    public TransferMemberSnapshot(Member member) {
        this.memberId = member.getId();
        this.identityNo = member.getIdentityNo();
        this.verificationStatus = member.getVerificationStatus();
        this.needsAccessible = member.isNeedsAccessible();
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getIdentityNo() {
        return identityNo;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public boolean isNeedsAccessible() {
        return needsAccessible;
    }
}
