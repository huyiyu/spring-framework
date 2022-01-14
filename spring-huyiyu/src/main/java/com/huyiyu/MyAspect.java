package com.huyiyu;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class MyAspect {

	@Aspect
	public static class LoggingAspect {

		@Before("execution(public * com.huyiyu.entity.*.*())")
		public void loggingBeginByAtArgs() {
			System.out.println("AOP before");
		}
	}

}
