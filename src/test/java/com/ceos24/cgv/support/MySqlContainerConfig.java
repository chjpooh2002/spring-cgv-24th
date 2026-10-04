package com.ceos24.cgv.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

// 잠금 대기·교착·간격 잠금은 H2의 MySQL 모드가 흉내 내지 못한다. 동시성 테스트만 이 설정을 가져와 실제 MySQL에서 돌린다.
// 컨테이너를 빈으로 두면 이 설정을 쓰는 테스트 클래스들이 같은 컨텍스트 캐시를 공유해 컨테이너가 하나만 뜬다.
// Docker가 없으면 건너뛰지 않고 실패한다. 동시성 검증이 조용히 빠지는 것보다 낫다.
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mySqlContainer() {
        // 운영 DB 버전(8.0)에 맞춘다. 잠금 대기 한도는 운영 설정의 sessionVariables와 같은 3초다.
        return new MySQLContainer("mysql:8.0")
                .withCommand("--innodb-lock-wait-timeout=3");
    }
}
