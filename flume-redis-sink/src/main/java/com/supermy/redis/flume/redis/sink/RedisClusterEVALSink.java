/**
 *  Copyright 2014 TangoMe Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.supermy.redis.flume.redis.sink;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.gson.Gson;
import com.supermy.redis.flume.redis.core.GroovyShellJsonExample;
import com.supermy.redis.flume.redis.core.redis.JedisPoolFactory;
import com.supermy.redis.flume.redis.core.redis.JedisPoolFactoryImpl;
import com.supermy.redis.flume.redis.sink.serializer.RedisSerializerException;
import com.supermy.redis.flume.redis.sink.serializer.Serializer;
import com.yam.redis.JedisClusterPipeline;
import groovy.lang.Binding;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.*;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

/*
 * Simple sink which read events from a channel and lpush them to redis
 */
public class RedisClusterEVALSink extends AbstractSink implements Configurable {

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterEVALSink.class);

    /**
     * Configuration attributes
     */
    private String host = null;
    private Integer port = null;
    private Integer timeout = null;
    private String password = null;
    private Integer database = null;
    private byte[] redisKey = null;
    private Integer batchSize = null;
    private Serializer serializer = null;

//    private final JedisPoolFactory jedisPoolFactory;
//    private JedisPool jedisPool = null;

    private Gson gson = null;

    private String ports = null;
    private JedisCluster cluster = null;

    private static final String SEARCH_REPLACE_KEY = "searchReplaceKey";
    private static final String SEARCH_REPLACE_DSL = "searchReplaceDsl";
    private Charset charset = Charsets.UTF_8;

    private  String searchReplaceKey;
    private  String searchReplaceDsl;

    public RedisClusterEVALSink() {
//        jedisPoolFactory = new JedisPoolFactoryImpl();
    }

//    @VisibleForTesting
//    public RedisClusterEVALSink(JedisPoolFactory _jedisPoolFactory) {
//        if (_jedisPoolFactory == null) {
//            throw new IllegalArgumentException("JedisPoolFactory cannot be null");
//        }
//
//        this.jedisPoolFactory = _jedisPoolFactory;
//    }

    @Override
    public synchronized void start() {
        logger.info("Starting");
//        if (jedisPool != null) {
//            jedisPool.destroy();
//        }

//        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
//        jedisPool = jedisPoolFactory.create(jedisPoolConfig, host, port, timeout, password, database);

        String[] hosts = host.split(";");
        String[] portlist = ports.split(";");
        Set<HostAndPort> jedisClusterNodes = new HashSet<HostAndPort>();

        if (portlist.length>=2){ //支持本机及群多端口
            for (int i = 0; i < portlist.length; i++) {
                jedisClusterNodes.add(new HostAndPort(host,new Integer(portlist[i])));
            }
        }else {
            for (int i = 0; i < hosts.length; i++) {
                jedisClusterNodes.add(new HostAndPort(hosts[i],port));
            }
        }
        // 构造池
        cluster= new JedisCluster(jedisClusterNodes);
        logger.debug(jedisClusterNodes.toString());

        cluster.set("bar","foo");


        super.start();
    }

    @Override
    public synchronized void stop() {
        logger.info("Stoping");

//        if (jedisPool != null) {
//            jedisPool.destroy();
//        }

        //关闭集群链接
        if (cluster != null) {
            try {
                cluster.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        super.stop();
    }

    @Override
    public Status process() throws EventDeliveryException {
        Status status = Status.READY;

        if (cluster == null) {
            throw new EventDeliveryException("Redis cluster connection not established. Please verify your configuration");
        }

        //List<byte[]> batchEvents = new ArrayList<byte[]>(batchSize);
        Set<byte[]> batchEvents = new HashSet<byte[]>(batchSize); //去掉重复数据


        Channel channel = getChannel();
        Transaction txn = channel.getTransaction();
//        Jedis jedis = jedisPool.getResource();


        JedisClusterPipeline jcp=null;

        try {
            txn.begin();



//            System.out.println("--------------------------------111");

            for (int i = 0; i < batchSize && status != Status.BACKOFF; i++) {
                Event event = channel.take();
                if (event == null) {
                    status = Status.BACKOFF;
                } else {
                    try {
                        batchEvents.add(serializer.serialize(event));
                    } catch (RedisSerializerException e) {
                        logger.error("Could not serialize event " + event, e);
                    }
                }
            }




            /**
             * Only send events if we got any
             */
            if (batchEvents.size() > 0) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Sending " + batchEvents.size() + " events");
                }


                jcp = JedisClusterPipeline.pipelined(cluster);
                jcp.refreshCluster();

                //redis  集群不支持 redis eval 用 groovy 脚本进行替代处理

                //进行数据的批量提交
//               Pipeline p = jedis.pipelined();

//                for (byte[] redisEvent : batchEvents) {
//
//                    String json = new String(redisEvent);
//
//                    Map m=gson.fromJson(json, HashMap.class);
//
//
//                    List<String> keys= (List<String>) m.get("keys");
//                    List<String> args= (List<String>) m.get("args");
//                    String scriptlua = m.get("script").toString();
//
//                    jcp.eval(scriptlua,keys,args);
//
//                }



                //输入参数
                Binding binding = new Binding();
                binding.setVariable("clusterNodes", cluster.getClusterNodes());
                binding.setVariable("batchEvents", batchEvents);
//                binding.setVariable("args", headers);
                //查找匹配数据；
                File f =new File(searchReplaceDsl);
                //在dsl 实现 jcp 操作 redis 集群的逻辑
                Map result =(Map) GroovyShellJsonExample.getShell(searchReplaceKey+f.lastModified(), f, binding);

                //替换匹配数据；
               // logger.debug(result.toString());
//                logger.debug(result.get("head").toString());

                //event.setBody(result.get("body").toString().getBytes(charset));
                //event.setHeaders((Map)result.get("head"));


                jcp.sync();


            }

            txn.commit();
        } catch (JedisConnectionException e) {
            txn.rollback();
            //jedisPool.returnBrokenResource(jedis);
            logger.error("Error while shipping events to redis", e);
        } catch (Throwable t) {
            txn.rollback();
            logger.error("Unexpected error", t);
        } finally {
            txn.close();
            //jedisPool.returnResource(jedis);

            if (jcp!=null){
                jcp.close();
            }

        }
//        System.out.println("--------------------------------444");

        return status;
    }

    @Override
    public void configure(Context context) {
        gson = new Gson();

        searchReplaceKey = context.getString(SEARCH_REPLACE_KEY);
        Preconditions.checkArgument(!StringUtils.isEmpty(searchReplaceKey),
                "Must supply a valid search pattern " + SEARCH_REPLACE_KEY +
                        " (may not be empty)");

        searchReplaceDsl = context.getString(SEARCH_REPLACE_DSL);
        Preconditions.checkNotNull(searchReplaceDsl,
                "Must supply a replacement string " + SEARCH_REPLACE_DSL +
                        " (empty is ok)");


        logger.info("Configuring");
        host = context.getString(RedisSinkConfigurationConstant.HOST);
        Preconditions.checkState(StringUtils.isNotBlank(host),
                "host cannot be empty, please specify in configuration file");
        ports = context.getString(RedisSinkConfigurationConstant.PORTS, "6379");
        port = context.getInteger(RedisSinkConfigurationConstant.PORT, Protocol.DEFAULT_PORT);
        timeout = context.getInteger(RedisSinkConfigurationConstant.TIMEOUT, Protocol.DEFAULT_TIMEOUT);
        database = context.getInteger(RedisSinkConfigurationConstant.DATABASE, Protocol.DEFAULT_DATABASE);
        password = context.getString(RedisSinkConfigurationConstant.PASSWORD);
        redisKey = context.getString(RedisSinkConfigurationConstant.KEY, RedisSinkConfigurationConstant.DEFAULT_KEY)
                .getBytes();
        batchSize = context.getInteger(RedisSinkConfigurationConstant.BATCH_SIZE,
                RedisSinkConfigurationConstant.DEFAULT_BATCH_SIZE);
        String serializerClassName = context.getString(RedisSinkConfigurationConstant.SERIALIZER,
                RedisSinkConfigurationConstant.DEFAULT_SERIALIZER_CLASS_NAME);

        Preconditions.checkState(batchSize > 0, RedisSinkConfigurationConstant.BATCH_SIZE
                + " parameter must be greater than 1");

        try {
            /**
             * Instantiate serializer
             */
            @SuppressWarnings("unchecked") Class<? extends Serializer> clazz = (Class<? extends Serializer>) Class
                    .forName(serializerClassName);
            serializer = clazz.newInstance();

            /**
             * Configure it
             */
            Context serializerContext = new Context();
            serializerContext.putAll(context.getSubProperties(RedisSinkConfigurationConstant.SERIALIZER_PREFIX));
            serializer.configure(serializerContext);

        } catch (ClassNotFoundException e) {
            logger.error("Could not instantiate event serializer", e);
            Throwables.propagate(e);
        } catch (InstantiationException e) {
            logger.error("Could not instantiate event serializer", e);
            Throwables.propagate(e);
        } catch (IllegalAccessException e) {
            logger.error("Could not instantiate event serializer", e);
            Throwables.propagate(e);
        }

    }
}
