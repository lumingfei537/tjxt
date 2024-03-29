package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoardSeason;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zlw
 * @since 2024-03-20
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {

    //方法作用：创建上赛季的表
    void createPointsBoardLatestTable(Integer id);
}
