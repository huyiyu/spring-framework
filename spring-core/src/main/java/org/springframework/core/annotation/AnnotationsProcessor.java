/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;

import org.springframework.lang.Nullable;

/**
 * Callback interface used to process annotations.
 *
 * @param <C> the context type
 * @param <R> the result type
 * @author Phillip Webb
 * @since 5.2
 * @see AnnotationsScanner
 * @see TypeMappedAnnotations
 */
@FunctionalInterface
interface AnnotationsProcessor<C, R> {

	/**
	 *在即将处理聚合时调用. 此方法可能会返回 {@code non-null} 结果以短路任何进一步的处理。
	 * @param context 与处理器相关的上下文信息
	 * @param aggregateIndex 即将被处理的聚合索引
	 * @return a {@code non-null} result if no further processing is required
	 */
	@Nullable
	default R doWithAggregate(C context, int aggregateIndex) {
		return null;
	}

	/**
	 * Called when an array of annotations can be processed. This method may
	 * return a {@code non-null} result to short-circuit any further processing.
	 * @param context the context information relevant to the processor
	 * @param aggregateIndex the aggregate index of the provided annotations
	 * @param source the original source of the annotations, if known
	 * @param annotations the annotations to process (this array may contain
	 * {@code null} elements)
	 * @return a {@code non-null} result if no further processing is required
	 */
	@Nullable
	R doWithAnnotations(C context, int aggregateIndex, @Nullable Object source, Annotation[] annotations);

	/**
	 * Get the final result to be returned. By default this method returns
	 * the last process result.
	 * @param result the last early exit result, or {@code null} if none
	 * @return the final result to be returned to the caller
	 */
	@Nullable
	default R finish(@Nullable R result) {
		return result;
	}

}
