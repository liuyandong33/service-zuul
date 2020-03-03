package build.dream.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Created by liuyandong on 2018/4/15.
 */
@ServletComponentScan
@SpringBootApplication
@EnableDiscoveryClient
@EnableZuulProxy
@EnableKafka
@MapperScan(basePackages = {"build.dream.common.mappers", "build.dream.gateway.mappers"})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}