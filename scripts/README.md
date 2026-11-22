# Backend scripts

## 현행 계약 동기화

`sync-contracts.sh`는 [sync_contracts.py](sync_contracts.py)의 진입점이다.

```sh
scripts/sync-contracts.sh
scripts/sync-contracts.sh /path/to/contracts
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_sync_contracts.py' -v
```

1. MANIFEST와 선택 schema/fixture 경로·JSON·SHA256을 먼저 확인한다.
2. Auth/API 테스트 리소스 양쪽에 복사본을 준비한다.
3. 준비 후 교체하고, 처리 중 예외가 발생하면 이전 복사본으로 되돌린다.

원본 누락·해시 불일치·symlink 이탈은 교체 전에 거부한다.
revision 변경 시 각 앱 ContractRevisionTest pin과 diff를 함께 확인한다.
이번 정리에서는 현재 원본 62개 파일의 해시 검증만 실행했으며 실제 앱 복사본 동기화는 필요하지 않아 수행하지 않았다.

SIGKILL·디스크 오류 등으로 복원이 완료되지 않으면 `.contracts-stage-*/previous`에 남은 backup을 보존한다.
실패 후 이 디렉토리가 있으면 원본 비교와 수동 복구를 먼저 수행한다.
여러 프로세스가 같은 앱의 contracts를 동시에 동기화하는 사용은 지원하지 않는다.

후속 공격·정상 대조군은 [현재 시나리오 정의](../../attack-simulation/README.md)를 따른다. 이전 실행기는 삭제되었으므로 이 문서를 실행 가능한 v2 검증 명령으로 해석하지 않는다.
