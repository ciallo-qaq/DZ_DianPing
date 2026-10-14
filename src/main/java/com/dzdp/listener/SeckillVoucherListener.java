package com.dzdp.listener;

import cn.hutool.json.JSONUtil;
import com.dzdp.entity.VoucherOrder;
import com.dzdp.service.impl.SeckillVoucherServiceImpl;
import com.dzdp.service.impl.VoucherOrderServiceImpl;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    @Resource
    SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(queues = "QA")
    public void receivedA(Message message, Channel channel)throws Exception{
        String msg=new String(message.getBody());
        log.info("正常队列:");
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info(voucherOrder.toString());
        if (!saveOrder(voucherOrder)) {
            // 重复投递：订单已经落过库，直接结束，避免消息被反复重投
            return;
        }
        //数据库秒杀库存减一
        Long voucherId=voucherOrder.getVoucherId();
        seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();

    }


    @RabbitListener(queues = "QD")
    public void receivedD(Message message)throws Exception{
        log.info("死信队列:");
        String msg=new String(message.getBody());
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info(voucherOrder.toString());
        if (!saveOrder(voucherOrder)) {
            return;
        }

        Long voucherId=voucherOrder.getVoucherId();
        seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();

    }

    /**
     * 订单落库，重复消息返回 false，保证消费幂等。
     *
     * <p>依赖 tb_voucher_order 上的两个唯一约束：主键 id（同一条消息重投）和
     * uk_user_voucher(user_id, voucher_id)（一人一单，兜底 Redis 丢数据的情况）。
     * 若唯一约束缺失，重复消息会插入出重复订单，这里没有兜底。</p>
     */
    private boolean saveOrder(VoucherOrder voucherOrder) {
        try {
            voucherOrderService.save(voucherOrder);
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("订单已存在，忽略重复消息 orderId={} userId={} voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return false;
        }
    }
}
