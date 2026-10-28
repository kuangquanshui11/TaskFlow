if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return 0 end
local valid = redis.call('HGET', KEYS[1], 'current') == ARGV[1]
if not valid then
    local now = redis.call('TIME')
    local milliseconds = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
    valid = redis.call('HGET', KEYS[1], 'previous') == ARGV[1]
        and milliseconds <= tonumber(redis.call('HGET', KEYS[1], 'previous_until') or '0')
end
if not valid then return 0 end
-- Comparison and sliding expiry are one atomic operation; a missing key is never recreated.
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
