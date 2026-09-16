package top.qianlink.portfolio;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// 每日备份用 @Scheduled，不开这个注解定时任务不会跑
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("top.qianlink.portfolio.mapper")
public class PortfolioApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
