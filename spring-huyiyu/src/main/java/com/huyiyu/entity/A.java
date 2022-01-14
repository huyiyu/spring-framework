package com.huyiyu.entity;

import java.util.Arrays;
import org.springframework.stereotype.Component;

public class A {

	private B b;
	private C c;

	public A(B b,C c) {
		this.b = b;
		this.c = c;
	}

	public A(C c) {
		this.c = c;
	}

	public A(){

	}

	public A(B b) {
		this.b = b;
	}


	public void a(){}
	public void ab(){}
	public void abc(){}
	public void abcd(){}
	public void abcde(){}
	public void abcdef(){}


	public static void main(String[] args) {
		Arrays.toString(A.class.getDeclaredConstructors());
		Arrays.toString(A.class.getDeclaredConstructors());
		Arrays.toString(A.class.getDeclaredConstructors());
		Arrays.toString(A.class.getDeclaredConstructors());
		Arrays.toString(A.class.getDeclaredConstructors());
		Arrays.toString(A.class.getDeclaredConstructors());

	}
}
