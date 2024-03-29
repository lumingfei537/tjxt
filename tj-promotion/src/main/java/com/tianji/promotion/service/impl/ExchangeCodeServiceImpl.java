package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.query.CodeQuery;
import com.tianji.promotion.domain.vo.ExchangeCodeVO;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author zlw
 * @since 2024-03-24
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {
    private final StringRedisTemplate redisTemplate;

    //异步生成兑换码
    @Override
    @Async("generateExchangeCodeExecutor")//  使用generateExchangeCodeExecutor 自定义线程池中的线程异步执行
    public void asyncGenerateExchangeCode(Coupon coupon) {
        // 方式一：循环兑换码总数量 在循环中单个获取自增id  incr  效率不高
        // 方式二：先调用incrby  得到自增id的最大值  然后在循环生成兑换码 （只需要操作一次redis）
        log.debug("生成兑换码  线程名{}", Thread.currentThread().getName());
        Integer totalNum = coupon.getTotalNum();
        // 1.生成自增id 借助redis中 incrby
        Long increment = redisTemplate.opsForValue().increment(PromotionConstants.COUPON_CODE_SERIAL_KEY, totalNum);
        if (increment == null) {
            return;
        }
        int maxSerialNum = increment.intValue();// 本地自增id的最大值
        int begin = maxSerialNum - totalNum + 1;// 自增id 循环的开始值
        // 2.循环生成兑换码 调用工具类生成兑换码
        List<ExchangeCode> list = new ArrayList<>();
        for (int serialNum = begin; serialNum <= maxSerialNum; serialNum++) {
            String code = CodeUtil.generateCode(serialNum, coupon.getId());//参数一为自增id， 参数二为优惠券id（内部会计算出0~15之间一个值）
            ExchangeCode exchangeCode = new ExchangeCode();
            exchangeCode.setId(serialNum);//兑换码id
            exchangeCode.setCode(code);//兑换码
            exchangeCode.setExchangeTargetId(coupon.getId());//对应的优惠券id
            exchangeCode.setExpiredTime(coupon.getIssueEndTime());//兑换码的过期时间就是优惠券领取的截止时间
            // log.debug("过期时间{}", coupon.getIssueEndTime());
            list.add(exchangeCode);
        }
        //将兑换码信息保存到db
        this.saveBatch(list);

        //
        redisTemplate.opsForZSet().add(PromotionConstants.COUPON_RANGE_KEY, coupon.getId().toString(), maxSerialNum);
    }

    @Override
    public PageDTO<ExchangeCodeVO> queryCodePage(CodeQuery query) {
        Page<ExchangeCode> page = this.lambdaQuery()
                .eq(ExchangeCode::getStatus, query.getStatus())
                .eq(ExchangeCode::getExchangeTargetId, query.getCouponId())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        // 返回数据
        return PageDTO.of(page, c -> new ExchangeCodeVO(c.getId(), c.getCode()));
    }

    @Override
    public boolean updateExchangeCodeMark(long serialNum, boolean flag) {
        String key = PromotionConstants.COUPON_CODE_MAP_KEY;
        // 修改兑换码的自增id 对应的offset的值
        Boolean aBoolean = redisTemplate.opsForValue().setBit(key, serialNum, flag);
        return aBoolean != null && aBoolean;
    }
}
