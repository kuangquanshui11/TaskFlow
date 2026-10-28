package com.taskflow.admin.job.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.taskflow.admin.job.mapper.JobMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
// 只扫描 SQL Mapper，不能把 Redis/数据访问接口也注册成 MyBatis 代理。
@MapperScan(basePackageClasses = JobMapper.class, annotationClass = Mapper.class)
public class MybatisPlusConfiguration {
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(
            @Value("${taskflow.persistence.database-type}") DbType databaseType,
            @Value("${taskflow.persistence.max-page-size}") long maxPageSize) {
        var pagination = new PaginationInnerInterceptor(databaseType);
        pagination.setMaxLimit(maxPageSize);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
