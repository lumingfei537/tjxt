package com.tianji.learning.service.impl;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;
    @Override
    public SignResultVO ISignRecordService() {
        // 1.获取用户id
        Long userId = UserContext.getUser();
        // 2.拼接key
        LocalDate now = LocalDate.now();//当前时间的年月
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + userId.toString() + format;
        // 3.利用bitset命令 将签到记录保存到redis的bitmap结构中  需要校验是否已签到
        int offset = now.getDayOfMonth() - 1;// 偏移量
        Boolean setBit = redisTemplate.opsForValue().setBit(key, offset, true);
        if (setBit) {
            // 说明当天已经签过到了
            throw new BizIllegalException("不能重复签到");
        }
        // 4.计算连续签到的天数
        int days = countSignDays(key, now.getDayOfMonth());
        // 5.计算连续签到 奖励积分
        int rewardPoints = 0;//代表连续签到奖励积分
        switch (days) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }

        // 6. 保存积分
        mqHelper.send(
                MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));

        // 7.封装vo返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(days);
        vo.setRewardPoints(rewardPoints);
        return vo;
    }

    @Override
    public Byte[] querySignRecords() {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.拼接key
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();

        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + userId.toString() + format;

        // 3.查询签到记录
        List<Long> bitField = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtils.isEmpty(bitField)) {
            return new Byte[0];
        }

        int num = bitField.get(0).intValue();

        Byte[] arr = new Byte[dayOfMonth];
        int pos = dayOfMonth - 1;
        while (pos >= 0) {
            arr[pos--] = (byte) (num & 1);
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return arr;
    }

    /**
     * 计算连续签到多少天
     * @param key 缓存中的key
     * @param dayOfMonth 本月第一天到今天的 天数
     * @return 签到的天数
     */
    private int countSignDays(String key, int dayOfMonth) {
        // 1.求本月第一天到今天所有的签到数据
        List<Long> bitField = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtils.isEmpty(bitField)) {
            return 0;
        }
        int num = bitField.get(0).intValue();//本月第一天到今天的签到数据 拿到的十进制
        log.debug("num  {}", num);
        // 2.num转二进制 从后往前推共有多少个1  与运算  右移
        int counter = 0;//计数器
        while ((num & 1) == 1) {
            counter++;
            num = num>>>1;// 右移一位
        }
        return counter;
    }

}
