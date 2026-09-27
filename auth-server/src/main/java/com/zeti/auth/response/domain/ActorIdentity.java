package com.zeti.auth.response.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가명(subject_key) → 실제 userId 역매핑. 로그인 때 Auth가 채운다.
 * 대응 명령은 raw userId를 싣지 않고 가명만 싣는다 — 이 매핑 권한은 집행 측(Auth)에만 있다.
 * subject_key = HMAC(zetty.subject, userId)로, 이벤트 actor.subject_key와 같은 값이다.
 */
@Entity
@Table(name = "actor_identity_map")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActorIdentity {

    @Id
    @Column(name = "subject_key", length = 128)
    private String subjectKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public static ActorIdentity of(String subjectKey, Long userId) {
        ActorIdentity a = new ActorIdentity();
        a.subjectKey = subjectKey;
        a.userId = userId;
        return a;
    }
}
