package com.itheima;

import com.tianji.learning.LearningApplication;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.service.ILearningLessonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@SpringBootTest(classes = LearningApplication.class)
public class MPTest {

    @Autowired
    ILearningLessonService lessonService;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Test
    public void test() {
        System.out.println("--------");
    }

    @Test
    public void test2() {
        LocalDate time = LocalDate.now().minusMonths(1);
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;

        for (int i = 1; i <= 20; i++) {
            redisTemplate.opsForZSet().add(key, String.valueOf(i), i);
        }
    }
}
