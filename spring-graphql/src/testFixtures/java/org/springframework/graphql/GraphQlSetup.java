/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.graphql;

import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.TypeResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer;
import org.springframework.graphql.execution.*;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlSetup;
import org.springframework.graphql.server.webflux.GraphQlHttpHandler;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Workflow for GraphQL tests setup that starts with {@link GraphQlSource.Builder}
 * related input, and then optionally moving on to the creation of a
 * {@link ExecutionGraphQlService} or a {@link WebGraphQlHandler}.
 *
 * @author Rossen Stoyanchev
 */
@SuppressWarnings("unused")
public class GraphQlSetup implements GraphQlServiceSetup {

	private final GraphQlSource.SchemaResourceBuilder graphQlSourceBuilder;

	private final List<DataLoaderRegistrar> dataLoaderRegistrars = new ArrayList<>();

	private final List<WebGraphQlInterceptor> interceptors = new ArrayList<>();

	private final List<ThreadLocalAccessor> accessors = new ArrayList<>();


	private GraphQlSetup(Resource... schemaResources) {
		this.graphQlSourceBuilder = GraphQlSource.schemaResourceBuilder().schemaResources(schemaResources);
	}


	public GraphQlSetup queryFetcher(String field, DataFetcher<?> dataFetcher) {
		return dataFetcher("Query", field, dataFetcher);
	}

	public GraphQlSetup mutationFetcher(String field, DataFetcher<?> dataFetcher) {
		return dataFetcher("Mutation", field, dataFetcher);
	}

	public GraphQlSetup subscriptionFetcher(String field, DataFetcher<?> dataFetcher) {
		return dataFetcher("Subscription", field, dataFetcher);
	}

	public GraphQlSetup dataFetcher(String type, String field, DataFetcher<?> dataFetcher) {
		return runtimeWiring(wiringBuilder ->
				wiringBuilder.type(type, typeBuilder -> typeBuilder.dataFetcher(field, dataFetcher)));
	}

	public GraphQlSetup runtimeWiring(RuntimeWiringConfigurer configurer) {
		this.graphQlSourceBuilder.configureRuntimeWiring(configurer);
		return this;
	}

	public GraphQlSetup runtimeWiringForAnnotatedControllers(ApplicationContext context) {
		AnnotatedControllerConfigurer configurer = new AnnotatedControllerConfigurer();
		configurer.setApplicationContext(context);
		configurer.afterPropertiesSet();
		return runtimeWiring(configurer);
	}

	public GraphQlSetup exceptionResolver(DataFetcherExceptionResolver... resolvers) {
		this.graphQlSourceBuilder.exceptionResolvers(Arrays.asList(resolvers));
		return this;
	}

	public GraphQlSetup subscriptionExceptionResolvers(SubscriptionExceptionResolver... resolvers) {
		this.graphQlSourceBuilder.subscriptionExceptionResolvers(Arrays.asList(resolvers));
		return this;
	}

	public GraphQlSetup typeResolver(TypeResolver typeResolver) {
		this.graphQlSourceBuilder.defaultTypeResolver(typeResolver);
		return this;
	}

	public GraphQlSetup typeVisitor(GraphQLTypeVisitor... visitors) {
		this.graphQlSourceBuilder.typeVisitors(Arrays.asList(visitors));
		return this;
	}

	public GraphQL toGraphQl() {
		return this.graphQlSourceBuilder.build().graphQl();
	}

	public GraphQlSource toGraphQlSource() {
		return this.graphQlSourceBuilder.build();
	}


	// GraphQlServiceSetup...

	@Override
	public GraphQlServiceSetup dataLoaders(DataLoaderRegistrar... registrars) {
		this.dataLoaderRegistrars.addAll(Arrays.asList(registrars));
		return this;
	}

	public ExecutionGraphQlService toGraphQlService() {
		GraphQlSource source = graphQlSourceBuilder.build();
		DefaultExecutionGraphQlService service = new DefaultExecutionGraphQlService(source);
		this.dataLoaderRegistrars.forEach(service::addDataLoaderRegistrar);
		return service;
	}


	// WebGraphQlSetup...

	public WebGraphQlSetup interceptor(WebGraphQlInterceptor... interceptors) {
		this.interceptors.addAll(Arrays.asList(interceptors));
		return this;
	}

	@Override
	public WebGraphQlSetup threadLocalAccessor(@Nullable ThreadLocalAccessor accessor) {
		if (accessor != null) {
			this.accessors.add(accessor);
		}
		return this;
	}

	public WebGraphQlHandler toWebGraphQlHandler() {
		ExecutionGraphQlService service = toGraphQlService();
		return WebGraphQlHandler.builder(service)
				.interceptors(this.interceptors)
				.threadLocalAccessors(this.accessors)
				.build();
	}

	@Override
	public org.springframework.graphql.server.webmvc.GraphQlHttpHandler toHttpHandler() {
		return new org.springframework.graphql.server.webmvc.GraphQlHttpHandler(toWebGraphQlHandler());
	}

	@Override
	public GraphQlHttpHandler toHttpHandlerWebFlux() {
		return new GraphQlHttpHandler(toWebGraphQlHandler());
	}


	// Factory methods

	public static GraphQlSetup schemaContent(String schema) {
		return new GraphQlSetup(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
	}

	public static GraphQlSetup schemaResource(Resource... resources) {
		return new GraphQlSetup(resources);
	}

}
