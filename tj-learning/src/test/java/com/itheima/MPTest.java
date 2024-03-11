package com.itheima;

import com.tianji.learning.LearningApplication;
import com.tianji.learning.service.ILearningLessonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = LearningApplication.class)
public class MPTest {

    @Autowired
    ILearningLessonService lessonService;
    @Test
    public void test() {
        System.out.println("--------");
    }
}
