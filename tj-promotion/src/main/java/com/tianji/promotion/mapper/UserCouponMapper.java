package com.tianji.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 Mapper 接口
 * </p>
 *
 * @author zlw
 * @since 2024-03-28
 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    @Select("SELECT c.id, c.discount_type, c.`specific`, c.threshold_amount, c.discount_value, c.max_discount_amount, uc.id as creater\n" +
            "FROM\n" +
            "coupon c \n" +
            "INNER JOIN user_coupon uc ON c.id = uc.coupon_id\n" +
            "WHERE uc.user_id=#{userId} AND uc.`status`=1")
    List<Coupon> queryMyCoupons(Long userId);
}
