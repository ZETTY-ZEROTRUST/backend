package com.zeti.auth.response.infrastructure.persistence;

import com.zeti.auth.response.domain.ResponseCommandLog;
import org.springframework.data.jpa.repository.JpaRepository;

/** 대응 명령 멱등·감사 로그 저장소. command_id(PK)로 재전송을 판별한다. */
public interface ResponseCommandLogRepository extends JpaRepository<ResponseCommandLog, String> {
}
