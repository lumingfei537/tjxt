package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.IUserCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 前端控制器
 * </p>
 *
 * @author zlw
 * @since 2024-03-28
 */
@Api(tags = "用户券相关接口")
@RestController
@RequestMapping("/user-coupons")
@RequiredArgsConstructor
public class UserCouponController {

    private final IUserCouponService userCouponService;

    @ApiOperation("领取优惠券")
    @PostMapping("{id}/receive")
    public void receiveCoupon(@PathVariable Long id) {
        userCouponService.receiveCoupon(id);
    }

    @ApiOperation("兑换码兑换优惠券")
    @PostMapping("{code}/exchange")
    public void exchangeCoupon(@PathVariable String code) {
        userCouponService.exchangeCoupon(code);
    }

    @ApiOperation("分页查询我的优惠券")
    @GetMapping("/page")
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        return userCouponService.queryMyCouponPage(query);
    }

    // 该方法是给tj-trade服务 远程调用使用的

    /**
     * 查询可用优惠券方案
     * @param courses 订单中的课程信息
     * @return 方案集合
     */
    @ApiOperation("查询可用优惠券方案")
    @PostMapping("available")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> courses) {
        return userCouponService.findDiscountSolution(courses);
    }
}
