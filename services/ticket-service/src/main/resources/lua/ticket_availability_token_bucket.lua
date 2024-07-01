-- KEYS[2] 是用户购买的出发站点和到达站点，比如北京南_南京南
local inputString = KEYS[2]
local actualKey = inputString
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end

-- ARGV[1] 是需要扣减的座位类型以及对应数量
local jsonArrayStr = ARGV[1]
-- 序列化成对象
local jsonArray = cjson.decode(jsonArrayStr)

for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    local actualInnerHashKey = actualKey .. "_" .. seatType
    local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))
    -- 超过则继续，不超过返回 1，返回非 0 则代表失败
    if ticketSeatAvailabilityTokenValue < count then
        return 1
    end
end

--令牌容器中对应的出发站点和到达站点对应的座位类型余票充足，开始进行扣减
local alongJsonArrayStr = ARGV[2]
local alongJsonArray = cjson.decode(alongJsonArrayStr)

for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do
        local startStation = tostring(alongJsonObj.startStation)
        local endStation = tostring(alongJsonObj.endStation)
        --开始扣减出发站点和到达站点相关的车站令牌余量
        local actualInnerHashKey = startStation .. "_" .. endStation .. "_" .. seatType
        redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), -count)
    end
end

return 0
