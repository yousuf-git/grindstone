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
@ConditionalOnProperty(name = "app.datasource.mysql.enabled", havingValue = "true")
@EnableJpaRepositories(
    basePackages = "com.test.load.repository.mssql",
    entityManagerFactoryRef = "mssqlEntityManagerFactory",
    transactionManagerRef = "mssqlTransactionManager"
)
public class MssqlDataSourceConfig {

    @Value("${spring.jpa.hibernate.ddl-auto:update}")
    private String ddlAuto;

    @Bean
    @ConfigurationProperties("app.datasource.mysql")
    public DataSourceProperties mssqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.mysql.hikari")
    public HikariDataSource mssqlDataSource() {
        return mssqlDataSourceProperties()
            .initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean mssqlEntityManagerFactory() {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(mssqlDataSource());
        em.setPackagesToScan("com.test.load.entity.mssql");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setPersistenceUnitName("mysql");

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", ddlAuto);
        props.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        props.put("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        em.setJpaPropertyMap(props);

        return em;
    }

    @Bean
    public PlatformTransactionManager mssqlTransactionManager(
            @Qualifier("mssqlEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
