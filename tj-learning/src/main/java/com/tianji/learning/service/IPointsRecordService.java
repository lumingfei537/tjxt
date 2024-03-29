package com.tianji.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mq.msg.SignInMessage;

import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务类
 * </p>
 *
 * @author zlw
 * @since 2024-03-20
 */
public interface IPointsRecordService extends IService<PointsRecord> {
    //增加积分
    void addPointRecord(SignInMessage msg, PointsRecordType type);
    //查询我的今日积分情况
    List<PointsStatisticsVO> queryMyTodayPoints();
}
