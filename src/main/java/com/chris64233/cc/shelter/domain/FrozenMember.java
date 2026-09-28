package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * 转移申请创建时冻结的单个成员快照：身份标识、年龄、无障碍需求与核验状态。
 * 到达确认时逐项与当前在住清单重新核对，任何变化都使旧确认失败。
 */
@Embeddable
public class FrozenMember {

    @Column(name = "identity_no", nullable = false)
    private String identityNo;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false)
    private boolean needsAccessible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus verificationStatus;

    protected FrozenMember() {
    }

    public FrozenMember(String identityNo, int age, boolean needsAccessible,
                        VerificationStatus verificationStatus) {
        this.identityNo = identityNo;
        this.age = age;
        this.needsAccessible = needsAccessible;
        this.verificationStatus = verificationStatus;
    }

    public static FrozenMember of(Member member) {
        return new FrozenMember(member.getIdentityNo(), member.getAge(), member.isNeedsAccessible(),
                member.getVerificationStatus());
    }

    /** 与当前在住成员比对：身份标识、年龄、无障碍需求、核验状态必须全部一致 */
    public boolean matches(Member member) {
        return identityNo.equals(member.getIdentityNo())
                && age == member.getAge()
                && needsAccessible == member.isNeedsAccessible()
                && verificationStatus == member.getVerificationStatus();
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
}
