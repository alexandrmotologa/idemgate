-- Redis Atomic Token Bucket Rate Limiter
-- KEYS[1]: rate limit key, e.g. "ratelimit:{tenantId}"
-- ARGV[1]: bucket capacity (number)
-- ARGV[2]: refill rate per second (number)
-- ARGV[3]: requested tokens (number, typically 1)
-- ARGV[4]: current timestamp in milliseconds (number)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local now_ms = tonumber(ARGV[4])

-- Retrieve current bucket state
local data = redis.call('HMGET', key, 'tokens', 'last_updated_ms')
local tokens = tonumber(data[1])
local last_updated_ms = tonumber(data[2])

if tokens == nil or last_updated_ms == nil then
    tokens = capacity
    last_updated_ms = now_ms
else
    -- Compute replenished tokens based on elapsed time
    local elapsed_ms = math.max(0, now_ms - last_updated_ms)
    local replenished = (elapsed_ms / 1000.0) * refill_rate
    tokens = math.min(capacity, tokens + replenished)
    last_updated_ms = now_ms
end

local allowed = 0
local remaining = tokens
local reset_ms = 0

if tokens >= requested then
    allowed = 1
    remaining = tokens - requested
    tokens = remaining
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated_ms', last_updated_ms)
    -- Expire bucket if inactive for 1 hour
    redis.call('EXPIRE', key, 3600)
else
    allowed = 0
    remaining = tokens
    -- Time until at least 'requested' tokens are available
    local missing = requested - tokens
    reset_ms = math.ceil((missing / refill_rate) * 1000)
end

-- Return: allowed (1 or 0), remaining tokens (integer), reset time in milliseconds
return { allowed, math.floor(remaining), reset_ms }
