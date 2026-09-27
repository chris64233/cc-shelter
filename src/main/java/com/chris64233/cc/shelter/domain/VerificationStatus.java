package com.chris64233.cc.shelter.domain;

public enum VerificationStatus {
    /** 正式登记的家庭成员，无需额外身份核验 */
    NOT_REQUIRED,
    /** 临时入住、已声明原家庭号但尚未完成身份核验，不能并入正式家庭 */
    UNVERIFIED,
    /** 已完成身份核验，可以参与家庭合并 */
    VERIFIED
}
