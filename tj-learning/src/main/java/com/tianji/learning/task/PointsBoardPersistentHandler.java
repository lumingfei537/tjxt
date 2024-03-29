package com.tianji.learning.task;

import com.tianji.common.utils.CollUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService pointsBoardSeasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 创建上赛季（上个月）榜单表
     */
    // @Scheduled(cron = "0 0 3 1 * ?")//每个月1号凌晨3点运行
    // @Scheduled(cron = "0 38 21 23 3 ?")//每个月1号凌晨3点运行 单机版定时任务调度
    @XxlJob("createTableJob")
    public void createPointsBoardTableOfLastSeason() {
        log.debug("创建上赛季榜单表任务执行了");
        // 1.获取上次月当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);

        // 2.查询赛季表获取赛季id
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.debug("上赛季信息 {}", one);
        if (one == null) {
            return;
        }
        //创建上赛季榜单表  points_board_?
        pointsBoardSeasonService.createPointsBoardLatestTable(one.getId());
    }

    // 持久化上赛季（上个月）排行榜数据 到db中
    @XxlJob("savePointsBoard2DB")//任务名字要和 xxljob控制台 任务的jobhandle值保持一致
    public void savePointsBoard2DB() {
        log.debug("持久化上赛季排行榜数据到db中 任务执行了");
        // 1.获取上个月 当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);
        // 2.查询赛季表points_board_season 获取上赛季信息
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.debug("上赛季信息 {}", one);
        if (one == null) {
            return;
        }
        // 3.计算动态表名 并存入threadLocal
        String tableName = POINTS_BOARD_TABLE_PREFIX + one.getId();
        log.debug("动态表名 {}", tableName);
        TableInfoContext.setInfo(tableName);
        // 4.分页获取redis上赛季积分排行榜数据
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;

        int shardIndex = XxlJobHelper.getShardIndex();//当前分片的索引 从0开始
        int shardTotal = XxlJobHelper.getShardTotal();// 总的分片数

        int pageNo = shardIndex + 1;
        int pageSize = 5;
        while (true) {
            log.debug("处理第 {} 页数据", pageNo);
            List<PointsBoard> pointsBoardList = pointsBoardService.queryCurrentBoard(key, pageNo, pageSize);
            if (CollUtils.isEmpty(pointsBoardList)) {
                break;
            }
            pageNo += shardTotal;

            // 5.持久化到db相应的赛季表中
            for (PointsBoard board : pointsBoardList) {
                board.setId(Long.valueOf(board.getRank()));//历史赛季排行榜中id 就代表了排名
                board.setRank(null);
            }
            pointsBoardService.saveBatch(pointsBoardList);
        }
        // 6.清空threadLocal中的数据
        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 2.计算key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        // 3.删除
        redisTemplate.unlink(key);
    }
}
