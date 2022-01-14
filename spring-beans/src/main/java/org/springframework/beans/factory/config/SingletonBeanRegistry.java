/*
 * Copyright 2002-2015 the original author or authors.
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

import org.springframework.lang.Nullable;

/**
 * Interface that defines a registry for shared bean instances.
 * Can be implemented by {@link org.springframework.beans.factory.BeanFactory}
 * implementations in order to expose their singleton management facility
 * in a uniform manner.
 *
 * <p>The {@link ConfigurableBeanFactory} interface extends this interface.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see ConfigurableBeanFactory
 * @see org.springframework.beans.factory.support.DefaultSingletonBeanRegistry
 * @see org.springframework.beans.factory.support.AbstractBeanFactory
 */
public interface SingletonBeanRegistry {

	/**
	 * 使用当前名称为当前对象注册到单例池中
	 * <p>提供的对象应该是完全初始化过的; 注册表将不执行任何初始化回调(尤其不会调用
	 * InitializingBean  的{@code afterPropertiesSet} 方法).
	 * 提供的对象将不会收到任何销毁回调例如
	 * (like DisposableBean's {@code destroy} method) 等.
	 * <p>当运行于一个完整的 BeanFactory是: <b>如果你想要调用生命周期回调(如初始化或销毁),请注册一个bean definition而不是一个Bean
	 * <p>该方法通常在单例池配置时创建,也可以在运行阶段注册单例对象,因此 他的实现必须同步单例的访问;
	 * 他不得不这么做如果 BeanFactory 支持懒加载.
	 * @param beanName the name of the bean
	 * @param singletonObject the existing singleton object
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.DisposableBean#destroy
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry#registerBeanDefinition
	 */
	void registerSingleton(String beanName, Object singletonObject);

	/**
	 * 根据提供的名称检索源对象
	 * <p>只提供完整创建的对象 不提供未完全实例化bean definition
	 * <p>该方法主要目的是访问手动注册的单例
	 * (see {@link #registerSingleton}). 同时可以访问通过定义Bean definition 创建出来已经创建的对象
	 * <p><b>NOTE:</b> 该方法不知道factory Bean 的前缀或别名,获取对象之前你需要解析成规范名称
	 * @param beanName the name of the bean to look for
	 * @return the registered singleton object, or {@code null} if none found
	 * @see ConfigurableListableBeanFactory#getBeanDefinition
	 */
	@Nullable
	Object getSingleton(String beanName);

	/**
	 * 检查提供的名称是否存在单例池中
	 * <p>只提供完整创建的对象 不提供未完全实例化bean definition
	 * <p>该方法主要目的是访问手动注册的单例
	 * 	 * (see {@link #registerSingleton}). 同时可以访问通过定义Bean definition 创建出来已经创建的对象
	 * <p>为了根据提供的名称检查当前Bean工厂是否存在此 bean定义,
	 * 使用ListableBeanFactory 的 {@code containsBeanDefinition}. 获取两个
	 * {@code containsBeanDefinition} 存在bean定义和单例 {@code containsSingleton} 的答案
	 * whether a specific bean factory contains a local bean instance with the given name.
	 * <p>Use BeanFactory's {@code containsBean} for general checks whether the
	 * factory knows about a bean with a given name (whether manually registered singleton
	 * instance or created by bean definition), also checking ancestor factories.
	 * <p><b>NOTE:</b> 该方法不知道factory Bean 的前缀或别名,获取对象之前你需要解析成规范名称
	 * @param beanName the name of the bean to look for
	 * @return if this bean factory contains a singleton instance with the given name
	 * @see #registerSingleton
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 * @see org.springframework.beans.factory.BeanFactory#containsBean
	 */
	boolean containsSingleton(String beanName);

	/**
	 * 返回单例池对象的所有规范名称
	 * <p>Only checks already instantiated singletons; does not return names for singleton bean definitions which have not been instantiated yet.
	 * <p>该方法主要目的是访问手动注册的单例
	 * (see {@link #registerSingleton}). 同时可以访问通过定义Bean definition 创建出来已经创建的对象
	 * @return the list of names as a String array (never {@code null})
	 * @see #registerSingleton
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry#getBeanDefinitionNames
	 * @see org.springframework.beans.factory.ListableBeanFactory#getBeanDefinitionNames
	 */
	String[] getSingletonNames();

	/**
	 * Return the number of singleton beans registered in this registry.
	 * <p>Only checks already instantiated singletons; does not count
	 * singleton bean definitions which have not been instantiated yet.
	 * <p>The main purpose of this method is to check manually registered singletons
	 * (see {@link #registerSingleton}). Can also be used to count the number of
	 * singletons defined by a bean definition that have already been created.
	 * @return the number of singleton beans
	 * @see #registerSingleton
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry#getBeanDefinitionCount
	 * @see org.springframework.beans.factory.ListableBeanFactory#getBeanDefinitionCount
	 */
	int getSingletonCount();

	/**
	 * Return the singleton mutex used by this registry (for external collaborators).
	 * @return the mutex object (never {@code null})
	 * @since 4.2
	 */
	Object getSingletonMutex();

}
