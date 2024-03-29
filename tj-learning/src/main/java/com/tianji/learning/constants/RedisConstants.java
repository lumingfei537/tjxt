package com.tianji.learning.constants;

public interface RedisConstants {

    /**
     * 签到记录的key前缀 完整格式为sign:uid:用户id:年月
     */
    String SIGN_RECORD_KEY_PREFIX = "sign:uid:";

    /**
     * 积分排行榜的key的前缀 boards:202403
     */
    String POINTS_BOARD_KEY_PREFIX = "boards:";
}
