local current = redis.call('HGET', KEYS[1], 'current')
local login_time = tonumber(redis.call('HGET', KEYS[1], 'auth_time') or '0')
local state = redis.call('HGET', KEYS[1], 'state')
local resume = state == 'ACTIVE' and login_time == tonumber(ARGV[3])
if not resume and (ARGV[5] ~= '1' or (current and tonumber(ARGV[3]) <= login_time)) then return 0 end
if resume and current ~= ARGV[1] then
    local now = redis.call('TIME')
    local milliseconds = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
    redis.call('HSET', KEYS[1], 'previous', current, 'previous_until', milliseconds + tonumber(ARGV[4]))
elseif not resume then
    redis.call('HDEL', KEYS[1], 'previous', 'previous_until')
end
redis.call('HSET', KEYS[1], 'current', ARGV[1], 'auth_time', ARGV[3], 'state', 'ACTIVE')
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
