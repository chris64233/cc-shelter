package com.chris64233.cc.shelter.domain;

public enum MemberEventType {
    TEMPORARY_CHECK_IN,
    IDENTITY_VERIFIED,
    MERGED,
    /** 随家庭跨安置点转移到达目标房间（家庭关系不变，安置点/房间变化） */
    TRANSFERRED,
    CHECKOUT
}
