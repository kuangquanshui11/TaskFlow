if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return 0 end
local current = redis.call('HGET', KEYS[1], 'current')
if current == ARGV[2] then
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
    return 1
end
if current ~= ARGV[1] then return 0 end
local now = redis.call('TIME')
local milliseconds = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
redis.call('HSET', KEYS[1], 'previous', current, 'previous_until', milliseconds + tonumber(ARGV[4]), 'current', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
