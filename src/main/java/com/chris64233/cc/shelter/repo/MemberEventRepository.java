package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.MemberEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberEventRepository extends JpaRepository<MemberEvent, Long> {

    List<MemberEvent> findByIdentityNoOrderByIdAsc(String identityNo);

    List<MemberEvent> findByMemberIdOrderByIdAsc(Long memberId);
}
