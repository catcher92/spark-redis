package com.redislabs.provider.redis

import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig, JedisSentinelPool}
import redis.clients.jedis.exceptions.JedisConnectionException
import java.util.concurrent.ConcurrentHashMap

import redis.clients.jedis.util.Pool

import scala.collection.JavaConversions._


object ConnectionPool {
  @transient private lazy val pools: ConcurrentHashMap[RedisEndpoint, Pool[Jedis]] =
    new ConcurrentHashMap[RedisEndpoint, Pool[Jedis]]()

  def connect(re: RedisEndpoint): Jedis = {
    val pool = pools.getOrElseUpdate(re,
      {
        val poolConfig: JedisPoolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(250)
        poolConfig.setMaxIdle(32)
        poolConfig.setTestOnBorrow(false)
        poolConfig.setTestOnReturn(false)
        poolConfig.setTestWhileIdle(false)
        poolConfig.setMinEvictableIdleTimeMillis(60000)
        poolConfig.setTimeBetweenEvictionRunsMillis(30000)
        poolConfig.setNumTestsPerEvictionRun(-1)

        if (null == re.master || re.master.trim.isEmpty) {
          new JedisPool(poolConfig, re.host, re.port, re.timeout, re.auth, re.dbNum, re.ssl)
        } else {
          val sentinels = re.host.split(",").map(x => x + ":" + re.port).toSet
          new JedisSentinelPool(re.master.trim, sentinels, poolConfig, re.auth)
        }
      }
    )
    var sleepTime: Int = 4
    var conn: Jedis = null
    while (conn == null) {
      try {
        conn = pool.getResource
      }
      catch {
        case e: JedisConnectionException if e.getCause.toString.
          contains("ERR max number of clients reached") => {
          if (sleepTime < 500) sleepTime *= 2
          Thread.sleep(sleepTime)
        }
        case e: Exception => throw e
      }
    }
    conn
  }
}

