# Noxac Pixel Dungeon 여관

## Windows에서 플레이

여관 0.7.0은 EXE를 실행하면 게스트 이름으로 공용 여관에 들어간다. 오른쪽 위 이름을 눌러 닉네임을 바꿀 수 있다. 여관, 파티 던전, 1인 던전이 같은 게임 창에서 전환된다.

- **동료 구함**을 선택하면 나무 팻말이 캐릭터 위에 나타난다. 0.6 계열 클라이언트끼리 자동으로 매칭하며, 다른 사람의 팻말을 클릭해 그 사람과 파티를 맺을 수 있다.
- **테스트 모드**에서 **길잡이와 입장**을 누르면 같은 하수도 맵과 ATB 규칙으로 연습한다. 길잡이는 이동하고 가까운 적과 싸운다. 연습은 오프라인에서도 실행된다.
- **혼자 던전 입장**은 원본 1인 저장 슬롯을 연다. 저장 슬롯에서 뒤로 가거나 게임 메뉴에서 메인 메뉴를 선택하면 여관으로 돌아온다.
- **WASD**, 방향키 조합, 숫자패드로 8방향 이동한다. 여관과 던전은 첫 방향키 뒤 약 90ms 동안 두 번째 키를 대각선 조합으로 받을 수 있다. 숫자패드 대각선은 즉시 입력된다. 여관에서는 클릭한 칸까지 걷고, 던전에서는 인접 칸을 클릭해 한 칸 이동한다.
- 대각선 목적지가 비어 있고 옆 두 칸 중 한 칸 이상이 열려 있으면 이동할 수 있다. 두 벽이 맞닿은 틈은 막힌다.
- 던전에서 **Space** 또는 인접한 적 클릭으로 평타를 치고, **E**로 식량을 먹는다. 이동은 한 칸마다 0.18초 간격으로 이어지며, 평타 간격 0.8초 중에도 이동할 수 있다.
- 적이 떨어뜨린 아이템은 두 화면에 같이 표시된다. 먼저 아이템 칸에 들어간 사람이 줍는다. 소탕 후에도 움직여 남은 아이템을 주울 수 있다.
- 여관과 파티 던전에서 **F1**로 반투명 조작 도움말을 열고 닫는다. 여관의 **Enter**는 대화 입력이다.

## 브라우저에서 플레이

[웹 여관](https://noxac-pixel-dungeon-inn.kimyc3825.chatgpt.site/play/)에서 닉네임을 입력하고 **입장**을 누른다. 브라우저와 Windows 0.7.0은 같은 여관과 매칭을 사용한다. 어느 클라이언트든 파티장이나 참가자가 될 수 있다.

브라우저에서는 여관, 길잡이 훈련, 2인 원정을 플레이할 수 있다. **동료 찾기**를 누르거나 동료를 찾는 모험가의 팻말을 누르면 파티에 들어간다. 키보드 조작과 화면 버튼을 사용할 수 있다. 원정 중에는 파티장이 게임 창이나 브라우저 탭을 열어 둔다.

## 던전 테마

여관에서 **하수도** 또는 **버려진 감옥**을 고른다. 길잡이 훈련에는 내가 고른 테마를, 2인 원정에는 파티장이 고른 테마를 적용한다. 감옥 타일과 생성된 맵을 함께 보려면 웹을 새로고침하고 Windows 참가자는 0.7.0을 사용한다.

하수도는 익숙한 고정 맵으로 시작한다. 감옥은 원정 시드로 방 여섯 개의 크기와 배치를 정한 뒤 순환 복도와 가지 복도를 판다. 두 사람은 같은 시작 방에 등장하고 쥐 두 마리는 떨어진 방에 배치된다. 파티장이 완성된 맵과 테마를 두 사람에게 공유한다.

원본의 [RegularLevel](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java)과 [LoopBuilder](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/builders/LoopBuilder.java)를 참고해 방 준비, 연결, 타일 채우기 순서로 구성했다. Windows의 `DungeonLayout.java`와 웹의 `layout.mjs`에 생성 규칙이 있다. 테마를 추가할 때는 이 규칙의 방 배치와 스폰, 해당 타일 시트, 여관의 테마 선택을 연결한다.

### 행동력

| 행동 | 원본 싱글플레이 기본 코스트 | 파티 던전 행동력 |
| --- | --- | --- |
| 이동 | 이동속도 1에서 1턴 | 전투 중 1, 전투 밖 자유 이동 |
| 평타 | 표준 무기 1턴 | 1 |
| 일반 식량 | 3턴 | 3 |

행동력은 캐릭터마다 최대 10이다. 행동력을 쓴 뒤 0.5초가 지나면 회복이 시작되고, 0.8초에 1만큼 찬다. 식량은 각 플레이어가 하나 들고 시작하고, 주운 식량도 먹을 수 있다. 하수도 시험 맵의 식량은 HP를 최대 6 회복한다. 쥐 추격 간격은 0.38초, 공격 간격은 0.8초다.

원본의 이동은 `Hero`에서 `1 / speed()`, 표준 평타는 `Hero.attackDelay()`와 `Weapon.delayFactor()`, 일반 식량은 `Food.TIME_TO_EAT`를 기준으로 비교했다. 원본의 무기별 공격 속도와 식사 시간을 바꾸는 특성은 싱글플레이에 적용된다. 파티 및 연습 던전의 행동력과 전리품은 해당 하수도 원정 안에서 관리한다.

## 빌드

[원본 업데이트 검토 절차](upstream-update.md)

Windows x64에서 JDK 21을 지정한 뒤 저장소 루트에서 실행한다.

```powershell
.\gradlew.bat -p inn-client packageWindows
```

출력은 `inn-client/build/jpackage/0.7.0/NoxacPixelDungeon/NoxacPixelDungeon.exe`다. 실행 폴더에 Java와 게임 에셋이 포함된다.

기본 서버는 [여관 운영](https://noxac-pixel-dungeon-inn.kimyc3825.chatgpt.site)이다. 로컬 개발에서는 `NOXAC_INN_URL`을 `http://127.0.0.1:8787`로 설정한다.

## 네트워크와 전투

Sites Worker와 관리형 D1이 여관, 매칭, 세션 인증, WebRTC 연결 정보 교환을 맡는다. 0.6 및 0.7 계열 클라이언트는 여관 입장 시 `dungeonProtocol: 5`를 보낸다. 0.5 계열은 프로토콜 4, 0.4 계열은 기존 V3 던전 API를 사용한다.

파티장의 클라이언트 한 곳에서 이동, ATB, 적 행동, 피해와 전리품을 확정한다. Windows와 브라우저의 파티장이 같은 규칙과 프로토콜을 사용한다. 참여자는 입력마다 번호를 붙인다. 파티장은 처리한 번호를 기록하고, 두 사람에게 같은 좌표·체력·전리품 목록을 보낸다. 처치당 하나의 ID로 드롭을 기록하고 먼저 들어간 플레이어에게 획득을 귀속한다. 내 이동과 평타 준비 동작은 즉시 시작하며, 위치를 파티장의 판정에 맞춰 보정한다.

던전의 데이터는 WebRTC로 직접 보내고, 직접 연결 준비 중이거나 사용할 수 없으면 Sites가 메시지를 중계한다. 던전 상단에서 현재 연결 방식을 확인할 수 있다. 전투 계산은 중계 중에도 파티장 클라이언트에서 실행된다. 파티장 이탈 시 해당 원정은 종료된다. 다른 연결 환경을 로컬에서 재현할 때는 Java 옵션 `-Dnoxac.inn.relayOnly=true`로 중계를 강제할 수 있다.

```powershell
Set-Location inn-server
npm ci
npm test
npm run dev
```

여관은 `/api/v2/inn/session`, `/sync`, `/move`, `/chat`, `/drink`, `/match`를 사용한다. `/match` 요청의 `queued`는 매칭 신청 또는 취소이며, 신청할 때 `targetId`를 보내면 그 팻말의 상대를 지정한다.

두 참가자는 여관의 토큰과 `matchId`로 `POST /api/v4/link/enter`를 호출한다. 이후 `/api/v4/link/sync`, `/signal`, `/relay`, `/leave`에는 같은 링크 `id`를 보낸다. `/signal`은 `type`과 `sdp`를, `/relay`는 증가하는 `seq`와 최대 24 KiB의 `message`를 받는다. 중계는 송신자별로 최신 메시지 한 건을 유지한다. 인게임 입력은 `move`, `attack`, `eat`이며, 참가자는 마지막 확인 이후 입력을 순서대로 묶어 보낸다. 파티장은 처리한 입력 번호를 상태와 함께 돌려준다.

배포에는 `inn-server`의 Worker 소스와 D1 마이그레이션을 사용한다. `GET /api/v4/health`로 연결 API의 준비 상태를 확인할 수 있다.

## 원본과 라이선스

[Shattered Pixel Dungeon v4.0.2](https://github.com/00-Evan/shattered-pixel-dungeon/tree/v4.0.2)의 커밋 `57a4e06a4caf162446d1c28caa7983f0493fecf0`에서 분기했다. 원본 게임의 [GPLv3 라이선스](../LICENSE.txt)와 저작권을 보존한다. 실행 파일과 함께 대응 소스 및 [서드파티 고지](../inn-client/THIRD_PARTY_NOTICES.md)를 제공한다.
