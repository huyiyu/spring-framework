/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.servlet.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.context.support.WebApplicationObjectSupport;

/**
 * Convenient superclass for any kind of web content generator,
 * like {@link org.springframework.web.servlet.mvc.AbstractController}
 * and {@link org.springframework.web.servlet.mvc.WebContentInterceptor}.
 * Can also be used for custom handlers that have their own
 * {@link org.springframework.web.servlet.HandlerAdapter}.
 *
 * <p>Supports HTTP cache control options. The usage of corresponding HTTP
 * headers can be controlled via the {@link #setCacheSeconds "cacheSeconds"}
 * and {@link #setCacheControl "cacheControl"} properties.
 *
 * <p><b>NOTE:</b> As of Spring 4.2, this generator's default behavior changed when
 * using only {@link #setCacheSeconds}, sending HTTP response headers that are in line
 * with current browsers and proxies implementations (i.e. no HTTP 1.0 headers anymore)
 * Reverting to the previous behavior can be easily done by using one of the newly
 * deprecated methods {@link #setUseExpiresHeader}, {@link #setUseCacheControlHeader},
 * {@link #setUseCacheControlNoStore} or {@link #setAlwaysMustRevalidate}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Brian Clozel
 * @author Rossen Stoyanchev
 * @see #setCacheSeconds
 * @see #setCacheControl
 * @see #setRequireSession
 */
public abstract class WebContentGenerator extends WebApplicationObjectSupport {

	/** HTTP method "GET". */
	public static final String METHOD_GET = "GET";

	/** HTTP method "HEAD". */
	public static final String METHOD_HEAD = "HEAD";

	/** HTTP method "POST". */
	public static final String METHOD_POST = "POST";

	/**
	 * 由于 Pragma 在 HTTP 响应中的行为没有确切规范，所以不能可靠替代 HTTP/1.1 中通用首部 Cache-Control，
	 * 尽管在请求中，假如 Cache-Control 不存在的话，它的行为与 Cache-Control:
	 * no-cache 一致。建议只在需要兼容 HTTP/1.0 客户端的场合下应用 Pragma 首部
	 */
	private static final String HEADER_PRAGMA = "Pragma";

	/**
	 * 与浏览器缓存相关 只是当前HTTP在某个阶段后过期
	 */
	private static final String HEADER_EXPIRES = "Expires";
	/**
	 * 浏览器缓存相关 指示当前缓存处理的策略
	 */
	protected static final String HEADER_CACHE_CONTROL = "Cache-Control";


	/** Set of supported HTTP methods. */
	@Nullable
	private Set<String> supportedMethods;
	/**
	 * 允许的http方法
	 * 逗号分隔,开启默认严格校验时为 GET,POST,HEAD,OPTIONS
	 * 否则 扣掉TRACE的所有方法
	 */
	@Nullable
	private String allowHeader;

	private boolean requireSession = false;

	/**
	 * 对浏览器响应头 cache-controll的控制
	 */
	@Nullable
	private CacheControl cacheControl;

	private int cacheSeconds = -1;

	@Nullable
	private String[] varyByRequestHeaders;


	// deprecated fields

	/** Use HTTP 1.0 expires header? */
	private boolean useExpiresHeader = false;

	/** Use HTTP 1.1 cache-control header? */
	private boolean useCacheControlHeader = true;

	/** Use HTTP 1.1 cache-control header value "no-store"? */
	private boolean useCacheControlNoStore = true;

	private boolean alwaysMustRevalidate = false;


	/**
	 * 不要被这个空参构造误导了,根本没有用实际调用
	 */
	public WebContentGenerator() {
		this(true);
	}

	/**
	 *
	 * 默认HandlerAdapter 使用此构造 不限制方法
	 * @param restrictDefaultSupportedMethods {@code true} 如果为true 只允许get post head options
	 * 一般设置成false
	 */
	public WebContentGenerator(boolean restrictDefaultSupportedMethods) {
		// 实际改代码不执行限制默认方法而是都添加
		if (restrictDefaultSupportedMethods) {
			this.supportedMethods = new LinkedHashSet<>(4);
			this.supportedMethods.add(METHOD_GET);
			this.supportedMethods.add(METHOD_HEAD);
			this.supportedMethods.add(METHOD_POST);
		}
		initAllowHeader();
	}

	/**
	 * Create a new WebContentGenerator.
	 * @param supportedMethods the supported HTTP methods for this content generator
	 */
	public WebContentGenerator(String... supportedMethods) {
		setSupportedMethods(supportedMethods);
	}


	/**
	 * Set the HTTP methods that this content generator should support.
	 * <p>Default is GET, HEAD and POST for simple form controller types;
	 * unrestricted for general controllers and interceptors.
	 */
	public final void setSupportedMethods(@Nullable String... methods) {
		if (!ObjectUtils.isEmpty(methods)) {
			this.supportedMethods = new LinkedHashSet<>(Arrays.asList(methods));
		}
		else {
			this.supportedMethods = null;
		}
		initAllowHeader();
	}

	/**
	 * Return the HTTP methods that this content generator supports.
	 */
	@Nullable
	public final String[] getSupportedMethods() {
		return (this.supportedMethods != null ? StringUtils.toStringArray(this.supportedMethods) : null);
	}

	/**
	 * 初始化默认允许的方法,当 supportedMethods 为空时 接受所有非 trace 方法
	 * 当 supportedMethods 有内容时,确保一定添加OPTIONS 方法(没有手动添加) 并接受supportMethods 方法列表
	 */
	private void initAllowHeader() {
		Collection<String> allowedMethods;
		// 如果默认支持的方法列表为空,去掉trace方法其他全部允许
		if (this.supportedMethods == null) {

			allowedMethods = new ArrayList<>(HttpMethod.values().length - 1);
			for (HttpMethod method : HttpMethod.values()) {
				if (method != HttpMethod.TRACE) {
					allowedMethods.add(method.name());
				}
			}
		}
		// 如果不为空 且包含OPTIONS 方法,直接获取支持的方法作为允许的方法
		else if (this.supportedMethods.contains(HttpMethod.OPTIONS.name())) {
			allowedMethods = this.supportedMethods;
		}
		// 如果不包含OPTIONS 方法,除了已经支持的方法,要手动添加OPTIONS
		else {
			allowedMethods = new ArrayList<>(this.supportedMethods);
			allowedMethods.add(HttpMethod.OPTIONS.name());

		}
		// 使用逗号隔开组成字符串
		this.allowHeader = StringUtils.collectionToCommaDelimitedString(allowedMethods);
	}


	@Nullable
	protected String getAllowHeader() {
		return this.allowHeader;
	}

	/**
	 * Set whether a session should be required to handle requests.
	 */
	public final void setRequireSession(boolean requireSession) {
		this.requireSession = requireSession;
	}

	/**
	 * Return whether a session is required to handle requests.
	 */
	public final boolean isRequireSession() {
		return this.requireSession;
	}

	/**
	 * Set the {@link org.springframework.http.CacheControl} instance to build
	 * the Cache-Control HTTP response header.
	 * @since 4.2
	 */
	public final void setCacheControl(@Nullable CacheControl cacheControl) {
		this.cacheControl = cacheControl;
	}

	/**
	 * Get the {@link org.springframework.http.CacheControl} instance
	 * that builds the Cache-Control HTTP response header.
	 * @since 4.2
	 */
	@Nullable
	public final CacheControl getCacheControl() {
		return this.cacheControl;
	}

	/**
	 * Cache content for the given number of seconds, by writing
	 * cache-related HTTP headers to the response:
	 * <ul>
	 * <li>seconds == -1 (default value): no generation cache-related headers</li>
	 * <li>seconds == 0: "Cache-Control: no-store" will prevent caching</li>
	 * <li>seconds > 0: "Cache-Control: max-age=seconds" will ask to cache content</li>
	 * </ul>
	 * <p>For more specific needs, a custom {@link org.springframework.http.CacheControl}
	 * should be used.
	 * @see #setCacheControl
	 */
	public final void setCacheSeconds(int seconds) {
		this.cacheSeconds = seconds;
	}

	/**
	 * Return the number of seconds that content is cached.
	 */
	public final int getCacheSeconds() {
		return this.cacheSeconds;
	}

	/**
	 * Configure one or more request header names (e.g. "Accept-Language") to
	 * add to the "Vary" response header to inform clients that the response is
	 * subject to content negotiation and variances based on the value of the
	 * given request headers. The configured request header names are added only
	 * if not already present in the response "Vary" header.
	 * @param varyByRequestHeaders one or more request header names
	 * @since 4.3
	 */
	public final void setVaryByRequestHeaders(@Nullable String... varyByRequestHeaders) {
		this.varyByRequestHeaders = varyByRequestHeaders;
	}

	/**
	 * Return the configured request header names for the "Vary" response header.
	 * @since 4.3
	 */
	@Nullable
	public final String[] getVaryByRequestHeaders() {
		return this.varyByRequestHeaders;
	}

	/**
	 * Set whether to use the HTTP 1.0 expires header. Default is "false",
	 * as of 4.2.
	 * <p>Note: Cache headers will only get applied if caching is enabled
	 * (or explicitly prevented) for the current request.
	 * @deprecated as of 4.2, since going forward, the HTTP 1.1 cache-control
	 * header will be required, with the HTTP 1.0 headers disappearing
	 */
	@Deprecated
	public final void setUseExpiresHeader(boolean useExpiresHeader) {
		this.useExpiresHeader = useExpiresHeader;
	}

	/**
	 * Return whether the HTTP 1.0 expires header is used.
	 * @deprecated as of 4.2, in favor of {@link #getCacheControl()}
	 */
	@Deprecated
	public final boolean isUseExpiresHeader() {
		return this.useExpiresHeader;
	}

	/**
	 * Set whether to use the HTTP 1.1 cache-control header. Default is "true".
	 * <p>Note: Cache headers will only get applied if caching is enabled
	 * (or explicitly prevented) for the current request.
	 * @deprecated as of 4.2, since going forward, the HTTP 1.1 cache-control
	 * header will be required, with the HTTP 1.0 headers disappearing
	 */
	@Deprecated
	public final void setUseCacheControlHeader(boolean useCacheControlHeader) {
		this.useCacheControlHeader = useCacheControlHeader;
	}

	/**
	 * Return whether the HTTP 1.1 cache-control header is used.
	 * @deprecated as of 4.2, in favor of {@link #getCacheControl()}
	 */
	@Deprecated
	public final boolean isUseCacheControlHeader() {
		return this.useCacheControlHeader;
	}

	/**
	 * Set whether to use the HTTP 1.1 cache-control header value "no-store"
	 * when preventing caching. Default is "true".
	 * @deprecated as of 4.2, in favor of {@link #setCacheControl}
	 */
	@Deprecated
	public final void setUseCacheControlNoStore(boolean useCacheControlNoStore) {
		this.useCacheControlNoStore = useCacheControlNoStore;
	}

	/**
	 * Return whether the HTTP 1.1 cache-control header value "no-store" is used.
	 * @deprecated as of 4.2, in favor of {@link #getCacheControl()}
	 */
	@Deprecated
	public final boolean isUseCacheControlNoStore() {
		return this.useCacheControlNoStore;
	}

	/**
	 * An option to add 'must-revalidate' to every Cache-Control header.
	 * This may be useful with annotated controller methods, which can
	 * programmatically do a last-modified calculation as described in
	 * {@link org.springframework.web.context.request.WebRequest#checkNotModified(long)}.
	 * <p>Default is "false".
	 * @deprecated as of 4.2, in favor of {@link #setCacheControl}
	 */
	@Deprecated
	public final void setAlwaysMustRevalidate(boolean mustRevalidate) {
		this.alwaysMustRevalidate = mustRevalidate;
	}

	/**
	 * Return whether 'must-revalidate' is added to every Cache-Control header.
	 * @deprecated as of 4.2, in favor of {@link #getCacheControl()}
	 */
	@Deprecated
	public final boolean isAlwaysMustRevalidate() {
		return this.alwaysMustRevalidate;
	}



	/**
	 * 检查当前请求方法是否在允许列表中
	 * @param request
	 * @throws ServletException
	 */
	protected final void checkRequest(HttpServletRequest request) throws ServletException {
		//检查请求方法
		String method = request.getMethod();
		if (this.supportedMethods != null && !this.supportedMethods.contains(method)) {
			throw new HttpRequestMethodNotSupportedException(method, this.supportedMethods);
		}

		// 检查当前是否允许session 并且当前请求有没有session
		if (this.requireSession && request.getSession(false) == null) {
			throw new HttpSessionRequiredException("Pre-existing session required but none found");
		}
	}

	/**
	 * Prepare the given response according to the settings of this generator.
	 * Applies the number of cache seconds specified for this generator.
	 * @param response current HTTP response
	 * @since 4.2
	 */
	protected final void prepareResponse(HttpServletResponse response) {
		if (this.cacheControl != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Applying default " + getCacheControl());
			}
			// 设置响应缓存策略
			applyCacheControl(response, this.cacheControl);
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Applying default cacheSeconds=" + this.cacheSeconds);
			}
			// 设置缓存时间此流程大部分废弃的方法
			applyCacheSeconds(response, this.cacheSeconds);
		}
		if (this.varyByRequestHeaders != null) {
			//			设置vary头
			for (String value : getVaryRequestHeadersToAdd(response, this.varyByRequestHeaders)) {
				response.addHeader("Vary", value);
			}
		}
	}

	/**
	 * 设置浏览器缓存的处理策略 响应头
	 * @param response current HTTP response
	 * @param cacheControl the pre-configured cache control settings
	 * @since 4.2
	 */
	protected final void applyCacheControl(HttpServletResponse response, CacheControl cacheControl) {
		String ccValue = cacheControl.getHeaderValue();
		if (ccValue != null) {
			// Set computed HTTP 1.1 Cache-Control header
			response.setHeader(HEADER_CACHE_CONTROL, ccValue);

			if (response.containsHeader(HEADER_PRAGMA)) {
				// Reset HTTP 1.0 Pragma header if present
				response.setHeader(HEADER_PRAGMA, "");
			}
			if (response.containsHeader(HEADER_EXPIRES)) {
				// Reset HTTP 1.0 Expires header if present
				response.setHeader(HEADER_EXPIRES, "");
			}
		}
	}

	/**
	 * Apply the given cache seconds and generate corresponding HTTP headers,
	 * i.e. allow caching for the given number of seconds in case of a positive
	 * value, prevent caching if given a 0 value, do nothing else.
	 * Does not tell the browser to revalidate the resource.
	 * @param response current HTTP response
	 * @param cacheSeconds positive number of seconds into the future that the
	 * response should be cacheable for, 0 to prevent caching
	 */
	@SuppressWarnings("deprecation")
	protected final void applyCacheSeconds(HttpServletResponse response, int cacheSeconds) {
		if (this.useExpiresHeader || !this.useCacheControlHeader) {
			// Deprecated HTTP 1.0 cache behavior, as in previous Spring versions
			if (cacheSeconds > 0) {
				cacheForSeconds(response, cacheSeconds);
			}
			else if (cacheSeconds == 0) {
				preventCaching(response);
			}
		}
		else {
			CacheControl cControl;
			if (cacheSeconds > 0) {
				cControl = CacheControl.maxAge(cacheSeconds, TimeUnit.SECONDS);
				if (this.alwaysMustRevalidate) {
					cControl = cControl.mustRevalidate();
				}
			}
			else if (cacheSeconds == 0) {
				cControl = (this.useCacheControlNoStore ? CacheControl.noStore() : CacheControl.noCache());
			}
			else {
				cControl = CacheControl.empty();
			}
			applyCacheControl(response, cControl);
		}
	}


	/**
	 * 检查并准备response  两个动作合并
	 * 检查 的是allowMethod
	 * 准备的是缓存策略
	 * 方法已经废弃
	 */
	@Deprecated
	protected final void checkAndPrepare(
			HttpServletRequest request, HttpServletResponse response, boolean lastModified) throws ServletException {

		checkRequest(request);
		prepareResponse(response);
	}

	/**
	 * Check and prepare the given request and response according to the settings
	 * of this generator.
	 * @see #checkRequest(HttpServletRequest)
	 * @see #applyCacheSeconds(HttpServletResponse, int)
	 * @deprecated as of 4.2, since the {@code lastModified} flag is effectively ignored,
	 * with a must-revalidate header only generated if explicitly configured
	 */
	@Deprecated
	protected final void checkAndPrepare(
			HttpServletRequest request, HttpServletResponse response, int cacheSeconds, boolean lastModified)
			throws ServletException {

		checkRequest(request);
		applyCacheSeconds(response, cacheSeconds);
	}

	/**
	 * Apply the given cache seconds and generate respective HTTP headers.
	 * <p>That is, allow caching for the given number of seconds in the
	 * case of a positive value, prevent caching if given a 0 value, else
	 * do nothing (i.e. leave caching to the client).
	 * @param response the current HTTP response
	 * @param cacheSeconds the (positive) number of seconds into the future
	 * that the response should be cacheable for; 0 to prevent caching; and
	 * a negative value to leave caching to the client.
	 * @param mustRevalidate whether the client should revalidate the resource
	 * (typically only necessary for controllers with last-modified support)
	 * @deprecated as of 4.2, in favor of {@link #applyCacheControl}
	 */
	@Deprecated
	protected final void applyCacheSeconds(HttpServletResponse response, int cacheSeconds, boolean mustRevalidate) {
		if (cacheSeconds > 0) {
			cacheForSeconds(response, cacheSeconds, mustRevalidate);
		}
		else if (cacheSeconds == 0) {
			preventCaching(response);
		}
	}

	/**
	 * Set HTTP headers to allow caching for the given number of seconds.
	 * Does not tell the browser to revalidate the resource.
	 * @param response current HTTP response
	 * @param seconds number of seconds into the future that the response
	 * should be cacheable for
	 * @deprecated as of 4.2, in favor of {@link #applyCacheControl}
	 */
	@Deprecated
	protected final void cacheForSeconds(HttpServletResponse response, int seconds) {
		cacheForSeconds(response, seconds, false);
	}

	/**
	 * Set HTTP headers to allow caching for the given number of seconds.
	 * Tells the browser to revalidate the resource if mustRevalidate is
	 * {@code true}.
	 * @param response the current HTTP response
	 * @param seconds number of seconds into the future that the response
	 * should be cacheable for
	 * @param mustRevalidate whether the client should revalidate the resource
	 * (typically only necessary for controllers with last-modified support)
	 * @deprecated as of 4.2, in favor of {@link #applyCacheControl}
	 */
	@Deprecated
	protected final void cacheForSeconds(HttpServletResponse response, int seconds, boolean mustRevalidate) {
		if (this.useExpiresHeader) {
			// HTTP 1.0 header
			response.setDateHeader(HEADER_EXPIRES, System.currentTimeMillis() + seconds * 1000L);
		}
		else if (response.containsHeader(HEADER_EXPIRES)) {
			// Reset HTTP 1.0 Expires header if present
			response.setHeader(HEADER_EXPIRES, "");
		}

		if (this.useCacheControlHeader) {
			// HTTP 1.1 header
			String headerValue = "max-age=" + seconds;
			if (mustRevalidate || this.alwaysMustRevalidate) {
				headerValue += ", must-revalidate";
			}
			response.setHeader(HEADER_CACHE_CONTROL, headerValue);
		}

		if (response.containsHeader(HEADER_PRAGMA)) {
			// Reset HTTP 1.0 Pragma header if present
			response.setHeader(HEADER_PRAGMA, "");
		}
	}

	/**
	 * 防止响应被缓存。仅在 HTTP 1.0 兼容模式下调用。
	 * <p>参见 {@code https:www.mnot.netcache_docs}。
	 * @deprecated 自 4.2 起，支持 {@link applyCacheControl}
	 */
	@Deprecated
	protected final void preventCaching(HttpServletResponse response) {
		response.setHeader(HEADER_PRAGMA, "no-cache");

		if (this.useExpiresHeader) {
			// HTTP 1.0 Expires header
			response.setDateHeader(HEADER_EXPIRES, 1L);
		}

		if (this.useCacheControlHeader) {
			// HTTP 1.1 Cache-Control header: "no-cache" is the standard value,
			// "no-store" is necessary to prevent caching on Firefox.
			response.setHeader(HEADER_CACHE_CONTROL, "no-cache");
			if (this.useCacheControlNoStore) {
				response.addHeader(HEADER_CACHE_CONTROL, "no-store");
			}
		}
	}

	/**
	 * 添加vary 响应头告诉浏览器内容协商应
	 * 使用哪些响应头
	 * @param response
	 * @param varyByRequestHeaders
	 * @return
	 */
	private Collection<String> getVaryRequestHeadersToAdd(HttpServletResponse response, String[] varyByRequestHeaders) {
		if (!response.containsHeader(HttpHeaders.VARY)) {
			return Arrays.asList(varyByRequestHeaders);
		}
		Collection<String> result = new ArrayList<>(varyByRequestHeaders.length);
		Collections.addAll(result, varyByRequestHeaders);
		for (String header : response.getHeaders(HttpHeaders.VARY)) {
			for (String existing : StringUtils.tokenizeToStringArray(header, ",")) {
				if ("*".equals(existing)) {
					return Collections.emptyList();
				}
				for (String value : varyByRequestHeaders) {
					if (value.equalsIgnoreCase(existing)) {
						result.remove(value);
					}
				}
			}
		}
		return result;
	}

}
