/*
package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.api.dto.msg.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tianji.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE;

*/
/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-17
 *//*

@Slf4j
@Service
@RequiredArgsConstructor
public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;
    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        */
/*//*
/ 1.获取登录用户id
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
        // 4.发送消息到mq*//*



        // 1.基于前端的参数，判断是执行点赞还是取消点赞
        boolean success = dto.getLiked() ? like(dto) : unlike(dto);
        // 2.判断是否执行成功，如果失败，则直接结束
        if (!success) {
            return;
        }
        // 3.如果执行成功，统计点赞总数
        Integer likedTimes = lambdaQuery()
                .eq(LikedRecord::getBizId, dto.getBizId())
                .count();
        // 4.发送MQ通知
        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), likedTimes);
        log.debug("发送点赞消息  消息内容{}", msg);
        mqHelper.send(
                LIKE_RECORD_EXCHANGE,
                StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, dto.getBizType()),
                msg);
    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        if (CollUtils.isEmpty(bizIds)) {
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
        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
    }

    private boolean unlike(LikeRecordFormDTO dto) {
        return remove(new QueryWrapper<LikedRecord>().lambda()
                .eq(LikedRecord::getUserId, UserContext.getUser())
                .eq(LikedRecord::getBizId, dto.getBizId()));
    }

    private boolean like(LikeRecordFormDTO dto) {
        Long userId = UserContext.getUser();
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
        return true;
    }
}
*/
