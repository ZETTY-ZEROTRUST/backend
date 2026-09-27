package com.zeti.auth.identity.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column
    private String phone;

    @Column(name = "auth_version", nullable = false)
    private int authVersion;

    /** 대응 명령 LOCK_ACCOUNT로 세운다. 잠긴 계정은 로그인을 거부한다. */
    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public static User create(String email, String passwordHash,
                              String name, String phone) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.name = name;
        user.phone = phone;
        return user;
    }

    /** 전체 로그아웃·권한 회수 시 증가. 기존 AT는 다음 검증에서 거부된다. */
    public void bumpAuthVersion() {
        this.authVersion += 1;
    }

    /** LOCK_ACCOUNT 집행. 로그인 거부 상태로 만든다. */
    public void lock() {
        this.locked = true;
    }

    /** 잠금 해제(명시적 해제 명령·운영). */
    public void unlock() {
        this.locked = false;
    }
}
