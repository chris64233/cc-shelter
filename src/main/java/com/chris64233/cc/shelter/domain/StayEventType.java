package com.chris64233.cc.shelter.domain;

public enum StayEventType {
    CHECK_IN,
    TRANSFER,
    /** 正式家庭作为合并目标产生的新入住（吸收若干临时家庭） */
    MERGE,
    /** 临时家庭在合并中被并入正式家庭，原入住结束 */
    TEMP_MERGED,
    /** 成员退住 */
    CHECKOUT
}
