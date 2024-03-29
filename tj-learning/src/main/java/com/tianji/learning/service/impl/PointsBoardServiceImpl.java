package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-20
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;
    @Override
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.判断是查当前赛季还是历史赛季 query.season  赛季id ，为null或者0则代表查询当前赛季
        boolean isCurrent = query.getSeason() == null || query.getSeason() == 0;
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        Long season = query.getSeason();//历史赛季id

        // 3.查询我的排名和积分 根据query.season 判断是查redis还是db
        PointsBoard board = isCurrent ? queryMyCurrentBoard(key) : queryMyHistoryBoard(season);
        // 4.分页查询赛季列表 根据 query.season 判断是查redis还是db
        List<PointsBoard> list =  isCurrent ? queryCurrentBoard(key, query.getPageNo(), query.getPageSize()) : queryHistoryBoard(query);
        // 5.封装用户id集合 远程调用用户服务 获取用户信息 转map
        Set<Long> uids = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        if (CollUtils.isEmpty(userDTOS)) {
            throw new BizIllegalException("用户不存在");
        }
        //封装map key为用户id value为用户名称
        Map<Long, String> userDtoMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c.getName()));

        // 6.封装vo返回
        PointsBoardVO vo = new PointsBoardVO();
        vo.setRank(board.getRank());// 我的排名
        vo.setPoints(board.getPoints());// 我的积分

        List<PointsBoardItemVO> voList = new ArrayList<>();
        for (PointsBoard pointsBoard : list) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            itemVO.setName(userDtoMap.get(pointsBoard.getUserId()));
            itemVO.setRank(pointsBoard.getRank());
            itemVO.setPoints(pointsBoard.getPoints());
            voList.add(itemVO);
        }
        vo.setBoardList(voList);
        return vo;
    }

    /**
     * 查询当前赛季排行榜列表 从redis zset查
     * @param key
     * @param pageNo 页码
     * @param pageSize 条数
     * @return
     */
    public List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize) {
        // 1.计算start 和 stop 分页值
        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize - 1;
        // 2.利用zrevrange命令 会按分数倒序 分页查询
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if (CollUtils.isEmpty(typedTuples)) {
            return CollUtils.emptyList();
        }
        // 3.封装结果返回
        int rank = start + 1;
        List<PointsBoard> list = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String value = typedTuple.getValue();//用户id
            Double score = typedTuple.getScore();//总积分值
            if (StringUtils.isBlank(value) || score == null) {
                continue;
            }
            PointsBoard board = new PointsBoard();
            board.setUserId(Long.valueOf(value));
            board.setPoints(score.intValue());
            board.setRank(rank++);
            list.add(board);
        }
        return list;
    }

    // 查询历史赛季列表 从db查
    private List<PointsBoard> queryHistoryBoard(PointsBoardQuery query) {
        return null;
    }

    // 查询历史赛季 我的积分和排名 db
    private PointsBoard queryMyHistoryBoard(Long season) {
        // todo
        return null;
    }

    // 查询当前赛季 我的积分和排行 redis
    private PointsBoard queryMyCurrentBoard(String key) {
        Long userId = UserContext.getUser();
        //获取分值
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        //获取排名 从0开始需要加1
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        PointsBoard board = new PointsBoard();
        board.setPoints(score == null ? 0 : score.intValue());
        board.setRank(rank == null ? 0 : rank.intValue() + 1);
        return board;
    }
}
