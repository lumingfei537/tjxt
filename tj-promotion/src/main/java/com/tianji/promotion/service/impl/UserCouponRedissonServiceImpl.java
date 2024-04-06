/*
package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

*/
/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-28
 *//*

@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponRedissonServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    // 领取优惠券
    @Override
    // @Transactional
    public void receiveCoupon(Long id) {
        // 1.根据id查询优惠券信息，做相关校验
        if (id == null) {
            throw new BadRequestException("非法参数");
        }
        Coupon coupon = couponMapper.selectById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        if (coupon.getStatus() != CouponStatus.ISSUING) {
            throw new BadRequestException("该优惠券状态不是正在发放");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("该优惠券已过期或未开始发放");
        }
        if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
            throw new BadRequestException("该优惠券库存不足");
        }
        Long userId = UserContext.getUser();

        // 获取当前用户 对该优惠券 已领数量 user_coupon表  条件：userid couponId 统计数量
        */
/*Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, id)
                .count();
        if (count != null && count >= coupon.getUserLimit()) {
            throw new BadRequestException("已达到领取上限");
        }
        // 2.优惠券的已发放数量+1
        couponMapper.incrIssueNum(id);
        // 3.生成用户券
        saveUserCoupon(userId, coupon);*//*


        // checkAndCreateUserCoupon(userId, coupon, null);

        */
/*synchronized (userId.toString().intern()) {
            checkAndCreateUserCoupon(userId, coupon, null);
        }*//*


        */
/*synchronized (userId.toString().intern()) {
            // 从aop上下文中 获取当前类的代理对象
            IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
            //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原对象
            userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);// 这种写法是调用代理对象的方法 方法是有事务的
        }*//*


        // 通过redisson实现分布式锁
        String key = "lock:coupon:uid:" + userId;
        RLock lock = redissonClient.getLock(key);
        try {
            // boolean isLock = lock.tryLock(1, 5, TimeUnit.SECONDS);// 看门狗会失效
            boolean isLock = lock.tryLock(); // 看门狗机制生效 ，默认失效时间为30秒
            if (!isLock) {
                throw new BizIllegalException("操作太频繁了");
            }
            // 从aop上下文中 获取当前类的代理对象
            IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
            //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原对象
            userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);// 这种写法是调用代理对象的方法 方法是有事务的
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        // 1.校验code是否为空
        if (StringUtils.isBlank(code)) {
            throw new BadRequestException("非法参数");
        }
        // 2.解析兑换码，得到自增id
        long serialNum = CodeUtil.parseCode(code);
        log.debug("自增id:{}", serialNum);
        // 3.判断兑换码是否兑换  采用redis的bitmap结构 setbit key offset  1 如果true代表已兑换
        boolean result = exchangeCodeService.updateExchangeCodeMark(serialNum, true);
        if (result) {
            //兑换码已经被兑换了
            throw new BizIllegalException("兑换码已被使用");
        }
        try {
            // 4.判断兑换码是否存在  根据自增id查询  主键查询
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在");
            }
            // 5.判断是否过期
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
            if (now.isAfter(expiredTime)) {
                throw new BizIllegalException("兑换码已过期");
            }
            //校验并生成用户券
            Long userId = UserContext.getUser();
            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
            if (coupon == null) {
                throw new BizIllegalException("优惠券不存在");
            }
            checkAndCreateUserCoupon(userId, coupon, serialNum);
        } catch (Exception e) {
            // 10.将兑换码的状态重置
            exchangeCodeService.updateExchangeCodeMark(serialNum, false);
            throw e;
        }
    }
    @Override
    @Transactional
    public void checkAndCreateUserCoupon(Long userId, Coupon coupon, Long serialNum) {
        // Long类型 -128-127 之间是同一个对象， 超过区间是不同的对象
        // Long.toString方法底层是new String 所以是不同对象
        // userId.toString().intern() intern()方法是强制从常量池中取字符串
        // synchronized (userId.toString().intern()) {
            // 1.获取当前用户 对该优惠券 已领数量 user_coupon表  条件：userid couponId 统计数量
            Integer count = this.lambdaQuery()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getCouponId, coupon.getId())
                    .count();
            if (count != null && count >= coupon.getUserLimit()) {
                throw new BadRequestException("已达到领取上限");
            }
            // 2.优惠券的已发放数量+1
            couponMapper.incrIssueNum(coupon.getId());
            // 3.生成用户券
            saveUserCoupon(userId, coupon);

            // 更新兑换码的状态
            if (serialNum != null) {
                exchangeCodeService.lambdaUpdate()
                        .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                        .set(ExchangeCode::getUserId, userId)
                        .eq(ExchangeCode::getId, serialNum)
                        .update();
            }
        // throw new RuntimeException("test");
        // }
    }

    //保存用户券
    private void saveUserCoupon(Long userId, Coupon coupon) {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setUserId(userId);
        userCoupon.setCouponId(coupon.getId());
        LocalDateTime termBeginTime = coupon.getTermBeginTime();//优惠券使用 开始时间
        LocalDateTime termEndTime = coupon.getTermEndTime();//优惠券使用 结束时间
        if (termBeginTime == null && termEndTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        userCoupon.setTermBeginTime(termBeginTime);
        userCoupon.setTermEndTime(termEndTime);
        this.save(userCoupon);
    }

    @Override
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        // 1.查询当前用户id
        Long userId = UserContext.getUser();
        // 2.分页查询用户下的优惠券
        Page<UserCoupon> page = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, query.getStatus())
                .page(query.toMpPage("term_end_time", false));
        List<UserCoupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.获取优惠券的详细信息
        // 3.1获取用户券关联的优惠券id
        Set<Long> uids = records.stream().map(UserCoupon::getCouponId).collect(Collectors.toSet());
        // 3.2查询
        List<Coupon> coupons = couponMapper.selectBatchIds(uids);
        // 4.返回封装vo
        List<CouponVO> voList = BeanUtils.copyList(coupons, CouponVO.class);
        return PageDTO.of(page, voList);
    }
}
*/
