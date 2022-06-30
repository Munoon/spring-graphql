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

package org.springframework.graphql.execution;

import graphql.ExecutionInput;
import graphql.execution.DataFetcherResult;
import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import org.reactivestreams.Publisher;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.function.Function;

/**
 * Wrap a {@link DataFetcher} to enable the following:
 * <ul>
 * <li>Support {@link Mono} return value.
 * <li>Support {@link Flux} return value as a shortcut to {@link Flux#collectList()}.
 * <li>Re-establish Reactor Context passed via {@link ExecutionInput}.
 * <li>Re-establish ThreadLocal context passed via {@link ExecutionInput}.
 * </ul>
 *
 * @author Rossen Stoyanchev
 */
final class ContextDataFetcherDecorator implements DataFetcher<Object> {

	private final DataFetcher<?> delegate;

	private final boolean subscription;

	private final SubscriptionExceptionResolver subscriptionExceptionResolver;

	private ContextDataFetcherDecorator(
			DataFetcher<?> delegate, boolean subscription,
			SubscriptionExceptionResolver subscriptionExceptionResolver) {
		Assert.notNull(delegate, "'delegate' DataFetcher is required");
		Assert.notNull(subscriptionExceptionResolver, "'subscriptionExceptionResolver' is required");
		this.delegate = delegate;
		this.subscription = subscription;
		this.subscriptionExceptionResolver = subscriptionExceptionResolver;
	}

	@Override
	public Object get(DataFetchingEnvironment environment) throws Exception {

		Object value = ReactorContextManager.invokeCallable(() ->
				this.delegate.get(environment), environment.getGraphQlContext());

		ContextView contextView = ReactorContextManager.getReactorContext(environment.getGraphQlContext());

		if (this.subscription) {
			Publisher<?> publisher = interceptSubscriptionPublisherWithExceptionHandler((Publisher<?>) value);
			return (!contextView.isEmpty() ? Flux.from(publisher).contextWrite(contextView) : publisher);
		}

		if (value instanceof Flux) {
			value = ((Flux<?>) value).collectList();
		}

		if (value instanceof Mono) {
			Mono<?> valueMono = (Mono<?>) value;
			if (!contextView.isEmpty()) {
				valueMono = valueMono.contextWrite(contextView);
			}
			value = valueMono.toFuture();
		}

		return value;
	}

	@SuppressWarnings("unchecked")
	private Publisher<?> interceptSubscriptionPublisherWithExceptionHandler(Publisher<?> publisher) {
		Function<? super Throwable, Mono<DataFetcherResult<?>>> onErrorResumeFunction = e ->
				subscriptionExceptionResolver.resolveException(e)
						.map(errors -> DataFetcherResult.newResult().errors(errors).build());

		if (publisher instanceof Flux) {
			return ((Flux<Object>) publisher).onErrorResume(onErrorResumeFunction);
		}

		if (publisher instanceof Mono) {
			return ((Mono<Object>) publisher).onErrorResume(onErrorResumeFunction);
		}

		throw new IllegalArgumentException("Unknown publisher type: '" + publisher.getClass().getName() +"'. " +
				"Expected reactor.core.publisher.Mono or reactor.core.publisher.Flux");
	}

	/**
	 * {@link GraphQLTypeVisitor} that wraps non-GraphQL data fetchers and adapts them if
	 * they return {@link Flux} or {@link Mono}.
	 */
	static GraphQLTypeVisitor TYPE_VISITOR = new GraphQLTypeVisitorStub() {

		@Override
		public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition fieldDefinition,
				TraverserContext<GraphQLSchemaElement> context) {

			GraphQLCodeRegistry.Builder codeRegistry = context.getVarFromParents(GraphQLCodeRegistry.Builder.class);
			GraphQLFieldsContainer parent = (GraphQLFieldsContainer) context.getParentNode();
			DataFetcher<?> dataFetcher = codeRegistry.getDataFetcher(parent, fieldDefinition);

			if (dataFetcher.getClass().getPackage().getName().startsWith("graphql.")) {
				return TraversalControl.CONTINUE;
			}

			SubscriptionExceptionResolver subscriptionExceptionResolver =
					context.getVarFromParents(SubscriptionExceptionResolver.class);

			boolean handlesSubscription = parent.getName().equals("Subscription");
			dataFetcher = new ContextDataFetcherDecorator(dataFetcher, handlesSubscription, subscriptionExceptionResolver);
			codeRegistry.dataFetcher(parent, fieldDefinition, dataFetcher);
			return TraversalControl.CONTINUE;
		}
	};

}
