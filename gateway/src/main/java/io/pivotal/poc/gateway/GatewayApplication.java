/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pivotal.poc.gateway;

import java.io.File;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.local.LocalDeployerProperties;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.util.SimpleIdGenerator;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import io.pivotal.poc.claimcheck.FileClaimCheckStore;
import io.pivotal.poc.claimcheck.LocalFileClaimCheckStore;
import io.pivotal.poc.gateway.filters.pre.FileClaimCheckFilter;
import io.pivotal.poc.gateway.filters.pre.XPathHeaderEnrichingFilter;

@EnableZuulProxy
@EnableBinding
@EnableEurekaClient
@EnableConfigurationProperties(LocalDeployerProperties.class)
@SpringBootApplication
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

	@Bean
	public MultipartResolver multipartResolver() {
		return new StandardServletMultipartResolver();
	}

	@Bean
	public XPathHeaderEnrichingFilter xpathHeaderEnrichingFilter() {
		return new XPathHeaderEnrichingFilter("customer", "//@customer");
	}

	@Bean
	public FileClaimCheckStore fileClaimCheckStore() {
		return new LocalFileClaimCheckStore(new File("/tmp/uploads"), new SimpleIdGenerator());
	}

	@Bean
	public FileClaimCheckFilter fileClaimCheckFilter(FileClaimCheckStore fileClaimCheckStore) {
		return new FileClaimCheckFilter(fileClaimCheckStore);
	}

//	@Bean
//	public RequestMonitoringFilter requestMonitoringFilter(FunctionExecutorPoolManager poolManager) {
//		return new RequestMonitoringFilter(poolManager);
//	}

//	@Bean
//	public FunctionExecutorPoolManager functionExecutorPoolManager(AppDeployer deployer) {
//		return new FunctionExecutorPoolManager(deployer, mavenResourceLoader());
//	}

//	@Bean
//	public MavenResourceLoader mavenResourceLoader() {
//		return new MavenResourceLoader(new MavenProperties());
//	}

//	@Bean
//	public AppDeployer appDeployer(LocalDeployerProperties properties) {
//		return new LocalAppDeployer(properties);
//	}
}
