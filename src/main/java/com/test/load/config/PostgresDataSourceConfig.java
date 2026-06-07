package com.test.load.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableJpaRepositories(
    basePackages = "com.test.load.repository.pg",
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
public class PostgresDataSourceConfig {

    @Value("${spring.jpa.hibernate.ddl-auto:update}")
    private String ddlAuto;

    @Primary
    @Bean
    public JpaVendorAdapter jpaVendorAdapter() {
        return new HibernateJpaVendorAdapter();
    }

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties pgDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource pgDataSource() {
        return pgDataSourceProperties()
            .initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    @Primary
    @Bean(name = "entityManagerFactory")
    public LocalContainerEntityManagerFactoryBean pgEntityManagerFactory() {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(pgDataSource());
        em.setPackagesToScan("com.test.load.entity.pg");
        em.setJpaVendorAdapter(jpaVendorAdapter());
        em.setPersistenceUnitName("pg");

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", ddlAuto);
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        em.setJpaPropertyMap(props);

        return em;
    }

    @Primary
    @Bean(name = "transactionManager")
    public PlatformTransactionManager pgTransactionManager(
            @Qualifier("entityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
