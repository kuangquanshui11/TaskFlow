if redis.call('EXISTS', KEYS[1]) == 1 then
    -- Keep a short-lived tombstone so a recently issued token cannot re-open a logged-out session.
    redis.call('HSET', KEYS[1], 'state', 'REVOKED', 'current', '')
    redis.call('HDEL', KEYS[1], 'previous', 'previous_until')
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return 1
