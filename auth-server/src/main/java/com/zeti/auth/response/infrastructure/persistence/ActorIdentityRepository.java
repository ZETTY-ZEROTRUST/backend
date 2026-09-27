package com.zeti.auth.response.infrastructure.persistence;

import com.zeti.auth.response.domain.ActorIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 가명(subject_key) 역매핑 저장소. 로그인 upsert(save=merge)와 집행 시 조회(findById)에 쓴다. */
public interface ActorIdentityRepository extends JpaRepository<ActorIdentity, String> {
}
