package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.TransferApplication;
import com.chris64233.cc.shelter.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferApplicationRepository extends JpaRepository<TransferApplication, Long> {

    Optional<TransferApplication> findByTransferNo(String transferNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferApplication t where t.transferNo = :transferNo")
    Optional<TransferApplication> findByTransferNoForUpdate(@Param("transferNo") String transferNo);

    /**
     * 活动转移（REQUESTED/ACCEPTED）涉及的家庭号。
     * 创建申请与发起合并时据此拒绝交叉申请；状态只含两种活动值，走默认升序。
     */
    @Query("select t.householdNo from TransferApplication t where t.status in :statuses")
    List<String> findActiveHouseholdNos(@Param("statuses") List<TransferStatus> statuses);

    /** 某家庭的活动转移：同一家庭同时只能有一笔 */
    @Query("select t from TransferApplication t where t.householdNo = :householdNo and t.status in :statuses")
    List<TransferApplication> findActiveByHousehold(@Param("householdNo") String householdNo,
                                                    @Param("statuses") List<TransferStatus> statuses);

    /**
     * 找出已接受但计划到达时间已过、仍未交接的申请。
     * 扫描接口在独立短事务里逐条加行锁做超时终结，避免长锁。
     */
    @Query("select t from TransferApplication t where t.status = com.chris64233.cc.shelter.domain.TransferStatus.ACCEPTED "
            + "and t.plannedArrivalAt < :now order by t.id asc")
    List<TransferApplication> findOverdueAccepted(@Param("now") Instant now);
}
