-- Release only the reservation owned by this order. A late duplicate must not release a new order.
local owner = redis.call('HGET', KEYS[3], ARGV[1])
if owner ~= ARGV[2] then return 0 end
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('HDEL', KEYS[4], ARGV[2])
return 1
