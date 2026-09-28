package com.chris64233.cc.shelter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 家庭活动转移槽位：一个家庭同一时间最多存在一笔活动转移（REQUESTED/ACCEPTED）。
 *
 * <p>以家庭号为行标识，创建申请时插入、转移到达终态或异常终态后删除；
 * 家庭行悲观锁保证申请创建与并发的合并确认/退住/即时转移串行化，
 * 主键冲突作为最后兜底，杜绝同一家庭出现两笔活动转移。
 */
@Entity
@Table(name = "transfer_activity_slots")
public class TransferActivitySlot {

    /** 家庭号即槽位主键 */
    @Id
    @Column(name = "household_no", nullable = false, updatable = false)
    private String householdNo;

    @Column(name = "transfer_application_id", nullable = false)
    private Long transferApplicationId;

    protected TransferActivitySlot() {
    }

    public TransferActivitySlot(String householdNo, Long transferApplicationId) {
        this.householdNo = householdNo;
        this.transferApplicationId = transferApplicationId;
    }

    public String getHouseholdNo() {
        return householdNo;
    }

    public Long getTransferApplicationId() {
        return transferApplicationId;
    }
}
