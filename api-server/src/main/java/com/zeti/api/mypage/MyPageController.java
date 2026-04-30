package com.zeti.api.mypage;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mypage")
@RequiredArgsConstructor
public class MyPageController {

    private final MyPageService myPageService;

    // JWT sub 기반 본인 마이페이지 종합 (user + 기본 배송지 + 최근 주문 5건 + 결제수단 잔액)
    @GetMapping
    public MyPageResponse getMyPage(@AuthenticationPrincipal Long userId) {
        return myPageService.getMyPage(userId);
    }
}
