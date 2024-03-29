package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoard;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 服务类
 * </p>
 *
 * @author zlw
 * @since 2024-03-20
 */
public interface IPointsBoardService extends IService<PointsBoard> {

    PointsBoardVO queryPointsBoardList(PointsBoardQuery query);

    /**
     * 查询当前赛季排行榜列表 从redis zset查
     * @param key
     * @param pageNo 页码
     * @param pageSize 条数
     * @return
     */
    public List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize);

}
