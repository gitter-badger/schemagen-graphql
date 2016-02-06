package com.bretpatterson.schemagen.graphql.impl;

import com.bretpatterson.schemagen.graphql.IGraphQLObjectMapper;
import com.bretpatterson.schemagen.graphql.IQueryFactory;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLParam;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLQuery;
import com.bretpatterson.schemagen.graphql.datafetchers.IMethodDataFetcher;
import com.bretpatterson.schemagen.graphql.utils.AnnotationUtils;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLOutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Default implementation of the query factory. Converts a Method signature on an object
 * into a:
 * <OL>
 *     <LI> List of GraphQL field definitions</LI>
 *     <LI> GraphQLOutput type for the return type of the method.</LI>
 *     <LI> Configures the Datafetcher based on the Object Instance, Field Name, Method Signature, and Type Factory</LI>
 * </OL>
 */
public class DefaultQueryFactory implements IQueryFactory {

	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultQueryFactory.class);

	protected <T> T findAnnotation(Annotation[] annotations, Class<T> type) {
		for (Annotation annotation : annotations) {
			if (annotation.annotationType() == type) {
				return (T) annotation;
			}
		}
		return null;
	}

	/**
	 * Generates field definitions, with arguments, from all {@link GraphQLQuery} annotated methods
	 * on the source object.
	 * @param graphQLObjectMapper the current IGraphQLObjectMapper
	 * @param targetObject the target object the DataFetcher will invoke the method on.
	 * @return
	 */
	public List<GraphQLFieldDefinition> newMethodQueriesForObject(IGraphQLObjectMapper graphQLObjectMapper, Object targetObject) {

		ImmutableList.Builder<GraphQLFieldDefinition> queries = ImmutableList.builder();

		for (Method method : targetObject.getClass().getDeclaredMethods()) {
			try {
				GraphQLQuery graphQLQueryAnnotation = method.getAnnotation(GraphQLQuery.class);
				if (graphQLQueryAnnotation != null) {
					String methodName = graphQLQueryAnnotation.name();
					if (AnnotationUtils.isNullValue(methodName)) {
						methodName = method.getName();
					}

					IMethodDataFetcher dataFetcher = graphQLQueryAnnotation.dataFetcher().newInstance();
					dataFetcher.setTypeFactory(graphQLObjectMapper.getTypeFactory());
					dataFetcher.setTargetObject(targetObject);
					dataFetcher.setMethod(method);
					dataFetcher.setFieldName(methodName);

					GraphQLFieldDefinition.Builder newField = GraphQLFieldDefinition.newFieldDefinition()
							.name(graphQLQueryAnnotation.name());
					newField.type(getReturnType(graphQLObjectMapper, method));
					newField.dataFetcher(dataFetcher);
					List<GraphQLArgument> arguments = getMethodArguments(graphQLObjectMapper, Optional.of(dataFetcher), method);

					newField.argument(arguments);

					queries.add(newField.build());
				}
			} catch (Exception ex) {
				Throwables.propagate(ex);
			}
		}

		return queries.build();
	}

	protected GraphQLOutputType getReturnType(IGraphQLObjectMapper graphQLObjectMapper, Method method) {
		return graphQLObjectMapper.getOutputType(method.getGenericReturnType());
	}

	/**
	 * Generates a list of GraphQLArgument objects for the specified method while passing parameter information to the datafetcher for use
	 * during fetching.
	 * 
	 * @param graphQLObjectMapper the grpahQLObject mapper
	 * @param dataFetcher The Data fetcher that will be used for this method.
	 * @param method
	 * @return
	 * @throws Exception
	 */
	protected List<GraphQLArgument> getMethodArguments(IGraphQLObjectMapper graphQLObjectMapper, Optional<IMethodDataFetcher> dataFetcher, Method method)
			throws Exception {

		Type returnType = method.getGenericReturnType();

		ImmutableList.Builder<GraphQLArgument> argumentBuilder = ImmutableList.builder();
		GraphQLArgument.Builder paramBuilder;
		Annotation[][] parameterAnnotations = method.getParameterAnnotations();
		int index = 0;
		for (Type paramType : method.getGenericParameterTypes()) {
			GraphQLParam graphQLParam = findAnnotation(parameterAnnotations[index], GraphQLParam.class);

			if (graphQLParam == null) {
				LOGGER.error("Missing @GraphParam annotation on parameter index {} for method {}", index, method.getName());
				continue;
			}

			paramBuilder = GraphQLArgument.newArgument().name(graphQLParam.name()).type(graphQLObjectMapper.getInputType(paramType));
			if (dataFetcher.isPresent()) {
				dataFetcher.get().addParam(graphQLParam.name(),
						paramType,
						Optional.<Object> fromNullable(AnnotationUtils.isNullValue(graphQLParam.defaultValue()) ? null : graphQLParam.defaultValue()));
			}

			argumentBuilder.add(paramBuilder.build());
			index++;
		}

		return argumentBuilder.build();
	}

}
