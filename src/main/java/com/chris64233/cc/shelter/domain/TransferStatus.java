package com.chris64233.cc.shelter.domain;

/**
 * 家庭跨安置点转移申请的状态：
 * REQUESTED 申请已创建（冻结成员与入住关系），等待目标安置点接受；
 * ACCEPTED  目标安置点已预留房间，等待家庭到达交接（来源床位仍有效）；
 * COMPLETED 到达确认完成，来源入住已结束、目标入住已建立，终态不可撤销；
 * REJECTED  目标安置点拒绝，预留未形成/已释放，来源入住有效，终态；
 * CANCELLED 家庭主动取消，预留已释放，来源入住有效，终态；
 * TIMED_OUT 超过计划到达时间未交接，预留已释放，来源入住有效，终态。
 */
public enum TransferStatus {
    REQUESTED,
    ACCEPTED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    TIMED_OUT;

    /** 仍在流转中的申请：占用“同一家庭同时只能有一笔活动转移”的名额 */
    public boolean isActive() {
        return this == REQUESTED || this == ACCEPTED;
    }
}
