/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.web.servlet.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to an incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * 当某个bean 使用了scope代理后,对应的ProxyFactoryBean 的名称
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	/**
	 * 当遇到CORS预请求时,使用的HandlerMethod
	 */
	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	/**
	 * 默认的全局CORS配置
	 * 在 static 代码块里初始化
	 */
	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOriginPattern("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}

	/**
	 * 是否在祖先的 ApplicationContext 中搜索HandlerMethod
	 */
	private boolean detectHandlerMethodsInAncestorContexts = false;

	/**
	 * 提供给mappingRegistry 的nameLookup map 属性 生成key 的名称生成器
	 * 默认格式 类大写字母#方法名
	 */
	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy;

	/**
	 * 请求URL和method 和对应Handler 的映射
	 * */
	private final MappingRegistry mappingRegistry = new MappingRegistry();


	/**
	 * 设置 spring 5.3 版本以上提供的新的路径解析器
	 * @param patternParser the parser to use
	 */
	@Override
	public void setPatternParser(PathPatternParser patternParser) {
		Assert.state(this.mappingRegistry.getRegistrations().isEmpty(),
				"PathPatternParser must be set before the initialization of " +
						"request mappings through InitializingBean#afterPropertiesSet.");
		super.setPatternParser(patternParser);
	}

	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * 设置nameLookup的名称生成逻辑
	 * @param namingStrategy 设置名称生成逻辑
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) {
		this.namingStrategy = namingStrategy;
	}

	/**
	 * 获取名称生成逻辑
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * 从当前mappingRegistry 获取到所有的方法映射 map
	 * key是
	 * @return
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(
					this.mappingRegistry.getRegistrations().entrySet().stream()
							.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().handlerMethod)));
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * 为特定的mapping 对象注册对应的Handler 和method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * 为特定的 mapping 移除对应的
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * spring bean初始化生命周期过程中
	 * 执行的方法 即使被子类重写,子类也会回调父类
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() {
		initHandlerMethods();
	}

	/**
	 * 生命周期执行时,调用的方法 获取所有
	 * spring 容器的Bean(开关控制是否获取祖先的) f
	 * 过滤掉scopeProxy类(因为scopeproxyFactoryBean一定不是Controller)
	 * 然后执行 handlerMethodsInitialized 打印日志记录当前注册了多少个 HandlerMethod
	 */
	protected void initHandlerMethods() {
		for (String beanName : getCandidateBeanNames()) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				// 检查所有BeanName 是否同时具备又有 @RequestMapping 又有 @Controller
				processCandidateBean(beanName);
			}
		}
		// 打印生成了多少个Handler映射关系
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
	 * 获取所有的Bean 的BeanName 根据 detectHandlerMethodsInAncestorContexts
	 * 判断是否从祖先中查询 controller
	 */
	protected String[] getCandidateBeanNames() {
		return (this.detectHandlerMethodsInAncestorContexts ?
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}


	/**
	 * 对传入的BeanName 判断是不是一个Controller
	 * 如果是,注册到mappingRegistry (生成requestMappingInfo 和 HandlerMethod 对应关系)
	 * 不是忽略
	 * @param beanName bean 名称
	 */
	protected void processCandidateBean(String beanName) {
		Class<?> beanType = null;
		try {
			// 根据BeanName获取类型 本质上调用 BeanFactory的getType
			beanType = obtainApplicationContext().getType(beanName);
		}
		catch (Throwable ex) {
			// 无法识别的Bean类型 可以直接跳过
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		// getType 异常可能导致beanType为空
		// 判断是否是Handler 条件是 有@RequestMapping 和@Controller 两个注解
		if (beanType != null && isHandler(beanType)) {
			// 通过benName搜索带 requestMapping 的方法,从而向MapperRegistry 注册
			detectHandlerMethods(beanName);
		}
	}

	/**
	 * 扫描Controller 有多少方法
	 * 默认 Handler	是  一个 beanname string类型.
	 * 仅有processCandidateBean 调用了 该方法
	 * @param handler controller 对象的 beanName
	 */
	protected void detectHandlerMethods(Object handler) {
		// 获取对应的类型,仅有一个方法调用,此处一定走BeanFactory.getType 获取Bean类型
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		if (handlerType != null) {
			// 去掉 Spring AOP代理 返回用户编写的类
			Class<?> userType = ClassUtils.getUserClass(handlerType);
			// 获取class 反射(自身+接口)获得 method  传递给外部回调接口调用getMappingForMethod 生成valueß
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> {
						try {
							// 子类实现这个接口从而提供注册到mappingRegistry 的mapping 对象
							return getMappingForMethod(method, userType);
						}
						catch (Throwable ex) {
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});
			// 打印requestMapping 扫描过程
			if (logger.isTraceEnabled()) {
				logger.trace(formatMappings(userType, methods));
			}
			else if (mappingsLogger.isDebugEnabled()) {
				mappingsLogger.debug(formatMappings(userType, methods));
			}
			methods.forEach((method, mapping) -> {
				// 查找可运行的方法,之前拿到的Method 可能是接口的
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
				// 向 mappingRegistry 注册这个requestMapping
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}

	/**
	 * 用于日志打印 当前Handler以及他的Mapping 信息
	 * @param userType 对应的用户类
	 * @param methods 对应的Method映射表
	 * @return
	 */
	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", "." + userType.getSimpleName()));
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**
	 * 透传给mappingRegistry 注册 requestMapping
	 * @param handler beanName
	 * @param method 对应的Method
	 * @param mapping requestMappingInfo 对象(mvc 或webflux)
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * 创建一个HandlerMethod 对象
	 * @param handler beanName
	 * @param method 对应目标方法
	 * @return 返回 HandlerMethod 对象
	 * */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		if (handler instanceof String) {
			return new HandlerMethod((String) handler,
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);
		}
		return new HandlerMethod(handler, method);
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * 打印生成的handlerMethod 总数
	 * @param handlerMethods 一个只读的Handler 映射表
	 * */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/**
	 * 通用的通过request 获取对应HandlerMethod 的做法
	 */
	@Override
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
		// 解析request 请求 获得整个path
		String lookupPath = initLookupPath(request);
		this.mappingRegistry.acquireReadLock();
		try {
			//通过lookupHandlerMethod 获取对应的	HandlerMethod 方法
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * 查找当前请求的最佳匹配处理程序方法。如果找到多个匹配项，则选择最佳匹配项。
	 * @param lookupPath 通过解析获得的URI
	 * @param request the current request 当前request
	 * @return  返回最佳匹配项的handlerMethod 如果没有匹配 那么关注
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
		List<Match> matches = new ArrayList<>();
		// 先匹配所有URL 没有参数的方法 看是否能拿到RequestMappingInfo
		List<T> directPathMatches = this.mappingRegistry.getMappingsByDirectPath(lookupPath);

		if (directPathMatches != null) {
			addMatchingMappings(directPathMatches, matches, request);
		}
		// 如果匹配直接路径匹配不上 那么 遍历所有registry 获得registrations匹配
		if (matches.isEmpty()) {
			addMatchingMappings(this.mappingRegistry.getRegistrations().keySet(), matches, request);
		}
		// 当matches 有内容意味着 匹配通过
		if (!matches.isEmpty()) {
			//获取第一个元素作为临时最佳匹配
			Match bestMatch = matches.get(0);
			// 如果匹配的Matches 不止一个
			if (matches.size() > 1) {
				// 提供一个比较器对匹配结果进行排序 然后 获取第一个
				Comparator<Match> comparator = new MatchComparator(getMappingComparator(request));
				matches.sort(comparator);
				bestMatch = matches.get(0);
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				// 如果请求是 CORS的options 请求 那么选择CORS-OPTIONS 独有的HandlerMethod
				if (CorsUtils.isPreFlightRequest(request)) {
					for (Match match : matches) {
						if (match.hasCorsConfig()) {
							return PREFLIGHT_AMBIGUOUS_MATCH;
						}
					}
				}
				else {
					// 否则获取 次优 的 requestMapping 方案 和最优的进行比较
					Match secondBestMatch = matches.get(1);
					// 如果 两者比较结果返回0 (评分相同) 那么抛出异常 匹配到多个 HandlerMethod
				if (comparator.compare(bestMatch, secondBestMatch) == 0) {
						Method m1 = bestMatch.getHandlerMethod().getMethod();
						Method m2 = secondBestMatch.getHandlerMethod().getMethod();
						String uri = request.getRequestURI();
						throw new IllegalStateException(
								"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
					}
				}
			}
			// 没有异常情况的环境下会匹配到仅有一个,此时 request attr 保存最优匹配
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.getHandlerMethod());
			// request attr 保存匹配结果
			handleMatch(bestMatch.mapping, lookupPath, request);
			// 返回对应的HandlerMethod
			return bestMatch.getHandlerMethod();
		}
		else {
			// 调用HandleNoMatch 尝试匹配普通 OPTIONS 或返回404 匹配不到
			return handleNoMatch(this.mappingRegistry.getRegistrations().keySet(), lookupPath, request);
		}
	}

	/**
	 * 匹配 mappings 列表中符合request 请求的对象
	 * 然后转化成Match对象转存入matches l列表
	 * @param mappings 待匹配的requestMappingInfo
	 * @param matches 匹配通过的Match对象
	 * @param request 待匹配的request
	 */
	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) {
		for (T mapping : mappings) {
			// 子类实现自己的MatchingMapping 判断是否匹配
			T match = getMatchingMapping(mapping, request);
			if (match != null) {
				// 如果匹配转化成Match对象存match列表
				matches.add(new Match(match, this.mappingRegistry.getRegistrations().get(mapping)));
			}
		}
	}

	/**
	 * 确定了 能找到对应的Handler 之后,把 HandlerMaping 固定字符串 匹配的 URI 和缓存到request attr
	 * @param mapping the matching mapping 当前方法这个参数没有用
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/**
	 * 当没有找到对应的 Handler 时的处理
	 * @param mappings all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return super.hasCorsConfigurationSource(handler) ||
				(handler instanceof HandlerMethod &&
						this.mappingRegistry.getCorsConfiguration((HandlerMethod) handler) != null);
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			}
			else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod);
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/**
	 * 提供给子类实现的识别当前Bean类型是否是一个Handler的方法
	 * 默认实现是检查既有@Controller 又有@RequestMapping
	 * @param beanType 当前Bean的类型
	 * @return 如果满足handler 要求返回true
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * 提供给子类实现作为注册到MappingRegistry 的value 的值,这个值作为后面
	 * 处理请求逻辑的执行器 默认RequestMappingHandlerMapping 封装了对RequestMapping 注解的解析
	 * @param method 带有RequestMapping 的可执行方法
	 * @param handlerType 对应的处理类型可能是当前类或当前类的子类
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * 这个方法已经废弃,被子类取代
	 */
	@Deprecated
	protected Set<String> getMappingPathPatterns(T mapping) {
		return Collections.emptySet();
	}

	/**
	 * Return the request mapping paths that are not patterns.
	 * @since 5.3
	 */
	protected Set<String> getDirectPaths(T mapping) {
		Set<String> urls = Collections.emptySet();
		for (String path : getMappingPathPatterns(mapping)) {
			if (!getPathMatcher().isPattern(path)) {
				urls = (urls.isEmpty() ? new HashSet<>(1) : urls);
				urls.add(path);
			}
		}
		return urls;
	}

	/**
	 * 检查映射是否与当前请求匹配，并返回一个（可能是新的）映射，其中包含与当前请求相关的条件。\
	 * @param mapping 匹配的映射 通常是一个requestMappingInfo
	 * @param request Servlet 提供的request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * 子类实现这个方法通过传入的request 获得一个对不同handler 的比较器
	 * requestMappingHandlerMapping 实现了对于@RequestMappingInfo 的比较器
	 * @param request 当前请求
	 * @return 返回一个比较器
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * 管理 requestMapping 和 handlerMethod 方法的 容器
	 */
	class MappingRegistry {

		/**
		 * key 是 requestMappingInfo
		 * value 是regist生成的 MappingRegistration
		 */
		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

		/**
		 * key 是不带有参数的直接映射路径
		 * value 是requestMappingInfo 列表
		 * 最先匹配路径的map
		 */
		private final MultiValueMap<String, T> pathLookup = new LinkedMultiValueMap<>();

		/**
		 * key 是String 当前类大写字母组合#方法名
		 * value 是对应的HandlerMethod 列表
		 */
		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>();

		/**
		 * 保存当前方法的CORS跨域配置
		 * 全局跨域配置 + 类上的注解 + 方法上的注解 并集
		 */
		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();

		/**
		 * 控制mappingregistry 的读写
		 */
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/**
		 * Return all registrations.
		 * @since 5.3
		 */
		public Map<T, MappingRegistration<T>> getRegistrations() {
			return this.registry;
		}

		/**
		 * Return matches for the given URL path. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByDirectPath(String urlPath) {
			return this.pathLookup.get(urlPath);
		}

		/**
		 * nameLookup 唯一对外方法 返回 当前 Mapping 缩写的全部HandlerMethod缩写
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		@Nullable
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		/**
		 * 透传给mappingRegistry 注册 requestMapping
		 * @param mapping requestMappingInfo 对象(mvc 或webflux)
		 * @param handler beanName
		 * @param method 对应的Method
		 */
		public void register(T mapping, Object handler, Method method) {
			this.readWriteLock.writeLock().lock();
			try {
				// 创建 HandlerMethod 对象 需要beanName 和对应方法
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);
				// 验证对应的RequestMappingInfo是否已经存在
				validateMethodMapping(handlerMethod, mapping);
				// 这里通过类名.this 获取的是外部类的this 但不一定是当前类内部的方法 有可能是子类的方法
				// 另外 directPaths 获取的是URL上没有参数的路径
				Set<String> directPaths = AbstractHandlerMethodMapping.this.getDirectPaths(mapping);
				for (String path : directPaths) {
					this.pathLookup.add(path, mapping);
				}
				// 注册到nameLookup key 是当前类大写字母组合#方法名 value 是 对应的handlerMethod对象
				String name = null;
				if (getNamingStrategy() != null) {
					name = getNamingStrategy().getName(handlerMethod, mapping);
					addMappingName(name, handlerMethod);
				}
				// 检查@CorsOrigin注解 并结合默认cors配置获得corsConfig 配置
				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					corsConfig.validateAllowCredentials();
					this.corsLookup.put(handlerMethod, corsConfig);
				}
				// 保存当前 requestMappingInfo
				this.registry.put(mapping,
						new MappingRegistration<>(mapping, handlerMethod, directPaths, name, corsConfig != null));
			}
			finally {
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void validateMethodMapping(HandlerMethod handlerMethod, T mapping) {
			// 由于registry 是普通的HashMap 所以requestMappingInfo 对象必然重写了 equals 和 hashcode 方法
			// 通过统计所有成员是否相等来判断两个requestMappingInfo是否相等
			MappingRegistration<T> registration = this.registry.get(mapping);
			//如果当前持有的requestMappingInfo有对应的HandlerMethod 说明该 Handler 已经注册,抛出异常
			HandlerMethod existingHandlerMethod = (registration != null ? registration.getHandlerMethod() : null);
			if (existingHandlerMethod != null && !existingHandlerMethod.equals(handlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + handlerMethod.getBean() + "' method \n" +
						handlerMethod + "\nto " + mapping + ": There is already '" +
						existingHandlerMethod.getBean() + "' bean method\n" + existingHandlerMethod + " mapped.");
			}
		}

		/**
		 * 对同名方法保存一个
		 * @param name 生成的当前类大写字母#方法名
		 * @param handlerMethod 对应方法
		 */
		private void addMappingName(String name, HandlerMethod handlerMethod) {
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				oldList = Collections.emptyList();
			}

			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}

			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList);
		}

		/**
		 * 取消注册 Mapping 本质上是通过移除
		 * @param mapping 当前 mapping
		 */
		public void unregister(T mapping) {
			this.readWriteLock.writeLock().lock();
			try {
				MappingRegistration<T> registration = this.registry.remove(mapping);
				if (registration == null) {
					return;
				}

				for (String path : registration.getDirectPaths()) {
					List<T> mappings = this.pathLookup.get(path);
					if (mappings != null) {
						mappings.remove(registration.getMapping());
						if (mappings.isEmpty()) {
							this.pathLookup.remove(path);
						}
					}
				}

				removeMappingName(registration);

				this.corsLookup.remove(registration.getHandlerMethod());
			}
			finally {
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}

	/**
	 * 作为registry 的value 存储,
	 * 保存requestMappingInfo，handlerMethod，directPaths corsconfig mappingName等信息
	 * @param <T> mapping 类型 requestMappingInfo
	 */
	static class MappingRegistration<T> {

		private final T mapping;

		private final HandlerMethod handlerMethod;

		private final Set<String> directPaths;

		@Nullable
		private final String mappingName;

		private final boolean corsConfig;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod,
				@Nullable Set<String> directPaths, @Nullable String mappingName, boolean corsConfig) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directPaths = (directPaths != null ? directPaths : Collections.emptySet());
			this.mappingName = mappingName;
			this.corsConfig = corsConfig;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public Set<String> getDirectPaths() {
			return this.directPaths;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}

		public boolean hasCorsConfig() {
			return this.corsConfig;
		}
	}


	/**
	 * 对registry key value 的简单封装
	 * 无内容
	 */
	private class Match {

		private final T mapping;

		private final MappingRegistration<T> registration;

		public Match(T mapping, MappingRegistration<T> registration) {
			this.mapping = mapping;
			this.registration = registration;
		}

		public HandlerMethod getHandlerMethod() {
			return this.registration.getHandlerMethod();
		}

		public boolean hasCorsConfig() {
			return this.registration.hasCorsConfig();
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}

	/**
	 * 包装了下比较器本质上使用子类提供的 mapping(requestMappingInfo) 比较器去比较
	 */
	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}

	/**
	 * 默认设置的DefaultHandler
	 */
	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

}
