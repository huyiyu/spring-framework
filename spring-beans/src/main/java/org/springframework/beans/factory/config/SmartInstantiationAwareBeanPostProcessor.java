/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.beans.factory.config;

import java.lang.reflect.Constructor;

import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

/**
 * {@link InstantiationAwareBeanPostProcessor} 接口的扩展，
 * 添加了用于预测已处理 bean 最终类型的回调。
 *
 * <p><b>NOTE:</b> 该接口是一个特殊用途的接口，
 * 主要供框架内部使用。通常，应用程序提供的后处理器应该简单地实现普通的 {@link BeanPostProcessor}
 * 接口或派生自 {@link InstantiationAwareBeanPostProcessorAdapter} 类。
 * 即使在点发布中，新方法也可能添加到此接口中。
 *
 * @author Juergen Hoeller
 * @since 2.0.3
 * @see InstantiationAwareBeanPostProcessorAdapter
 */
public interface SmartInstantiationAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessor {

	/**
	 * Predict the type of the bean to be eventually returned from this
	 * processor's {@link #postProcessBeforeInstantiation} callback.
	 * <p>The default implementation returns {@code null}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the type of the bean, or {@code null} if not predictable
	 * @throws org.springframework.beans.BeansException in case of errors
	 */
	@Nullable
	default Class<?> predictBeanType(Class<?> beanClass, String beanName) throws BeansException {
		return null;
	}

	/**
	 * Determine the candidate constructors to use for the given bean.
	 * <p>The default implementation returns {@code null}.
	 * @param beanClass the raw class of the bean (never {@code null})
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 */
	@Nullable
	default Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName)
			throws BeansException {

		return null;
	}

	/**
	 * 获取对指定 bean 的早期访问的引用，通常用于解析循环引用。
	 * <p>此回调使后处理器有机会尽早公开包装器 - 即,
	 * 在目标 bean 实例完全初始化之前。
	 * 暴露的对象应该等同于 {@link #postProcessBeforeInitialization}
	 * {@link #postProcessAfterInitialization} 否则会暴露的对象.
	 * 请注意，此方法返回的对象将用作 bean 引用，
	 * 除非后处理器从所述后处理回调返回不同的包装器. 换句话说:
	 * 这些后处理回调可能最终公开相同的引用，或者从这些后续回调
	 * 中返回原始 bean 实例
	 * （如果已经构建了受影响 bean 的包装器来调用此方法，它将作为最终 bean 引用公开默认）。
	 * <p>The default implementation returns the given {@code bean} as-is.
	 * @param bean the raw bean instance
	 * @param beanName the name of the bean
	 * @return the object to expose as bean reference
	 * (typically with the passed-in bean instance as default)
	 * @throws org.springframework.beans.BeansException in case of errors
	 */
	default Object getEarlyBeanReference(Object bean, String beanName) throws BeansException {
		return bean;
	}

}
