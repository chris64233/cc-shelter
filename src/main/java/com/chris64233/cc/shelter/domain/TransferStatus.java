package com.chris64233.cc.shelter.domain;

public enum TransferStatus {
    /** 申请已创建、已冻结成员与入住关系，等待目标安置点接受 */
    REQUESTED("申请中"),
    /** 目标安置点已接受并预留房间，等待家庭到达交接，来源床位仍有效 */
    ACCEPTED("待交接"),
    /** 已到达确认：来源入住结束、目标入住启用、成员全部迁移完成（终态，不可撤销） */
    ARRIVED("已到达"),
    /** 目标安置点拒绝（终态），预留已释放、来源入住保持有效 */
    REJECTED("拒绝"),
    /** 家庭主动取消（终态），预留已释放、来源入住保持有效 */
    CANCELLED("取消"),
    /** 超过交接时限（终态），预留已释放、来源入住保持有效 */
    EXPIRED("超时");

    private final String label;

    TransferStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
