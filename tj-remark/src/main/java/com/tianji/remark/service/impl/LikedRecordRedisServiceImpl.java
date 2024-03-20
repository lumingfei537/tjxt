package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.dto.msg.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constants.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.tianji.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikedRecordRedisServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;
    private final StringRedisTemplate redisTemplate;
    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        /*// 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.判断是否点赞 dto.liked 为true则点赞
        Boolean result = dto.getLiked();
        // 2.1.点赞逻辑
        if (result) {
            LikedRecord likedRecord = new LikedRecord();
            likedRecord.setUserId(userId);
            likedRecord.setBizId(dto.getBizId());
            likedRecord.setBizType(dto.getBizType());
            this.save(likedRecord);
        } else {
            // 2.2.取消赞逻辑
            this.lambdaUpdate()
                    .eq(LikedRecord::getUserId, userId)
                    .eq(LikedRecord::getBizId, dto.getBizId())
                    .remove();
        }
        // 3.统计该业务id的总点赞数
        Integer count = this.lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, dto.getBizId())
                .count();
        // 4.发送消息到mq*/


        // 1.基于前端的参数，判断是执行点赞还是取消点赞
        boolean success = dto.getLiked() ? like(dto) : unlike(dto);
        // 2.判断是否执行成功，如果失败，则直接结束
        if (!success) {
            return;
        }
        /*// 3.如果执行成功，统计点赞总数
        Integer likedTimes = lambdaQuery()
                .eq(LikedRecord::getBizId, dto.getBizId())
                .count();*/
        // 基于redis统计 业务id总的点赞数量
        // 拼接key  likes:set:biz:评论id
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        Long totalLikesNum = redisTemplate.opsForSet().size(key);
        if (totalLikesNum == null) {
            return;
        }
        // 采用zset结构来缓存点赞的总数  likes:times:type:QA    likes:times:type:NOTE
        String bizTypeTotalLikeKey = RedisConstants.LIKES_TIMES_KEY_PREFIX + dto.getBizType();
        redisTemplate.opsForZSet().add(bizTypeTotalLikeKey, dto.getBizId().toString(), totalLikesNum);
        /*// 4.发送MQ通知
        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), likedTimes);
        log.debug("发送点赞消息  消息内容{}", msg);
        mqHelper.send(
                LIKE_RECORD_EXCHANGE,
                StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, dto.getBizType()),
                msg);*/
    }

    private boolean unlike(LikeRecordFormDTO dto) {
        /*return remove(new QueryWrapper<LikedRecord>().lambda()
                .eq(LikedRecord::getUserId, UserContext.getUser())
                .eq(LikedRecord::getBizId, dto.getBizId()));*/
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();

        Long result = redisTemplate.opsForSet().remove(key, UserContext.getUser().toString());
        return result != null && result > 0;
    }

    private boolean like(LikeRecordFormDTO dto) {
        // 基于redis做点赞
        // 拼接 key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();

        // redisTemplate 往redis的set集合中添加点赞记录
        // Long result = redisTemplate.boundSetOps(key).add(UserContext.getUser().toString());
        Long result = redisTemplate.opsForSet().add(key, UserContext.getUser().toString());
        return result != null && result > 0;

        /*Long userId = UserContext.getUser();
        // 1.查询点赞记录
        Integer count = lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, dto.getBizId())
                .count();
        // 2.判断是否存在，如果已经存在，直接结束
        if (count > 0) {
            return false;
        }
        // 3.如果不存在，则新增
        LikedRecord likedRecord = new LikedRecord();
        likedRecord.setUserId(userId);
        likedRecord.setBizId(dto.getBizId());
        likedRecord.setBizType(dto.getBizType());
        save(likedRecord);
        return true;*/
    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        /*if (CollUtils.isEmpty(bizIds)) {
            return CollUtils.emptySet();
        }
        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.查询点赞状态
        List<LikedRecord> list = this.lambdaQuery()
                .in(LikedRecord::getBizId, bizIds)
                .eq(LikedRecord::getUserId, userId)
                .list();
        // 3.返回结果
        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());*/
        // Long userId = UserContext.getUser();
        // if (CollUtils.isEmpty(bizIds)) {
        //     return CollUtils.emptySet();
        // }
        // // 循环bizId
        // Set<Long> likedBizIds = new HashSet<>();
        // for (Long bizId : bizIds) {
        //     // 判断该业务id 的点赞用户集合中是否包含当前用户
        //     Boolean member = redisTemplate.opsForSet().isMember(RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId, userId.toString());
        //     if (member) {
        //         likedBizIds.add(bizId);
        //     }
        // }
        // return likedBizIds;


        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.查询点赞状态
        List<Object> objects = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId;
                src.sIsMember(key, userId.toString());
            }
            return null;
        });
        // 3.返回结果
        return IntStream.range(0, objects.size()) // 创建从0到集合size的流
                .filter(i -> (boolean) objects.get(i)) // 遍历每个元素，保留结果为true的角标i
                .mapToObj(bizIds::get)// 用角标i取bizIds中的对应数据，就是点赞过的id
                .collect(Collectors.toSet());// 收集
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        // 拼接key
        String bizTypeTotalLikeKey = RedisConstants.LIKES_TIMES_KEY_PREFIX + bizType;

        List<LikedTimesDTO> list = new ArrayList<>();
        // 1.从redis的zset结构中 按分数排序 取 maxBizSize 的 业务点赞信息  popmin 获取并移除
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().popMin(bizTypeTotalLikeKey, maxBizSize);
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String bizId = typedTuple.getValue();
            Double likedTimes = typedTuple.getScore();
            if (StringUtils.isBlank(bizId) || likedTimes == null) {
                continue;
            }
            // 2.封装LikedTimesDTO  消息数据
            LikedTimesDTO msg = LikedTimesDTO.of(Long.valueOf(bizId), likedTimes.intValue());
            list.add(msg);
        }

        // 3.发送MQ消息
        if (CollUtils.isNotEmpty(list)) {
            log.debug("批量发送点赞消息  消息内容{}", list);
            String routingKey = StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, bizType);
            mqHelper.send(
                    LIKE_RECORD_EXCHANGE,
                    routingKey,
                    list);
        }
    }
}
