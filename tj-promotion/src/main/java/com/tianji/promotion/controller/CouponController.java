package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.ICouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 *
 * @author zlw
 * @since 2024-03-24
 */
@Api(tags = "优惠券相关接口")
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final ICouponService couponService;

    @ApiOperation("新增优惠券")
    @PostMapping
    public void saveCoupon(@RequestBody @Validated CouponFormDTO dto) {
        couponService.saveCoupon(dto);
    }

    @ApiOperation("分页查询优惠券-管理端")
    @GetMapping("/page")
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query) {
        return couponService.queryCouponByPage(query);
    }

    @ApiOperation("修改优惠券")
    @PutMapping("{id}")
    public void updateCoupon(@PathVariable Long id, @RequestBody CouponFormDTO dto) {
        couponService.updateCoupon(id, dto);
    }

    @ApiOperation("根据id查询优惠券")
    @GetMapping("{id}")
    public CouponDetailVO queryCouponById(@PathVariable Long id) {
        return couponService.queryCouponById(id);
    }

    @ApiOperation("删除优惠券")
    @DeleteMapping("{id}")
    public void deleteCouponById(@PathVariable Long id) {
        couponService.deleteById(id);
    }

    @ApiOperation("发放优惠券")
    @PutMapping("{id}/issue")
    public void issueCoupon(@PathVariable Long id, @RequestBody @Validated CouponIssueFormDTO dto) {
        couponService.issueCoupon(id, dto);
    }

    @ApiOperation("暂停发放优惠券-管理端")
    @PutMapping("{id}/pause")
    public void pauseIssue(@PathVariable Long id) {
        couponService.pauseIssue(id);
    }

    @ApiOperation("查询发放中的优惠券列表-用户端")
    @GetMapping("list")
    public List<CouponVO> queryIssuingCoupon() {
        return couponService.queryIssuingCoupon();
    }

}
