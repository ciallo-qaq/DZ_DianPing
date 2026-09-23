-- 令牌桶限流脚本（Redis + Lua：查桶、补令牌、扣令牌、写回全部在一次原子调用内完成）
-- KEYS[1] : 桶 key
-- ARGV[1] : capacity         桶容量，即允许的最大突发令牌数
-- ARGV[2] : refillTokens     一个补充周期内补充的令牌数
-- ARGV[3] : refillPeriodMs   补充周期（毫秒）
-- ARGV[4] : nowMs            当前时间（毫秒）。由应用传入，不依赖脚本内 TIME，避免版本差异并便于压测
-- ARGV[5] : requested        本次请求消耗的令牌数
-- ARGV[6] : ttlMs            桶 key 过期时间（毫秒），用于自动回收，避免 key 无界增长
-- 返回     : {allowed(1/0), remaining(整数), retryAfterMs(被限流时建议重试的毫秒数)}
--
-- 令牌用浮点数存储，补令牌按"流逝时间 x 速率"连续计算，避免整秒取整造成令牌损耗；
-- 比较时留极小误差，防止 0.9999999 这种浮点误差把本该放行的请求判成限流。

local capacity = tonumber(ARGV[1])
local refillTokens = tonumber(ARGV[2])
local refillPeriodMs = tonumber(ARGV[3])
local nowMs = tonumber(ARGV[4])
local requested = tonumber(ARGV[5])
local ttlMs = tonumber(ARGV[6])

-- 参数异常时放行：具体校验与告警在应用侧(RateLimitSpec#isValid)完成，避免脚本本身成为故障点
if capacity == nil or refillTokens == nil or refillPeriodMs == nil
        or nowMs == nil or requested == nil or ttlMs == nil
        or capacity <= 0 or refillTokens <= 0 or refillPeriodMs <= 0 or requested <= 0 then
    return {1, 0, 0}
end

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])

-- 首次访问视为满桶
if tokens == nil then
    tokens = capacity
end
-- 时钟回拨保护：时间戳倒退时以当前时间为基准，避免无限补令牌
if ts == nil or ts > nowMs then
    ts = nowMs
end

local elapsed = nowMs - ts
if elapsed > 0 then
    tokens = math.min(capacity, tokens + elapsed * refillTokens / refillPeriodMs)
    ts = nowMs
end

local allowed = 0
local retryAfterMs = 0

if tokens + 0.000001 >= requested then
    allowed = 1
    tokens = tokens - requested
else
    -- 被限流时不扣令牌，只把补充后的令牌数与时间戳写回
    retryAfterMs = math.ceil((requested - tokens) * refillPeriodMs / refillTokens)
end

if tokens < 0 then
    tokens = 0
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', KEYS[1], ttlMs)

return {allowed, math.floor(tokens), retryAfterMs}
