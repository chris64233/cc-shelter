package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Household;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseholdRepository extends JpaRepository<Household, Long> {

    Optional<Household> findByHouseholdNo(String householdNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.householdNo = :householdNo")
    Optional<Household> findByHouseholdNoForUpdate(@Param("householdNo") String householdNo);

    /**
     * 一次锁定多个家庭行，按主键升序加锁。合并事务用统一顺序获取家庭锁，
     * 避免并发合并/转移/退住之间出现锁序倒置死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.householdNo in :householdNos order by h.id asc")
    List<Household> findByHouseholdNoInForUpdateOrderByIdAsc(@Param("householdNos") List<String> householdNos);
}
