package com.chris64233.cc.shelter.domain;

public enum MemberEventType {
    TEMPORARY_CHECK_IN,
    IDENTITY_VERIFIED,
    MERGED,
    /** 家庭跨安置点整体转移：成员随家庭从来源房间迁移到目标房间 */
    TRANSFER,
    CHECKOUT
}
