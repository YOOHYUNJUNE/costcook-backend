package com.costcook.service.impl;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.costcook.domain.PlatformTypeEnum;
import com.costcook.domain.request.SignUpOrLoginRequest;
import com.costcook.domain.response.SignUpOrLoginResponse;
import com.costcook.entity.SocialAccount;
import com.costcook.entity.User;
import com.costcook.exceptions.InvalidProviderException;
import com.costcook.exceptions.MissingFieldException;
import com.costcook.repository.SocialAccountRepository;
import com.costcook.repository.UserRepository;
import com.costcook.service.AuthService;
import com.costcook.util.TokenUtils;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final TokenUtils tokenUtils;
    
    /**
     * 주어진 소셜 키와 공급자를 사용하여 해당 사용자의 이메일을 조회합니다.
     *
     * 1. 소셜 계정(social_accounts) 테이블에서 소셜 키와 공급자를 기준으로 사용자 정보를 검색합니다.
     * 2. 사용자가 존재할 경우, 해당 사용자의 이메일을 리턴합니다.
     *
     * @param socialKey - 소셜 계정의 고유 키
     * @param provider - 소셜 공급자 유형 (Kakao, Google 등)
     * @return Optional<String> - 사용자의 이메일이 존재할 경우 해당 이메일, 그렇지 않으면 Optional.empty() 반환
     */
    @Override
    public Optional<String> getEmailFromSocialAccount(String socialKey, PlatformTypeEnum provider) {
        // social_accounts 테이블에서 socialKey와 provider를 기준으로 사용자 조회
        Optional<SocialAccount> socialAccountOpt = socialAccountRepository.findBySocialKeyAndProvider(socialKey, provider);
        
        if (socialAccountOpt.isPresent()) {
            // 사용자 ID를 가져옴
            User user = socialAccountOpt.get().getUser();
            return Optional.of(user.getEmail());
        }
        
        return Optional.empty(); // 사용자를 찾지 못한 경우
    }

    @Override
    public SignUpOrLoginResponse signUpOrLogin(SignUpOrLoginRequest request, HttpServletResponse response) {
        // 1. Request DTO 유효성 검증
        validateSignUpOrLoginRequest(request);

        // 2. email 필드를 기준으로 회원 가입 여부 확인
        User user = userRepository.findByEmail(request.getEmail())
                .orElseGet(() -> registerNewUser(request));

        log.info("새로 가입한 회원 정보: {}", user);

        // 2-2. email 을 기준으로 이미 회원가입한 회원이지만, social_accounts 테이블 조회(provider, socialKey) 없는 경우 registerNewUser(request) 호출 필요.
        log.info("findBySocialKeyAndProvider 호출");        
        Optional<SocialAccount> socialAccount = socialAccountRepository.findBySocialKeyAndProvider(request.getSocialKey(), PlatformTypeEnum.fromString(request.getProvider()));
        log.info("{}", socialAccount.toString());        
        if (!socialAccount.isPresent() && request.getProvider() != null && request.getSocialKey() != null) {
            // 소셜 계정 정보가 없다면 카카오 계정 정보를 등록
            registerSocialAccount(user, request.getProvider(), request.getSocialKey());
            log.info("소셜 계정 정보가 등록되었습니다: {} - {}", request.getProvider(), request.getSocialKey());
        }


        // 3. 액세스 토큰, 리프레시 토큰 발급
        Map<String, String> tokenMap = tokenUtils.generateToken(user);
        String accessToken = tokenMap.get("accessToken");
        String refreshToken = tokenMap.get("refreshToken");
        log.info("새로 발급된 액세스 토큰: {}", accessToken);
        log.info("새로 발급된 리프레시 토큰: {}", refreshToken);
        
        // 4. 발급된 리프레시 토큰 사용자 테이블에 저장
        user.setRefreshToken(refreshToken);
        userRepository.save(user);
        
        // 5. 쿠키에 생성된 리프레시 토큰과 액세스 토큰을 담아 응답
        tokenUtils.setRefreshTokenCookie(response, refreshToken);
        tokenUtils.setAccessTokenCookie(response, accessToken);
        
        // SignUpOrLoginResponse 객체 생성
        boolean isNewUser = user.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(1)); // 1분 이내에 생성된 경우 신규 사용자로 간주
        SignUpOrLoginResponse signUpOrLoginResponse = SignUpOrLoginResponse.builder()
                .message(isNewUser ? "회원가입 후 로그인이 완료되었습니다." : "로그인에 성공했습니다.")
                .accessToken(accessToken)
                .isNewUser(isNewUser)
                .build();
        
        return signUpOrLoginResponse;
    }

    // SignUpOrLoginRequest의 필수 필드를 검증
    private void validateSignUpOrLoginRequest(SignUpOrLoginRequest request) {
        log.info(request.toString());
        if (request.getSocialKey() == null || request.getEmail() == null || request.getProvider() == null) {
            throw new MissingFieldException();
        }

        if (!isValidProvider(request.getProvider())) {
            throw new InvalidProviderException("적절하지 않은 소셜 로그인 제공자입니다.");
        }
    }

    // 지원되는 소셜 로그인 제공자인지 확인
    private boolean isValidProvider(String provider) {
        return provider.equals("kakao") || provider.equals("google");
    }
    
    // 신규 사용자 등록 및 소셜 계정 정보 저장
    private User registerNewUser(SignUpOrLoginRequest request) {
        User newUser = new User();
        newUser.setEmail(request.getEmail());
        // 추가적인 사용자 정보 설정...
        
        // 신규 사용자 저장
        User savedUser = userRepository.save(newUser);

        // 소셜 계정 정보 저장
        SocialAccount socialAccount = SocialAccount
                .builder()
                .provider(PlatformTypeEnum.fromString(request.getProvider()))
                .socialKey(request.getSocialKey())
                .user(savedUser)
                .build();
        socialAccountRepository.save(socialAccount);

        return savedUser;
    }

    // 소셜 계정 등록 메소드
    private void registerSocialAccount(User user, String provider, String socialKey) {
    	log.info("{}", PlatformTypeEnum.fromString(provider));
    	log.info("--------");
        SocialAccount newSocialAccount = SocialAccount
                    .builder()
                    .provider(PlatformTypeEnum.fromString(provider))
                    .socialKey(socialKey)
                    .user(user)
                    .build();
        socialAccountRepository.save(newSocialAccount);
    }
}