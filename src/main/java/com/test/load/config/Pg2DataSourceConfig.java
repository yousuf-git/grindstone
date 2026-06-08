package com.test.load.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "app.datasource.pg2.enabled", havingValue = "true")
@EnableJpaRepositories(
    basePackages = "com.test.load.repository.pg2",
    entityManagerFactoryRef = "pg2EntityManagerFactory",
    transactionManagerRef = "pg2TransactionManager"
)
public class Pg2DataSourceConfig {

    @Value("${spring.jpa.hibernate.ddl-auto:update}")
    private String ddlAuto;

    @Bean
    @ConfigurationProperties("app.datasource.pg2")
    public DataSourceProperties pg2DataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.pg2.hikari")
    public HikariDataSource pg2DataSource() {
        return pg2DataSourceProperties()
            .initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean pg2EntityManagerFactory() {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(pg2DataSource());
        em.setPackagesToScan("com.test.load.entity.pg2");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setPersistenceUnitName("pg2");

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", ddlAuto);
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        em.setJpaPropertyMap(props);

        return em;
    }

    @Bean
    public PlatformTransactionManager pg2TransactionManager(
            @Qualifier("pg2EntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
