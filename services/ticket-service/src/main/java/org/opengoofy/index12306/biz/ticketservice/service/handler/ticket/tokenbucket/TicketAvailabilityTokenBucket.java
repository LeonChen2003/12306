/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.common.toolkit.Assert;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/** 列车车票余量令牌桶，应对海量并发场景下满足并行、限流以及防超卖等场景 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TicketAvailabilityTokenBucket {

  private final TrainStationService trainStationService;
  private final DistributedCache distributedCache;
  private final RedissonClient redissonClient;
  private final SeatMapper seatMapper;
  private final TrainMapper trainMapper;

  private static final String LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH =
      "lua/ticket_availability_token_bucket.lua";
  private static final String LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH =
      "lua/ticket_availability_rollback_token_bucket.lua";

  /**
   * 获取车站间令牌桶中的令牌访问 如果返回 {@link Boolean#TRUE} 代表可以参与接下来的购票下单流程 如果返回 {@link Boolean#FALSE}
   * 代表当前访问出发站点和到达站点令牌已被拿完，无法参与购票下单等逻辑
   *
   * @param requestParam 购票请求参数入参
   * @return 是否获取列车车票余量令牌桶中的令牌，{@link Boolean#TRUE} or {@link Boolean#FALSE}
   */
  public boolean takeTokenFromBucket(PurchaseTicketReqDTO requestParam) {
    // 获取列车信息
    TrainDO trainDO =
        distributedCache.safeGet(
            TRAIN_INFO + requestParam.getTrainId(),
            TrainDO.class,
            () -> trainMapper.selectById(requestParam.getTrainId()),
            ADVANCE_TICKET_DAY,
            TimeUnit.DAYS);
    // 获取列车经停站之间的数据集合，因为一旦失效要读取整个列车的令牌并重新赋值
    List<RouteDTO> routeDTOList =
        trainStationService.listTrainStationRoute(
            requestParam.getTrainId(), trainDO.getStartStation(), trainDO.getEndStation());
    StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
    // 令牌容器是个 Hash 结构，组装令牌 Hash Key
    String actualHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
    Boolean hasKey = distributedCache.hasKey(actualHashKey);
    // 如果令牌容器 Hash 数据结构不存在了，执行加载流程
    if (!hasKey) {
      RLock lock =
          redissonClient.getLock(
              String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
      lock.lock();
      try {
        Boolean hasKeyTwo = distributedCache.hasKey(actualHashKey);
        if (!hasKeyTwo) {
          List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
          Map<String, String> ticketAvailabilityTokenMap = new HashMap<>();
          for (RouteDTO each : routeDTOList) {
            List<SeatTypeCountDTO> seatTypeCountDTOList =
                seatMapper.listSeatTypeCount(
                    Long.parseLong(requestParam.getTrainId()),
                    each.getStartStation(),
                    each.getEndStation(),
                    seatTypes);
            for (SeatTypeCountDTO eachSeatTypeCountDTO : seatTypeCountDTOList) {
              String buildCacheKey =
                  StrUtil.join(
                      "_",
                      each.getStartStation(),
                      each.getEndStation(),
                      eachSeatTypeCountDTO.getSeatType());
              ticketAvailabilityTokenMap.put(
                  buildCacheKey, String.valueOf(eachSeatTypeCountDTO.getSeatCount()));
            }
          }
          stringRedisTemplate
              .opsForHash()
              .putAll(
                  TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId(),
                  ticketAvailabilityTokenMap);
        }
      } finally {
        lock.unlock();
      }
    }
    // 获取到 Redis 执行的 Lua 脚本
    DefaultRedisScript<Long> actual =
        Singleton.get(
            LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH,
            () -> {
              DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
              redisScript.setScriptSource(
                  new ResourceScriptSource(
                      new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
              redisScript.setResultType(Long.class);
              return redisScript;
            });
    Assert.notNull(actual);
    // 因为购票时，一个用户可以为多个乘车人买票，而多个乘车人又能购买不同的票，所以这里需要根据座位类型进行分组
    Map<Integer, Long> seatTypeCountMap =
        requestParam.getPassengers().stream()
            .collect(
                Collectors.groupingBy(
                    PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));
    // 最终结构就是拆分为一个 Map，Key 是座位类型，value 是该座位类型的购票人数
    JSONArray seatTypeCountArray =
        seatTypeCountMap.entrySet().stream()
            .map(
                entry -> {
                  JSONObject jsonObject = new JSONObject();
                  jsonObject.put("seatType", String.valueOf(entry.getKey()));
                  jsonObject.put("count", String.valueOf(entry.getValue()));
                  return jsonObject;
                })
            .collect(Collectors.toCollection(JSONArray::new));
    // 获取需要判断扣减的站点
    List<RouteDTO> takeoutRouteDTOList =
        trainStationService.listTakeoutTrainStationRoute(
            requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
    // 用户购买的出发站点和到达站点
    String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
    Long result =
        stringRedisTemplate.execute(
            actual,
            Lists.newArrayList(actualHashKey, luaScriptKey),
            JSON.toJSONString(seatTypeCountArray),
            JSON.toJSONString(takeoutRouteDTOList));
    return result != null && Objects.equals(result, 0L);
  }

  /**
   * 回滚列车余量令牌，一般为订单取消或长时间未支付触发
   *
   * @param requestParam 回滚列车余量令牌入参
   */
  public void rollbackInBucket(TicketOrderDetailRespDTO requestParam) {
    DefaultRedisScript<Long> actual =
        Singleton.get(
            LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH,
            () -> {
              DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
              redisScript.setScriptSource(
                  new ResourceScriptSource(
                      new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
              redisScript.setResultType(Long.class);
              return redisScript;
            });
    Assert.notNull(actual);
    List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();
    Map<Integer, Long> seatTypeCountMap =
        passengerDetails.stream()
            .collect(
                Collectors.groupingBy(
                    TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));
    JSONArray seatTypeCountArray =
        seatTypeCountMap.entrySet().stream()
            .map(
                entry -> {
                  JSONObject jsonObject = new JSONObject();
                  jsonObject.put("seatType", String.valueOf(entry.getKey()));
                  jsonObject.put("count", String.valueOf(entry.getValue()));
                  return jsonObject;
                })
            .collect(Collectors.toCollection(JSONArray::new));
    StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
    String actualHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
    String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
    List<RouteDTO> takeoutRouteDTOList =
        trainStationService.listTakeoutTrainStationRoute(
            String.valueOf(requestParam.getTrainId()),
            requestParam.getDeparture(),
            requestParam.getArrival());
    Long result =
        stringRedisTemplate.execute(
            actual,
            Lists.newArrayList(actualHashKey, luaScriptKey),
            JSON.toJSONString(seatTypeCountArray),
            JSON.toJSONString(takeoutRouteDTOList));
    if (result == null || !Objects.equals(result, 0L)) {
      log.error("回滚列车余票令牌失败，订单信息：{}", JSON.toJSONString(requestParam));
      throw new ServiceException("回滚列车余票令牌失败");
    }
  }

  public void putTokenInBucket() {}

  public void initializeTokens() {}
}
