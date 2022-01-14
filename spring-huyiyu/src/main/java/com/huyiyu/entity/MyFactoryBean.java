package com.huyiyu.entity;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.stereotype.Component;

@Component
public class MyFactoryBean implements FactoryBean<B> {

	@Override
	public B getObject() throws Exception {
		return new B();
	}

	@Override
	public Class<?> getObjectType() {
		return B.class;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

}
