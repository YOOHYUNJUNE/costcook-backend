package com.costcook.service;

public interface EmailService {
	public void sendVerificationCode(String email, String verificationCode);
	public boolean verifyCode(String email, String verificationCode);
}