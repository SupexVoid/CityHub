-- KEYS: integer stock, buyer set, user->order ownership, pending task hash, activity metadata
-- ARGV: user, orderId, nowMillis, serialized task
local stock = tonumber(redis.call('GET', KEYS[1]))
local beginTime = tonumber(redis.call('HGET', KEYS[5], 'begin'))
local endTime = tonumber(redis.call('HGET', KEYS[5], 'end'))
local now = tonumber(ARGV[3])
if not stock or not beginTime or not endTime then return -1 end
if now < beginTime or now >= endTime then return 3 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
if stock <= 0 then return 1 end
-- A stable order ID cannot overwrite another pending task.
if redis.call('HEXISTS', KEYS[4], ARGV[2]) == 1 then return 2 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[4], ARGV[2], ARGV[4])
return 0
