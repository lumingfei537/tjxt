package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.discount.Discount;
import com.tianji.promotion.discount.DiscountStrategy;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.CodeUtil;
import com.tianji.promotion.utils.MyLock;
import com.tianji.promotion.utils.MyLockStrategy;
import com.tianji.promotion.utils.MyLockType;
import com.tianji.promotion.utils.PermuteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponMqServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final ICouponScopeService couponScopeService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final RabbitMqHelper mqHelper;
    private final Executor calculteSolutionExecutor;
    // 领取优惠券
    @Override
    // @Transactional
    // 分布式锁可以对 优惠券加锁
    @MyLock(name = "lock:coupon:uid:#{id}")
    public void receiveCoupon(Long id) {
        // 1.根据id查询优惠券信息，做相关校验
        if (id == null) {
            throw new BadRequestException("非法参数");
        }
        // Coupon coupon = couponMapper.selectById(id);
        // 从redis中获取优惠券信息
        Coupon coupon = queryCouponByCache(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("该优惠券已过期或未开始发放");
        }
        // if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
        if (coupon.getTotalNum() <= 0) {
            throw new BadRequestException("该优惠券库存不足");
        }
        Long userId = UserContext.getUser();

        // 获取当前用户 对该优惠券 已领数量 user_coupon表  条件：userid couponId 统计数量
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
        saveUserCoupon(userId, coupon);*/

        // 统计已领取数量
        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + id;
        // increment 代表本次领取后的 已领数量
        Long increment = redisTemplate.opsForHash().increment(key, userId.toString(), 1);
        // 校验是否超过限领数量
        if (increment > coupon.getUserLimit()) {// increment是+1后的结果，所以此处不能等于
            throw new BizIllegalException("超出限领范围");
        }
        //修改优惠券的库存 -1
        String couponKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
        redisTemplate.opsForHash().increment(couponKey, "totalNum", -1);

        //发送消息到mq
        UserCouponDTO msg = new UserCouponDTO();
        msg.setUserId(userId);
        msg.setCouponId(id);
        mqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE,
                MqConstants.Key.COUPON_RECEIVE,
                msg);
        /*synchronized (userId.toString().intern()) {
            checkAndCreateUserCoupon(userId, coupon, null);
        }*/

        /*synchronized (userId.toString().intern()) {
            // 从aop上下文中 获取当前类的代理对象
            IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
            //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原对象
            userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);// 这种写法是调用代理对象的方法 方法是有事务的
        }*/

        // 通过redisson实现分布式锁
        /*String key = "lock:coupon:uid:" + userId;
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
        }*/

        /*IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();//调用的代理对象
        userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);*/
    }

    /**
     * 从redis中获取优惠券信息（只能获取领取开始和结束时间， 发行总数量， 限领数量）
     * @param id
     * @return
     */
    private Coupon queryCouponByCache(Long id) {
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;

        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        Coupon coupon = BeanUtils.mapToBean(entries, Coupon.class, false, CopyOptions.create());

        return coupon;
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
    @MyLock(name = "lock:coupon:uid:#{userId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStrategy = MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
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
            int r = couponMapper.incrIssueNum(coupon.getId());
            if (r == 0) {
                throw new BizIllegalException("优惠券库存不足！");
            }
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
    @Transactional
    public void checkAndCreateUserCouponNew(UserCouponDTO msg) {
        /*Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, coupon.getId())
                .count();
        if (count != null && count >= coupon.getUserLimit()) {
            throw new BadRequestException("已达到领取上限");
        }*/

        // 1.从db中查询优惠券信息
        Coupon coupon = couponMapper.selectById(msg.getCouponId());
        if (coupon == null) {
            return;
        }
        // 2.优惠券的已发放数量+1
        int num = couponMapper.incrIssueNum(coupon.getId());
        if (num == 0) {
            // throw new BizIllegalException("券已领完");
            return;
        }
        // 3.生成用户券
        saveUserCoupon(msg.getUserId(), coupon);

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

    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> courses) {
        // 1.查询当前用户可用的优惠券 coupon 和 user_coupon表  条件：userid， status=1  查询字段：优惠券规则 优惠券id 用户券id
        List<Coupon> coupons = getBaseMapper().queryMyCoupons(UserContext.getUser());
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        log.debug("用户的优惠券共有{}张", coupons.size());
        for (Coupon coupon : coupons) {
            log.debug("优惠券： {}， {}",
                    DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon), coupon);
        }
        // 2.初筛
        // 2.1 计算订单的总金额，对coupons的price进行累加
        int totalAmount = courses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        log.debug("订单总金额：{}", totalAmount);
        // 2.2 校验优惠券是否可用
        List<Coupon> availableCoupons = coupons.stream().filter(coupon -> DiscountStrategy.getDiscount(coupon.getDiscountType()).
                canUse(totalAmount, coupon)).collect(Collectors.toList());
        if (CollUtils.isEmpty(availableCoupons)) {
            return CollUtils.emptyList();
        }
        log.debug("经过初筛之后，还剩{}张", availableCoupons.size());
        for (Coupon coupon : availableCoupons) {
            log.debug("优惠券：{}, {}",
                    DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon), coupon);
        }
        // 3.细筛（需要考虑优惠券的限定范围） 排列组合
        Map<Coupon, List<OrderCourseDTO>> availableCouponMap = findAvailableCoupon(availableCoupons, courses);
        if (CollUtils.isEmpty(availableCouponMap)) {
            return CollUtils.emptyList();
        }
        Set<Map.Entry<Coupon, List<OrderCourseDTO>>> entries = availableCouponMap.entrySet();
        for (Map.Entry<Coupon, List<OrderCourseDTO>> entry : entries) {
            log.debug("细筛之后的优惠券： {}， {}",
                    DiscountStrategy.getDiscount(entry.getKey().getDiscountType()).getRule(entry.getKey()),
                    entry.getKey());
            List<OrderCourseDTO> value = entry.getValue();
            for (OrderCourseDTO courseDTO : value) {
                log.debug("可用课程 {}", courseDTO);
            }
        }
        availableCoupons = new ArrayList<>(availableCouponMap.keySet());//才是真正可用的优惠券集合
        log.debug("经过细筛之后的 优惠券个数：{}", availableCoupons.size());
        for (Coupon coupon : availableCoupons) {
            log.debug("优惠券： {}， {}",
                    DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon), coupon);
        }
        // 排列组合
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        for (Coupon coupon : availableCoupons) {
            solutions.add(List.of(coupon));// 添加单券到方案中
        }
        log.debug("排列组合");
        for (List<Coupon> solution : solutions) {
            List<Long> cids = solution.stream().map(Coupon::getId).collect(Collectors.toList());
            log.debug("{}", cids);
        }

        // 4.计算每一种组合的优惠明细
        /*log.debug("开始计算 每一种组合的优惠明细");
        List<CouponDiscountDTO> dtos = new ArrayList<>();
        for (List<Coupon> solution : solutions) {
            CouponDiscountDTO dto = calculateSolutionDiscount(availableCouponMap, courses, solution);
            log.debug("方案最终优惠 {}  方案中优惠券使用了 {} 规则{}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
            dtos.add(dto);
        }*/

        // 5.使用多线程改造第4步 并行计算每一种组合的优惠情况
        log.debug("多线程开始计算 每一种组合的优惠明细");
        // List<CouponDiscountDTO> dtos1 = new ArrayList<>();//线程不安全
        List<CouponDiscountDTO> dtos = Collections.synchronizedList(new ArrayList<>(solutions.size()));// 线程安全
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture.supplyAsync(new Supplier<CouponDiscountDTO>() {
                @Override
                public CouponDiscountDTO get() {
                    log.debug("线程 {}  开始计算方案 {}", Thread.currentThread().getName(),
                            solution.stream().map(Coupon::getId).collect(Collectors.toSet()));
                    CouponDiscountDTO dto = calculateSolutionDiscount(availableCouponMap, courses, solution);
                    return dto;
                }
            }, calculteSolutionExecutor).thenAccept(new Consumer<CouponDiscountDTO>() {
                @Override
                public void accept(CouponDiscountDTO dto) {
                    log.debug("方案最终优惠 {}  方案中优惠券使用了 {} 规则{}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
                    dtos.add(dto);
                    latch.countDown();//计数器减1
                }
            });
        }
        try {
            latch.await(2, TimeUnit.SECONDS);//主线程会最多阻塞2秒
        } catch (InterruptedException e) {
            log.error("多线程计算组合优惠明细 报错了", e);
        }

        // 6.筛选最优解
        return findBestSolution(dtos);
    }

    /**
     * 求最优解
     * - 用券相同时，优惠金额最高的方案
     * - 优惠金额相同时，用券最少的方案
     * @param solutions
     * @return
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> solutions) {
        // 1.创建两个map， 分别记录用券相同，金额最高   金额相同，用券最少
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer, CouponDiscountDTO> lessDiscountMap = new HashMap<>();

        // 2.循环方案 向map中记录
        for (CouponDiscountDTO solution : solutions) {
            // 2.1 对优惠券id 升序，转字符串  然后逗号拼接
            String ids = solution.getIds().stream().sorted(Comparator.comparing(Long::longValue)).map(String::valueOf)
                    .collect(Collectors.joining(","));
            // 2.2.比较用券相同时，优惠金额是否最大
            CouponDiscountDTO old = moreDiscountMap.get(ids);
            if (old != null && old.getDiscountAmount() >= solution.getDiscountAmount()) {
                continue;
            }
            // 2.3.比较金额相同时，用券数量是否最少
            old = lessDiscountMap.get(solution.getDiscountAmount());
            int newSize = solution.getIds().size();// 当前方案的用券数量
            if (old != null && newSize > 1 && old.getIds().size() <= newSize) {
                continue;
            }
            // 2.4.添加更优方案到map中
            moreDiscountMap.put(ids, solution);//说明当前方案 更优
            lessDiscountMap.put(solution.getDiscountAmount(), solution);//说明当前方案更优
        }
        // 3.求两个map的交集
        Collection<CouponDiscountDTO> bestSolution = CollUtils.intersection(moreDiscountMap.values(), lessDiscountMap.values());
        // 4.对最终方案 按优惠金额 倒序
        List<CouponDiscountDTO> latestBestSolution = bestSolution.stream()
                .sorted(Comparator.comparing(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
        return latestBestSolution;
    }

    /**
     * 计算每一个方案的 优惠信息
     * @param availableCouponMap 该优惠券和可用课程的映射集合
     * @param courses 订单中所有的课程
     * @param solution 方案
     * @return
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> availableCouponMap, List<OrderCourseDTO> courses, List<Coupon> solution) {
        // 1.创建方案结果dto对象
        CouponDiscountDTO dto = new CouponDiscountDTO();
        // 2.初始化商品id和商品折扣明细的映射， 初始折扣明细全都设置为0 设置map结构，key为商品的id，value初始值都为0
        Map<Long, Integer> detailMap = courses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, courseDTO -> 0));
        // 3.计算方案的优惠信息
        // 3.1循环方案中优惠券
        for (Coupon coupon : solution) {
            // 3.2取出该优惠券对应的可用课程
            List<OrderCourseDTO> availableCourses = availableCouponMap.get(coupon);
            // 3.3计算可用课程的总金额（商品价格 - 该商品的折扣明细）
            int totalAmount = availableCourses.stream().mapToInt(value -> value.getPrice() - detailMap.get(value.getId())).sum();
            // 3.4判断优惠券是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(totalAmount, coupon)) {
                continue;// 券不可用，跳出循环，继续处理下一个券
            }
            // 3.5计算优惠券使用后的折扣值
            int discountAmount = discount.calculateDiscount(totalAmount, coupon);
            // 3.6计算商品的折扣明细，更新到detailMap中
            calculateDetailDiscount(detailMap, availableCourses, totalAmount, discountAmount);
            // 3.7累加每一个优惠券的优惠金额 赋值给方案结果dto对象
            dto.getIds().add(coupon.getId());// 只要执行当前这句话，就意味着这个优惠券生效了
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount());// 不能覆盖，应该是所有生效的优惠券累加的结果
        }

        return dto;
    }

    /**
     * 计算商品的折扣明细
     * @param detailMap 商品id和商品的优惠明细 映射
     * @param availableCourses 当前优惠券可用的课程集合
     * @param totalAmount 可用的课程的总金额
     * @param discountAmount 当前优惠券能优惠的金额
     */
    private void calculateDetailDiscount(Map<Long, Integer> detailMap, List<OrderCourseDTO> availableCourses,
                                         int totalAmount, int discountAmount) {
        // 目的：本方法就是优惠券在使用后  计算每个商品的折扣明细
        // 规则：前面的商品按比例计算，最后一个商品折扣明细 = 总的优惠金额 - 前面商品优惠的总额
        // 循环可用商品
        int times = 0;
        int remainDiscount = discountAmount;//代表剩余的优惠金额

        for (OrderCourseDTO c : availableCourses) {
            times++;
            int discount = 0;
            if (times == availableCourses.size()) {
                //说明是最后一个课程
                discount = remainDiscount;
            } else {
                // 是前面的课程
                discount = c.getPrice() * discountAmount / totalAmount;//此处先乘 在除，否则为0 因为 100/300后向下取整为0
                remainDiscount = remainDiscount - discount;
            }
            // 将商品的折扣明细添加到detailMap
            detailMap.put(c.getId(), discount + detailMap.get(c.getId()));
        }
    }

    /**
     * 细筛，查询每一个优惠券，对应的可用课程
     * @param coupons 初筛之后的优惠券集合
     * @param courses 订单中的课程集合
     * @return
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupon(List<Coupon> coupons, List<OrderCourseDTO> courses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>(coupons.size());
        for (Coupon coupon : coupons) {
            // 1.找出优惠券的可用的课程
            List<OrderCourseDTO> availableCourses = courses;
            if (coupon.getSpecific()) {
                // 1.1 限定了范围，查询优惠券的可用范围
                List<CouponScope> scopes = couponScopeService.lambdaQuery()
                        .eq(CouponScope::getCouponId, coupon.getId())
                        .list();
                // 1.2 获取范围对应的分类id
                Set<Long> scopeIds = scopes.stream().map(CouponScope::getBizId).collect(Collectors.toSet());
                // 1.3 筛选课程
                availableCourses = courses.stream()
                        .filter(c -> scopeIds.contains(c.getCateId())).collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) {
                // 说明当前优惠券限定了范围，但是在订单中的课程中没有找到可用课程，说明该券不可用，忽略该券，进行下一个优惠券的处理
                continue;
            }
            // 2.计算课程总价
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            // 3.判断是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount.canUse(totalAmount, coupon)) {
                map.put(coupon, availableCourses);
            }

        }
        return map;
    }
}
