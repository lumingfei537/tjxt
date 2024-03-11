package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningPlanDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.PlanStatus;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-07
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {

    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final LearningRecordMapper recordMapper;

    @Override
    @Transactional
    public void addUserLesson(Long userId, List<Long> courseIds) {
        // 1.通过feign远程调用课程服务， 得到课程信息
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cinfos)) {
            log.error("课程信息不存在，无法添加到课表");
            return;
        }
        // 2.封装po实体类,填充过期时间
        ArrayList<LearningLesson> list = new ArrayList<>();
        for (CourseSimpleInfoDTO cinfo : cinfos) {
            LearningLesson lesson = new LearningLesson();
            lesson.setUserId(userId);
            lesson.setCourseId(cinfo.getId());
            Integer validDuration = cinfo.getValidDuration(); // 课程有效期 单位：月
            if (validDuration != null) {
                LocalDateTime now = LocalDateTime.now();
                lesson.setCreateTime(now);
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            list.add(lesson);
        }

        // 3.批量保存
        this.saveBatch(list);
    }

    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.分页查询
        // select * from learning_lesson where user_id = #{userId} order by latest_learn_time limit 0, 5
        Page<LearningLesson> page = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.远程调用课程服务，给vo中的课程名 封面 章节数赋值
        Set<Long> courseIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cinfos)) {
            throw new BizIllegalException("课程不存在");
        }
        //将cinfos 课程集合 转换为 map结构<课程id， 课程对象>
        Map<Long, CourseSimpleInfoDTO> infoDTOMap = cinfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        // 4.将po中的数据封装到vo中
        List<LearningLessonVO> voList = new ArrayList<>();
        for (LearningLesson record : records) {
            LearningLessonVO vo = BeanUtils.copyBean(record, LearningLessonVO.class);
            CourseSimpleInfoDTO infoDTO = infoDTOMap.get(record.getCourseId());
            if (infoDTO != null) {
                vo.setCourseName(infoDTO.getName());
                vo.setCourseCoverUrl(infoDTO.getCoverUrl());
                vo.setSections(infoDTO.getSectionNum());
            }
            voList.add(vo);
        }
        // 5.返回
        return PageDTO.of(page, voList);
    }

    @Override
    public LearningLessonVO queryMyLessons() {
        //1.获取登录用户id
        Long userId = UserContext.getUser();
        //2.查询当前用户最近学习课程 按latest_learn_time 降序排序 取第一条 正在学习的 status=1
        // select * from xx where user_id = #{userId} AND status = 1 order by latest_learn_time limit 1
        LearningLesson lesson = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING)
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")
                .one();
        if (lesson == null) {
            return null;
        }
        //3.远程调用课程服务，给vo中课程名 封面 章节数赋值
        CourseFullInfoDTO cinfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cinfo == null) {
            throw new BizIllegalException("课程不存在");
        }
        //4.查询当前用户课表中 总的课程数
        // select count(*) from learning_lesson where user_id = xxx
        Integer count = this.lambdaQuery().eq(LearningLesson::getUserId, userId).count();

        //5.通过feign远程调用课程服务，获取小节名称和 小节编号
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(CollUtils.singletonList(lesson.getLatestSectionId()));
        if (CollUtils.isEmpty(cataSimpleInfoDTOS)) {
            throw new BizIllegalException("小节不存在");
        }

        //6.封装到vo返回
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        vo.setCourseName(cinfo.getName());
        vo.setCourseCoverUrl(cinfo.getCoverUrl());
        vo.setSections(cinfo.getSectionNum());
        vo.setCourseAmount(count);//当前用户能学习课程总数
        CataSimpleInfoDTO cataSimpleInfoDTO = cataSimpleInfoDTOS.get(0);
        vo.setLatestSectionName(cataSimpleInfoDTO.getName());//最近学习的小节名称
        vo.setLatestSectionIndex(cataSimpleInfoDTO.getCIndex());//最近学习的小节编号

        return vo;
    }

    @Override
    public LearningLessonVO queryLessonByCourseId(Long courseId) {
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.查询课程信息 select * from xx where user_id = #{userId} AND course_id = #{courseId}
        LearningLesson lesson = getOne(buildUserIdAndCourseIdWrapper(userId, courseId));
        if (lesson == null) {
            return null;
        }
        // 3.处理VO
        return BeanUtils.copyBean(lesson, LearningLessonVO.class);
    }

    @Override
    public Long isLessonValid(Long courseId) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        if (userId == null) {
            return null;
        }
        // 2.查询课程
        LearningLesson lesson = getOne(buildUserIdAndCourseIdWrapper(userId, courseId));
        if (lesson == null) {
            return null;
        }
        // 3.校验课程是否过期
        LocalDateTime expireTime = lesson.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        if (expireTime != null && now.isAfter(expireTime)) {
            return null;
        }
        return lesson.getId();
    }

    @Override
    public void deleteCourseFromLesson(Long userId, Long courseId) {
        if (userId == null) {
            userId = UserContext.getUser();
        }
        remove(buildUserIdAndCourseIdWrapper(userId, courseId));
    }

    @Override
    public Integer countLearningLessonByCourse(Long courseId) {
        // select count(1) from xx where course_id = #{cc} AND status in (0, 1, 2)
        return lambdaQuery()
                .eq(LearningLesson::getCourseId, courseId)
                .in(LearningLesson::getStatus,
                        LessonStatus.NOT_BEGIN.getValue(),
                        LessonStatus.LEARNING.getValue(),
                        LessonStatus.FINISHED.getValue())
                .count();
    }

    @Override
    public void createLearningPlan(LearningPlanDTO dto) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.查询课表learning_lesson   条件 user_id  course_id
        LearningLesson lesson = getOne(buildUserIdAndCourseIdWrapper(userId, dto.getCourseId()));
        if (lesson == null) {
            throw new BizIllegalException("该课程没有加入课表");
        }
        // 3.修改课表
        this.lambdaUpdate()
                .set(LearningLesson::getWeekFreq, dto.getFreq())
                .set(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        // 1.获取登录用户的id
        Long userId = UserContext.getUser();
        // 2.TODO 2.查询积分

        // 3.查询本周学习计划总数据 learning_lesson 条件 userID status in(0,1) plan_status  sum(week_freq)
        /*SELECT SUM(week_freq) FROM learning_lesson
        WHERE user_id = 2
        AND plan_status = 1
        AND `status` IN (0, 1)*/
        QueryWrapper<LearningLesson> wrapper = new QueryWrapper<>();
        wrapper.select("sum(week_freq) as plansTotal");
        wrapper.eq("user_id", userId);
        wrapper.in("status", LessonStatus.NOT_BEGIN, LessonStatus.LEARNING);
        wrapper.eq("plan_status", PlanStatus.PLAN_RUNNING);
        Map<String, Object> map = this.getMap(wrapper);
        // {plansTotal: 7}
        Integer plansTotal = 0;
        if (map != null && map.get("plansTotal") != null) {
            plansTotal = Integer.valueOf(map.get("plansTotal").toString());
        }
        // 4.查询本周 已学习的计划总数据 learning_record  条件 userId ,finished = 1, finish_time(xxx, xxx)
        // SELECT COUNT(*) FROM learning_record
        // WHERE user_id = 2
        // AND finished = 1
        // AND finish_time BETWEEN '2024-03-11 00:00:01' AND '2024-03-17 23:23:59'
        LocalDate now = LocalDate.now();
        LocalDateTime weekBeginTime = DateUtils.getWeekBeginTime(now);
        LocalDateTime weekEndTime = DateUtils.getWeekEndTime(now);
        Integer weekFinishedPlanNum = recordMapper.selectCount(Wrappers.<LearningRecord>lambdaQuery()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, true)
                .between(LearningRecord::getFinishTime, weekBeginTime, weekEndTime));

        // 5.查询课表数据 learning_lesson  条件userId status in(0, 1) plan_status = 1 分页
        Page<LearningLesson> page = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            LearningPlanPageVO vo = new LearningPlanPageVO();
            vo.setTotal(0L);
            vo.setPages(0L);
            vo.setList(CollUtils.emptyList());
            return vo;
        }
        // 6.远程调用课程服务， 获取课程信息
        Set<Long> courseIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cinfos)) {
            throw new BizIllegalException("课程不存在");
        }
        // 将cinfo  list结构转map <课程id，CourseSimpleInfoDTO>，避免使用双重for
        Map<Long, CourseSimpleInfoDTO> cInfosMap = cinfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        // 7.查询学习记录表 本周 当前用户下 每一门课下 已学习的小节数
        // SELECT lesson_id, COUNT(*) FROM learning_record
        // WHERE user_id = 2
        // AND finished = 1
        // AND finish_time BETWEEN '2024-03-11 00:00:01' AND '2024-03-17 23:23:59'
        // GROUP BY lesson_id
        QueryWrapper<LearningRecord> rWrapper = new QueryWrapper<>();
        rWrapper.select("lesson_id as lessonId", "count(*) as userId");
        rWrapper.eq("user_id", userId);
        rWrapper.eq("finished", true);
        rWrapper.between("finish_time", weekBeginTime, weekEndTime);
        rWrapper.groupBy("lesson_id");
        List<LearningRecord> learningRecords = recordMapper.selectList(rWrapper);
        // map中的key是lessonId  value是userId，userId是当前用户对该课程下已学习的小节数量，不是用户id
        Map<Long, Long> courseWeekFinishNumMap = learningRecords.stream().collect(Collectors.toMap(LearningRecord::getLessonId, c -> c.getUserId()));
        // 8.封装vo返回
        LearningPlanPageVO vo = new LearningPlanPageVO();
        vo.setWeekTotalPlan(plansTotal);
        vo.setWeekFinished(weekFinishedPlanNum);
        List<LearningPlanVO> voList = new ArrayList<>();
        for (LearningLesson record : records) {
            LearningPlanVO planVO = BeanUtils.copyBean(record, LearningPlanVO.class);
            CourseSimpleInfoDTO infoDTO = cInfosMap.get(record.getCourseId());
            planVO.setCourseName(infoDTO.getName());// 课程名
            planVO.setSections(infoDTO.getSectionNum());// 课程下的总小节数

            // Long aLong = courseWeekFinishNumMap.get(record.getId());
            // if (aLong != null) {
            //     planVO.setWeekLearnedSections(aLong.intValue());//本周学习的章节数
            // } else {
            //     planVO.setWeekLearnedSections(0);//本周学习的章节数
            // }
            planVO.setWeekLearnedSections(courseWeekFinishNumMap.getOrDefault(record.getId(), 0L).intValue());
            voList.add(planVO);
        }
        return vo.pageInfo(page.getTotal(), page.getPages(), voList);
    }

    private LambdaQueryWrapper<LearningLesson> buildUserIdAndCourseIdWrapper(Long userId, Long courseId) {
        LambdaQueryWrapper<LearningLesson> queryWrapper = new QueryWrapper<LearningLesson>()
                .lambda()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId);
        return queryWrapper;
    }

}
