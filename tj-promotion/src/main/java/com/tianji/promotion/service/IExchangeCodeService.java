package com.tianji.promotion.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.query.CodeQuery;
import com.tianji.promotion.domain.vo.ExchangeCodeVO;

/**
 * <p>
 * 兑换码 服务类
 * </p>
 *
 * @author zlw
 * @since 2024-03-24
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {

    //异步生成兑换码
    void asyncGenerateExchangeCode(Coupon coupon);

    PageDTO<ExchangeCodeVO> queryCodePage(CodeQuery query);

    boolean updateExchangeCodeMark(long serialNum, boolean flag);
}
