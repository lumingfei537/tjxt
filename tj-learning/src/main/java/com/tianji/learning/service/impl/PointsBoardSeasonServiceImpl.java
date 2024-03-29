package com.tianji.learning.service.impl;

import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.mapper.PointsBoardSeasonMapper;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-20
 */
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason> implements IPointsBoardSeasonService {

    //方法作用：创建上赛季的表
    @Override
    public void createPointsBoardLatestTable(Integer id) {
        getBaseMapper().createPointsBoardTable(POINTS_BOARD_TABLE_PREFIX + id);
    }
}
