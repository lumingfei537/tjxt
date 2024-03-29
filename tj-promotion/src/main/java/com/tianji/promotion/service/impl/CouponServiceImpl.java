package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.cache.CategoryCache;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponScopeVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    private final ICouponScopeService couponScopeService;
    private final IExchangeCodeService exchangeCodeService;
    private final IUserCouponService userCouponService;
    private final CategoryCache categoryCache;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO dto) {
        // 1.dto转po 保存优惠券 coupon表
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        this.save(coupon);
        // 2.判断是否限定了范围 dto.specific  如果为false直接return
        if (!dto.getSpecific()) {
            return;
        }
        // 3.如果dto.specific为 true  需要校验dto.scopes
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("分类id不能为空");
        }
        // 4.保存优惠券的限定范围 coupon_scope 批量新增
        /*List<CouponScope> csList = new ArrayList<>();
        for (Long scope : scopes) {
            CouponScope couponScope = new CouponScope();
            couponScope.setCouponId(coupon.getId());
            couponScope.setBizId(scope);
            couponScope.setType(1);
            csList.add(couponScope);
        }*/
        List<CouponScope> csList = scopes
                .stream()
                .map(aLong -> new CouponScope().setCouponId(coupon.getId()).setBizId(aLong).setType(1))
                .collect(Collectors.toList());

        couponScopeService.saveBatch(csList);
    }

    @Override
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query) {
        Integer status = query.getStatus();
        String name = query.getName();
        Integer type = query.getType();

        Page<Coupon> page = this.lambdaQuery()
                .eq(type != null, Coupon::getDiscountType, type)
                .eq(status != null, Coupon::getStatus, status)
                .like(StringUtils.isNotBlank(name), Coupon::getName, name)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        List<CouponPageVO> list = BeanUtils.copyList(records, CouponPageVO.class);

        return PageDTO.of(page, list);

    }

    @Override
    public CouponDetailVO queryCouponById(Long id) {
        // 1.查询优惠券
        Coupon coupon = this.getById(id);
        // 2.转换成vo
        CouponDetailVO vo = BeanUtils.copyBean(coupon, CouponDetailVO.class);
        if (vo == null || coupon.getSpecific()) {
            // 数据不存在， 或者不限定范围
            return vo;
        }
        // 3.查询限定范围
        List<CouponScope> scopes = couponScopeService.lambdaQuery()
                .eq(CouponScope::getCouponId, id)
                .list();
        if (CollUtils.isEmpty(scopes)) {
            return vo;
        }

        List<CouponScopeVO> scopeVOS = scopes.stream()
                .map(CouponScope::getBizId)
                .map(cateId -> new CouponScopeVO(cateId, categoryCache.getNameByLv3Id(cateId)))
                .collect(Collectors.toList());

        vo.setScopes(scopeVOS);
        return vo;
    }

    @Override
    @Transactional
    public void updateCoupon(Long id, CouponFormDTO dto) {
        // Coupon old = getById(dto.getId());
        // if (old == null) {
        //     throw new BadRequestException("优惠券不存在");
        // }
        // Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        // if (coupon == null || coupon.getStatus() != CouponStatus.DRAFT) {
        //     throw new BadRequestException("优惠券不存在或者优惠券正在使用中");
        // }
        // // 修改
        // updateById(coupon);
        //
        // if ((!old.getSpecific() && !dto.getSpecific()) || dto.getSpecific() == null) {
        //     // 没有限定范围，直接退出
        //     return;
        // }
        //
        // List<Long> scopes = dto.getScopes();
        // if (CollUtils.isEmpty(scopes)) {
        //     throw new BadRequestException("分类id不能为空");
        // }
        //
        // // 删除旧的范围
        // Long couponId = coupon.getId();
        // couponScopeService.removeByCouponId(couponId);
        //
        //
        // List<CouponScope> csList = scopes.stream()
        //         .map(aLong -> new CouponScope().setCouponId(coupon.getId()).setBizId(aLong).setType(1)).collect(Collectors.toList());
        // couponScopeService.saveBatch(csList);

        Coupon old = getById(dto.getId());
        if (old == null) {
            throw new BadRequestException("优惠券信息不存在");
        }
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        if (!coupon.getSpecific() || dto.getSpecific() == null) {
            // 没有指定限定范围，直接返回
            return;
        }
        updateById(coupon);

        // 对coupon.getSpecific()进行判断
        Long couponId = coupon.getId();
        couponScopeService.removeByCouponId(couponId);
    }

    @Override
    public void deleteById(Long id) {
        // 1.查询
        Coupon coupon = getById(id);
        if (coupon == null || coupon.getStatus() != CouponStatus.DRAFT) {
            throw new BadRequestException("优惠券不存在或者优惠券正在使用中");
        }
        // 2.删除优惠券
        boolean success = remove(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getId, id)
                .eq(Coupon::getStatus, CouponStatus.DRAFT));
        if (!success) {
            throw new BadRequestException("优惠券不存在或者优惠券正在使用中");
        }
        // 3.删除优惠券对应的限定范围
        if (!coupon.getSpecific()) {
            return;
        }
        couponScopeService.remove(new LambdaQueryWrapper<CouponScope>().eq(CouponScope::getCouponId, id));
    }

    @Override
    public void issueCoupon(Long id, CouponIssueFormDTO dto) {
        log.debug("发放优惠券 线程名{}", Thread.currentThread().getName());
        // 1.校验
        if (id == null || !id.equals(dto.getId())) {
            throw new BadRequestException("非法参数");
        }
        // 2.校验优惠券id是否存在
        Coupon coupon = this.getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        // 3.校验优惠券状态  只有待发放和暂停状态才能发放
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE) {
            throw new BizIllegalException("只有待发放和暂停状态才能发放");
        }
        LocalDateTime now = LocalDateTime.now();

        // 该变量代表优惠券是否立刻发放
        boolean isBeginIssue = (dto.getIssueBeginTime() == null || !dto.getIssueBeginTime().isAfter(now));

        // 4.修改优惠券的 领取开始和结束日期  使用有效期开始和结束日  天数  状态
       /* 方式一
        if (isBeginIssue) {
            tmp.setIssueBeginTime(now);
            tmp.setIssueEndTime(dto.getIssueEndTime());
            tmp.setStatus(CouponStatus.ISSUING);// 如果是立即发放，优惠券需要修改为进行中
            tmp.setTermDays(dto.getTermDays());
            tmp.setTermBeginTime(dto.getTermBeginTime());
            tmp.setTermEndTime(dto.getTermEndTime());
        } else {
            tmp.setIssueBeginTime(dto.getIssueBeginTime());
            tmp.setIssueEndTime(dto.getIssueEndTime());
            tmp.setStatus(CouponStatus.UN_ISSUE);
            tmp.setTermDays(dto.getTermDays());
            tmp.setTermBeginTime(dto.getTermBeginTime());
            tmp.setTermEndTime(dto.getTermEndTime());
        }
        this.updateById(tmp);*/

        // 方式二
        Coupon tmp = BeanUtils.copyBean(dto, Coupon.class);
        if (isBeginIssue) {
            tmp.setStatus(CouponStatus.ISSUING);
            tmp.setIssueBeginTime(now);
        } else {
            tmp.setStatus(CouponStatus.UN_ISSUE);
        }
        this.updateById(tmp);

        // 5.如果优惠券的领取方式为指定发放 ，需要生成兑换码
        // 优惠券的领取方式为指定发送 且 优惠券之前的状态为待发送
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT) {
            // 兑换码的截止时间 就是优惠券的领取截止时间； 该时间是从前端传的封装到了couponDB中
            coupon.setIssueEndTime(tmp.getIssueEndTime());
            exchangeCodeService.asyncGenerateExchangeCode(coupon);//异步生成兑换码
        }
    }

    @Override
    public void pauseIssue(Long id) {
        // 1.查询旧的优惠券
        Coupon coupon = getById(id);
        // 2.当前券状态必须是未开始或发放中
        CouponStatus status = coupon.getStatus();
        if (status != CouponStatus.UN_ISSUE && status != CouponStatus.ISSUING) {
            throw new BizIllegalException("只有状态是未开始或发放中才能暂停");
        }
        // 3.更新状态
        boolean success = this.lambdaUpdate()
                .set(Coupon::getStatus, CouponStatus.PAUSE)
                .eq(Coupon::getId, id)
                .in(Coupon::getStatus, CouponStatus.UN_ISSUE, CouponStatus.ISSUING)
                .update();
        if (!success) {
            log.error("重复暂停优惠券");
        }
        //TODO 4.删除缓存
    }

    @Override
    public List<CouponVO> queryIssuingCoupon() {
        // 1.查询db coupon 条件， 发放中  手动领取
        List<Coupon> couponList = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        if (CollUtils.isEmpty(couponList)) {
            return CollUtils.emptyList();
        }

        // 2.查询user_coupon表 条件 ：当前用户 发放中的优惠券id
        //正在发送的优惠券id集合
        Set<Long> couponIds = couponList.stream().map(Coupon::getId).collect(Collectors.toSet());
        //当前用户针对正在发放的优惠券领取记录
        List<UserCoupon> list = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .in(UserCoupon::getCouponId, couponIds)
                .list();

        // 2.1 统计当前用户 针对每一个卷 的领取数量
        Map<Long, Long> issueMap = list.stream().
                collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        // 2.2 统计当前用户 针对每一个卷的已领且未使用的数量
        Map<Long, Long> unuseMap = list.stream()
                .filter(c -> c.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        // 3.po转vo
        List<CouponVO> voList = new ArrayList<>();
        for (Coupon coupon : couponList) {
            CouponVO vo = BeanUtils.copyBean(coupon, CouponVO.class);
            // 优惠券还有剩余 （issue_num < total_num）且 (统计用户卷表user_coupon取出当前用户已领数量 < user_limit)
            Long issNum = issueMap.getOrDefault(coupon.getId(), 0L);
            boolean available = coupon.getIssueNum() < coupon.getTotalNum() && issNum.intValue() < coupon.getUserLimit();
            vo.setAvailable(available);//是否可以领取
            // 统计取出用户已经领取且未使用的数量
            boolean received = unuseMap.getOrDefault(coupon.getId(), 0L) > 0;
            vo.setReceived(received);//是否可以使用
            voList.add(vo);
        }
        return voList;
    }
}
