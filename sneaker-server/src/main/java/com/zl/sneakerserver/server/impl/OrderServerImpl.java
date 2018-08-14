package com.zl.sneakerserver.server.impl;

import com.zl.sneakerentity.dao.OrderDetailDao;
import com.zl.sneakerentity.dao.OrderMasterDao;
import com.zl.sneakerentity.dao.ProductInfoDao;
import com.zl.sneakerentity.enums.OrderStatusEnum;
import com.zl.sneakerentity.enums.PayStatusEnum;
import com.zl.sneakerentity.enums.ProductInfoStateEnum;
import com.zl.sneakerentity.enums.ResultEnum;
import com.zl.sneakerentity.model.OrderDetail;
import com.zl.sneakerentity.model.OrderMaster;
import com.zl.sneakerentity.model.ProductInfo;
import com.zl.sneakerserver.dto.OrderDto;
import com.zl.sneakerserver.exceptions.OrderException;
import com.zl.sneakerserver.server.OrderServer;
import com.zl.sneakerserver.server.ProductInfoServer;
import com.zl.sneakerserver.utils.KeyUtils;
import com.zl.sneakerserver.utils.PageCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * @Auther: le
 * @Date: 2018/7/27 16:45
 * @Description:
 */
@Service
@Slf4j
public class OrderServerImpl implements OrderServer {
    @Autowired
    OrderMasterDao orderMasterDao;

    @Autowired
    OrderDetailDao orderDetailDao;

    @Autowired
    ProductInfoDao productInfoDao;

    @Autowired
    ProductInfoServer productInfoServer;

    /**
     * 创建订单
     *
     * @param orderDto
     * @return
     * @throws OrderException
     */
    @Override
    @Transactional
    public OrderDto createOrder(OrderDto orderDto) throws OrderException {
        if (orderDto.getOrderDetailList().get(0) != null) {
            String orderId = KeyUtils.genUniqueKey();
            //订单价格
            BigDecimal orderAmount = new BigDecimal(BigInteger.ZERO);
            try {
                //获取购物车中商品数量，然后计算总价
                for (OrderDetail orderDetail : orderDto.getOrderDetailList()) {
                    //通过订单id查询商品
                    ProductInfo productInfo = productInfoServer.findById(orderDetail.getProductId());
                    //判断商品库存
                    if(productInfo.getProductStock() - orderDetail.getProductQuantity()<=-1){
                        throw new OrderException("商品库存不够");
                    } else {
                        //计算总价
                        orderAmount = productInfo.getProductPrice()
                                .multiply(new BigDecimal(orderDetail.getProductQuantity()))
                                .add(orderAmount);
                        orderDetail.setDetailId(KeyUtils.genUniqueKey());
                        orderDetail.setOrderId(orderId);
                        //BeanUtils 的拷贝，需要对应的实体的set方法名相同
                        BeanUtils.copyProperties(productInfo, orderDetail);
                        //添加订单概述
                        Integer ord = orderDetailDao.insertOrderDetail(orderDetail);
                        if (ord <=0){
                            return new OrderDto(ResultEnum.ORDERDETAIL_NOT_EXIST);
                        }else{
                            //减库存
                            productInfoDao.updateProductQuantity(orderDetail.getProductId(),
                                    orderDetail.getProductQuantity());
                        }
                    }
                }
                //订单数据
                OrderMaster orderMaster = new OrderMaster();
                orderDto.setOrderId(orderId);
                BeanUtils.copyProperties(orderDto, orderMaster);
                orderMaster.setOrderId(orderId);
                orderMaster.setOrderStatus(OrderStatusEnum.NEW.getState());
                orderMaster.setPayStatus(PayStatusEnum.WAIT.getState());
                orderMaster.setOrderAmount(orderAmount);

                //创建订单
                Integer effectnum = orderMasterDao.insertOrderMaster(orderMaster);
                if (effectnum <= 0) {
                    return new OrderDto(ProductInfoStateEnum.INNER_ERROE);
                } else {
                    return new OrderDto(ProductInfoStateEnum.SUCCESS, orderMaster.getOrderId());
                }
            } catch (Exception e) {
                throw new OrderException("创建订单错误 error:" + e.getMessage());
            }
        } else {
            log.error("createOrder error:{}", ProductInfoStateEnum.EMPTY.getStateInfo());
            return new OrderDto(ProductInfoStateEnum.EMPTY);
        }
    }


    /**
     * 通过openid查找订单
     *
     * @param buyerOpenId
     * @param pageIndex
     * @param pageSize
     * @return
     */
    @Override
    @Transactional
    public OrderDto findListByOpenId(String buyerOpenId, Integer pageIndex, Integer pageSize) {
        //分页数
        int rowIndex = PageCalculator.calculateRowIndex(pageIndex, pageSize);
        OrderDto orderDto = new OrderDto();
        try {
            List<OrderMaster> orderMasterList = orderMasterDao.selectOrderMasterByOpenid(buyerOpenId, rowIndex, pageSize);
            //返回数据
            orderDto.setOrderMasterList(orderMasterList);
        } catch (Exception e) {
            throw new OrderException("运单查找失败 error:" + e.getMessage());
        }
        return orderDto;
    }

    /**
     * 通过orderid查询
     *
     * @param orderId
     * @return
     */
    @Override
    @Transactional
    public OrderDto findByOrderId(String orderId) {
        OrderDto orderdto = new OrderDto();
        try {
            OrderMaster orderMaster = orderMasterDao.selectByOrderId(orderId);
            orderdto.setOrderMaster(orderMaster);
        } catch (Exception e) {
            throw new OrderException("通过orderid查询 error:" + e.getMessage());
        }
        return orderdto;
    }

    /**
     * 取消订单
     *
     * @param openId
     * @param orderId
     * @return
     * @throws OrderException
     */
    @Override
    @Transactional
    public OrderDto cancelOrder(String openId, String orderId) throws OrderException {
        //调用witherOrderIdEqualsOpenid 方法,判断openid是否属于orderid
        OrderDto orderDto = witherOrderIdEqualsOpenid(openId, orderId);
        if (OrderStatusEnum.CANCEL.getState().equals(orderDto.getState())) {
            try {
                //更新订单状态
                Integer effectNum = orderMasterDao.updateOrderById(openId, orderId);
                if (effectNum <= 0) {
                    return new OrderDto(ResultEnum.ORDER_UPDATE_FAIL);
                } else {
                    //取消订单成功，库存回滚
                    for (OrderDetail orderDetail : orderDetailDao.selectOrderDetailList(orderId)){
                        //获取id 和数量
                        String productId = orderDetail.getProductId();
                        Integer productQuantity = orderDetail.getProductQuantity();
                        //回滚库存
                        productInfoDao.backProductQuantity(productId,productQuantity);
                    }
                    //通过id 获取订单详情
                    OrderMaster orderMaster = findByOrderId(orderId).getOrderMaster();
                    //将更新后的orderMaster 赋值orderDto
                    BeanUtils.copyProperties(orderMaster, orderDto);
                }
            } catch (Exception e) {
                throw new OrderException("取消订单失败 error: " + e.getMessage());
            }
        }
        return orderDto;
    }

    /**
     * //判断openid是否属于orderid
     *
     * @param openId
     * @param orderId
     * @return
     * @throws OrderException
     */
    @Override
    @Transactional
    public OrderDto witherOrderIdEqualsOpenid(String openId, String orderId) throws OrderException {
        OrderDto orderDto = new OrderDto();
        try {
            //判断openid与orderid
            Integer effectNum = orderMasterDao.selectOpenIdandOrderId(openId, orderId);
            if (effectNum <= 0) {
                //订单不属于这个openid
                return new OrderDto(ResultEnum.ORDER_OWNER_ERROR);
            } else {
                //订单取消成功
                orderDto.setState(OrderStatusEnum.CANCEL.getState());
                orderDto.setStateInfo(OrderStatusEnum.CANCEL.getStateInfo());
            }
        } catch (Exception e) {
            log.error("判断openid与orderid错误 error:{}", e.getMessage());
            throw new OrderException("判断openid与orderid错误 error: " + e.getMessage());
        }
        return orderDto;
    }

}