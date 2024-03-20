package com.tianji.learning.service.impl;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {
    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;
    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.查询课表信息 条件userId 和 courseId
        LearningLesson lesson = lessonService.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            throw new BizIllegalException("课程未加入课表");
        }
        // 3.查询学习记录 条件lessonId 和 userId
        List<LearningRecord> recordList = this.lambdaQuery()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();
        // 4.封装结果返回
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());// 课表id
        dto.setLatestSectionId(lesson.getLatestSectionId());// 最近学习的小节id
        List<LearningRecordDTO> dtoList = BeanUtils.copyList(recordList, LearningRecordDTO.class);
        dto.setRecords(dtoList);
        return dto;
    }

    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO dto) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.处理学习记录
        boolean isFinished = false;//代表本小节是否第一次学完
        if (dto.getSectionType().equals(SectionType.VIDEO)) {
            // 2.1提交视频播放记录
            isFinished = handleVideoRecord(userId, dto);
        } else {
            // 2.2提交考试记录
            isFinished = handleExamRecord(userId, dto);
        }
        // 3.处理课表数据
        if (!isFinished) {// 如果本小节 不是第一次学完， 不用处理课表数据
            return;
        }
        handleLessonData(dto);
    }

    //处理视频播放记录
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO dto) {
        // 1.查询旧的学习记录 learning_record 条件userId lessonId section_id
        LearningRecord learningRecord = queryOldRecord(dto.getLessonId(), dto.getSectionId());
        // LearningRecord learningRecord = this.lambdaQuery()
        //         .eq(LearningRecord::getLessonId, dto.getLessonId())
        //         .eq(LearningRecord::getSectionId, dto.getSectionId())
        //         .one();
        // 2.判断是否存在
        if (learningRecord == null) {
            // 3.如果不存在则新增学习记录
            LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
            record.setUserId(userId);
            boolean success = save(record);
            if (!success) {
                throw new DbException("新增学习记录失败");
            }
            return false;
        }
        // 4.如果存在则更新学习记录 learning_record  更新什么字段 moment
        // 判断本小节是否是第一次学完   isFinished为true代表第一次学完
        boolean isFinished = !learningRecord.getFinished() && dto.getMoment() * 2 >= dto.getDuration();
        if (!isFinished) {
            // 添加学习记录到redis，并提交延迟任务
            LearningRecord record = new LearningRecord();
            record.setLessonId(dto.getLessonId());
            record.setSectionId(dto.getSectionId());
            record.setMoment(dto.getMoment());
            record.setFinished(learningRecord.getFinished());
            record.setId(learningRecord.getId());
            taskHandler.addLearningRecordTask(record);
            return false;
        }
        // update learning_record set moment=xxx ,finished=true, finish_time=xxx where id =xxx
        boolean result = this.lambdaUpdate()
                .set(LearningRecord::getMoment, dto.getMoment())
                .set(isFinished, LearningRecord::getFinished, true)
                .set(isFinished, LearningRecord::getFinishTime, dto.getCommitTime())
                .eq(LearningRecord::getId, learningRecord.getId())
                .update();
        if (!result) {
            throw new DbException("更新视频学习记录失败");
        }
        // 清理缓存
        taskHandler.cleanRecordCache(dto.getLessonId(), dto.getSectionId());
        return true;
    }

    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        // 1.查询缓存
        LearningRecord cache = taskHandler.readRecordCache(lessonId, sectionId);
        // 2.如果命中，直接返回
        if (cache != null) {
            return cache;
        }
        // 3.如果未命中，查询db
        LearningRecord dbRecord = this.lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        //讲义上没进行判断，不写可能会报空指针
        if (dbRecord == null) {
            return null;
        }
        // 4.放入缓存
        taskHandler.writeRecordCache(dbRecord);
        return dbRecord;
    }

    //处理课表相关数据
    private void handleLessonData(LearningRecordFormDTO dto) {
        //1.查询课表 learning_lesson 条件lesson_id主键
        LearningLesson lesson = lessonService.getById(dto.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课表不存在");
        }
        // 2.判断是否第一次学完 finished是否为true
        boolean allFinished = false;// 所有小节是否学完
        // 3.远程调用课程服务, 得到课程信息 小节总数
        CourseFullInfoDTO cinfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cinfo == null) {
            throw new BizIllegalException("课程不存在");
        }
        Integer sectionNum = cinfo.getSectionNum();

        // 4.如果finished为true 本小节第一次学完  判断该用户对该课程下全部小节是否学完
        Integer learnedSections = lesson.getLearnedSections();
        allFinished = learnedSections + 1 >= sectionNum;
        // 5.更新课表数据
        lessonService.lambdaUpdate()
                // .set(lesson.getStatus() == LessonStatus.NOT_BEGIN, LearningLesson::getStatus, LessonStatus.LEARNING)
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .set(allFinished, LearningLesson::getStatus, LessonStatus.FINISHED)
                .set(LearningLesson::getLatestSectionId, dto.getSectionId())
                .set(LearningLesson::getLatestLearnTime, dto.getCommitTime())
                // .set(finished, LearningLesson::getLearnedSections, lesson.getLearnedSections() + 1)
                .setSql( "learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    //处理考试记录
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO dto) {
        // 1.转换DTO为PO
        LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
        // 2.填充数据
        record.setUserId(userId);
        record.setFinished(true);//提交考试记录,代表本小节已学完
        record.setFinishTime(dto.getCommitTime());
        // 3.写入数据库
        boolean success = save(record);
        if (!success) {
            throw new DbException("新增考试记录失败");
        }
        return true;
    }
}
