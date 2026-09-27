package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /** 合并/退住后同身份标识可能存在历史行，只取仍在住的那条 */
    Optional<Member> findFirstByIdentityNoAndDepartedAtIsNull(String identityNo);

    boolean existsByIdentityNoAndDepartedAtIsNull(String identityNo);
}
