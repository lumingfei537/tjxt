package com.tianji.learning.service.impl;


import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.remark.RemarkClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_CREATE_TIME;
import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_LIKED_TIME;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {

    private final InteractionQuestionMapper questionMapper;
    private final UserClient userClient;
    private final RemarkClient remarkClient;

    @Override
    public void saveReply(ReplyDTO dto) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.保存回答或评论， interaction_reply
        InteractionReply interactionReply = BeanUtils.copyBean(dto, InteractionReply.class);
        interactionReply.setUserId(userId);
        this.save(interactionReply);

        InteractionQuestion question = questionMapper.selectById(dto.getQuestionId());
        // 3. 判断是否是回答  dto.answerId 为空则是回答,不空则为评论
        if (dto.getAnswerId() != null) {
            // 3.1如果不是回答，则累加回答下的评论次数
            InteractionReply reply = this.getById(dto.getAnswerId());
            reply.setReplyTimes(reply.getReplyTimes() + 1);
            this.updateById(reply);
        } else {
            // 3.2如果是回答，则修改问题表最近一次回答id  同时累加问题表回答次数
            question.setLatestAnswerId(interactionReply.getId());
            question.setAnswerTimes(question.getAnswerTimes() + 1);
        }
        // 4.判断是否是学生提交， dto.isStudent 为true则为学生提交， 如果是则将问题表中该问题的status字段改为未查看
        if (dto.getIsStudent()) {
            question.setStatus(QuestionStatus.UN_CHECK);
        }
        questionMapper.updateById(question);


        /*// 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.新增回答
        InteractionReply reply = BeanUtils.toBean(dto, InteractionReply.class);
        reply.setUserId(userId);
        save(reply);
        // 3.累加评论数或者累加回答数
        // 3.1.判断当前回复的类型是否是回答
        boolean isAnswer = dto.getAnswerId() == null;
        if (!isAnswer) {
            // 3.2.是评论，则需要更新上级回答的评论数量
            lambdaUpdate()
                    .setSql("reply_times = reply_times + 1")
                    .eq(InteractionReply::getId, dto.getAnswerId())
                    .update();
        }*/
        // 3.3.尝试更新问题表中的状态、 最近一次回答、回答数量
        // questionService.lambdaUpdate()
        //         .set(isAnswer, InteractionQuestion::getLatestAnswerId, reply.getAnswerId())
        //         .setSql(isAnswer, "answer_times = answer_times + 1")
        //         .set(dto.getIsStudent(), InteractionQuestion::getStatus, QuestionStatus.UN_CHECK.getValue())
        //         .eq(InteractionQuestion::getId, dto.getQuestionId())
        //         .update();
        /*InteractionQuestion question = questionMapper.selectById(dto.getQuestionId());
        LambdaUpdateWrapper<InteractionQuestion> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(isAnswer, InteractionQuestion::getLatestAnswerId, reply.getAnswerId());
        updateWrapper.setSql(isAnswer, "answer_times = answer_times + 1");
        updateWrapper.set(dto.getIsStudent(), InteractionQuestion::getStatus, QuestionStatus.UN_CHECK.getValue());
        updateWrapper.eq(InteractionQuestion::getId, dto.getQuestionId());
        questionMapper.update(question, updateWrapper);*/

    }

    @Override
    public PageDTO<ReplyVO> queryReplyVOPage(ReplyPageQuery query) {
        // 1。校验questionId和answerId是否为空
        if (query.getQuestionId() == null && query.getAnswerId() == null) {
            throw new BadRequestException("问题id和回答id不能都为空");
        }
        // 2.分页查询interaction_reply表
        Page<InteractionReply> page = this.lambdaQuery()
                // 如果传问题id则拼接问题id条件
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                // 如果回答id没传，则查询answer_id为0的数据，也就是回答
                .eq(InteractionReply::getAnswerId, query.getQuestionId() != null ? 0L : query.getAnswerId())
                .eq(InteractionReply::getHidden, false)
                .page(query.toMpPage( // 先根据点赞数排序，点赞数相同，则根据创建时间排序
                        new OrderItem(DATA_FIELD_NAME_LIKED_TIME, false),
                        new OrderItem(DATA_FIELD_NAME_CREATE_TIME, true))
                );
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(0L, 0L);
        }
        // 3.补全其它数据
        Set<Long> uids = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();
        Set<Long> targetReplyIds = new HashSet<>();
        for (InteractionReply record : records) {
            if (!record.getAnonymity()) {
                uids.add(record.getUserId());
            }
            // if (record.getTargetReplyId() != null && record.getTargetReplyId() > 0) {
                targetReplyIds.add(record.getTargetReplyId());
                answerIds.add(record.getId());
            // }
        }
        // 查询目标回复，如果目标回复不是匿名，则需要查询目标回复的用户信息
        targetReplyIds.remove(0L);
        targetReplyIds.remove(null);
        if (targetReplyIds.size() > 0) {
            List<InteractionReply> targetReplies = listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplies.stream()
                    .filter(Predicate.not(InteractionReply::getAnonymity))
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            uids.addAll(targetUserIds);
        }
        // 调用用户服务，查询用户信息
        List<UserDTO> userDTOList = userClient.queryUserByIds(uids);
        Map<Long, UserDTO> userDTOMap = new HashMap<>();
        if (userDTOList != null) {
            userDTOMap = userDTOList.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));
        }

        // 查询用户点赞状态
        Set<Long> bizLiked = remarkClient.isBizLiked(answerIds);

        // 4.封装vo返回
        List<ReplyVO> voList = new ArrayList<>();
        for (InteractionReply record : records) {
            // 拷贝基础属性
            ReplyVO vo = BeanUtils.copyBean(record, ReplyVO.class);
            if (!record.getAnonymity()) {
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                // 4.1.回复人信息
                if (userDTO != null) {
                    vo.setUserName(userDTO.getName());
                    vo.setUserIcon(userDTO.getIcon());
                    vo.setUserType(userDTO.getType());
                }
            }
            // 4.2.如果存在评论的目标，则需要设置目标用户信息
            if (record.getTargetReplyId() != null) {
                UserDTO targetUserDTO = userDTOMap.get(record.getTargetUserId());
                if (targetUserDTO != null) {
                    vo.setTargetUserName(targetUserDTO.getName());
                }
            }
            vo.setLiked(bizLiked.contains(record.getId()));
            voList.add(vo);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(), voList);
    }
}

