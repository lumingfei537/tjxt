package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 互动提问的问题表 前端控制器
 * </p>
 *
 * @author zlw
 * @since 2024-03-14
 */
@Api(tags = "互动问题相关接口-管理端")
@RestController
@RequestMapping("/admin/questions")
@RequiredArgsConstructor
public class InteractionQuestionAdminController {

    private final IInteractionQuestionService questionService;

    @ApiOperation("分页查询问题列表-管理端")
    @GetMapping("page")
    public PageDTO<QuestionAdminVO> queryQuestionAdminVOPage(QuestionAdminPageQuery query) {
        return questionService.queryQuestionAdminVOPage(query);
    }

    @ApiOperation("管理端隐藏或显示问题")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenQuestion(@PathVariable Long id, @PathVariable Boolean hidden) {
        questionService.hiddenQuestion(id, hidden);
    }

    @ApiOperation("管理端根据问题id查询问题详情")
    @GetMapping("{id}")
    public QuestionAdminVO queryQuestionByIdAdmin(@PathVariable Long id) {
        return questionService.queryQuestionByIdAdmin(id);
    }


}
