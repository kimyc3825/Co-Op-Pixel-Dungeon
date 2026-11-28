# 개발과 협업 가이드

이 저장소는 사람과 AI 코딩 에이전트가 함께 개발합니다. 이 문서는 누가 작업하든 같은 절차를 따르도록 정리한 기준입니다. AI 에이전트가 반드시 지킬 규칙은 저장소 루트의 [AGENTS.md](../AGENTS.md)에 요약되어 있습니다.

게임 규칙, 빌드 세부 사항과 조작법은 [inn.md](inn.md), 서버와 클라이언트 사이의 메시지 형식은 [server/PROTOCOL.md](../server/PROTOCOL.md)에 있습니다.

## 저장소 구조

| 경로 | 내용 |
| --- | --- |
| `core/` | 원본 게임 엔진과 이 포크의 던전 기능(턴 시계, 파티, 동료). Windows와 웹이 함께 씁니다. |
| `SPD-classes/` | 원본 엔진의 그래픽·입력·오디오 라이브러리 |
| `desktop/` | 원본 데스크톱 실행기. Windows 클라이언트가 이 위에서 실행됩니다. |
| `inn-client/` | Windows 클라이언트: 여관 화면(`NativeInnScene`, `NativeInnBoard`)과 서버 연결 |
| `html/` | 원본 엔진을 TeaVM으로 변환한 브라우저 던전 런타임 |
| `web-client/play/` | 웹 여관(HTML·JS)과 두 클라이언트가 함께 쓰는 여관 그림·배치 |
| `server/` | Cloudflare Worker: 여관 방, 매칭, 파티 중계 |
| `scripts/` | 웹 배포 준비(`stage-web-client.mjs`), 원본 업데이트 점검 |
| `android/`, `ios/`, `services/` | 원본 프로젝트에서 물려받은 모듈. 이 포크에서는 손대지 않습니다. |

`core`의 원본 파일은 원본 업데이트를 병합할 때 충돌이 생기지 않도록 꼭 필요한 만큼만 고칩니다. 새 기능은 가능하면 `Inn`으로 시작하는 별도 클래스에 둡니다.

## 개발 환경

- JDK 21 (`JAVA_HOME` 지정). Gradle은 저장소의 래퍼(`gradlew`, `gradlew.bat`)를 씁니다.
- Node.js 24 (서버와 웹 배포 스크립트)
- Python 3과 Pillow (외관·여관 그림을 다시 만들 때만)

Gradle 명령은 저장소 루트에서 `-p inn-client`를 붙여 실행합니다. 루트 빌드는 Android·iOS 모듈까지 설정하므로 느리고 Android SDK가 필요합니다. 브라우저 런타임만 `-PwebOnly`로 루트에서 빌드합니다.

## 브랜치와 풀 리퀘스트

**`main`은 곧 운영 서버입니다.** `main`에 푸시할 때마다 GitHub Actions가 테스트, 빌드 후 Cloudflare에 배포합니다([inn.md의 웹 배포](inn.md#웹-배포)). 그래서 모든 작업은 브랜치에서 하고 풀 리퀘스트로 합칩니다.

1. 작업을 시작하기 전에 열린 풀 리퀘스트와 이슈를 확인해 같은 파일을 고치는 작업이 없는지 봅니다. 겹치면 먼저 끝나는 쪽을 기다리거나 범위를 나눕니다.
2. 최신 `main`에서 브랜치를 만듭니다. 이름은 `<작성자>/<주제>` 형식입니다. 예: `kimyc/inn-chat-scroll`, `codex/guest-skills`, `claude/fix-party-desync`.
3. 한 브랜치에는 한 가지 목적만 담습니다. 리팩터링과 기능 추가를 섞지 않습니다.
4. 작업 중 커밋은 자유롭게 하되, 풀 리퀘스트를 열기 전에 최신 `main`을 병합하거나 리베이스하고 아래 검증을 모두 통과시킵니다.
5. 풀 리퀘스트 설명에 무엇을 왜 바꿨는지, 어떻게 확인했는지, 확인하지 못한 것은 무엇인지 적습니다. 템플릿이 자동으로 채워집니다. 풀 리퀘스트와 이슈는 제목, 본문, 댓글 모두 반드시 한국어로 씁니다. 코드, 명령, 파일 이름, 오류 메시지 같은 원문만 그대로 둡니다.
6. 다른 작업자(사람 또는 다른 에이전트)가 검토한 뒤 **Squash and merge**로 합칩니다. `main`에는 기능 하나당 커밋 하나가 남습니다. 합친 브랜치는 지웁니다.
7. `main`에 직접 푸시하는 것은 저장소 소유자가 긴급 수정을 할 때뿐입니다. 강제 푸시(`--force`)는 어떤 브랜치든 남의 브랜치에 하지 않습니다.

저장소 소유자에게는 GitHub의 Settings → Branches에서 `main`에 "Require a pull request before merging"을 켜 두기를 권합니다. 현재는 풀 리퀘스트에서 자동으로 도는 테스트가 없으므로, 아래 검증은 작성자가 직접 실행하고 결과를 풀 리퀘스트에 적습니다.

### 커밋 메시지

반드시 한국어로 짧게 씁니다. 첫 줄은 무엇을 바꿨는지(70자 안쪽), 필요하면 빈 줄 뒤에 이유를 씁니다. 예: `여관으로 돌아오면 던전 음악 끄기`. Squash and merge 때 커밋 제목도 한국어로 둡니다. 이전 이력에는 영어 커밋이 남아 있지만 따라 하지 않습니다. 문서와 게임 안 문구도 한국어로 씁니다.

## 로컬에서 실행하기

푸시하지 않은 변경은 로컬 서버로 확인합니다. 운영 서버(`https://co-op-pixel-dungeon.sidecat.workers.dev`)는 `main`의 코드만 돌리므로, 프로토콜을 바꾼 클라이언트를 운영 서버에 붙이면 매칭되지 않습니다.

```powershell
cd server; npm ci; cd ..                       # 처음 한 번
.\gradlew.bat -PwebOnly html:dist              # 브라우저 던전 런타임 (약 4분, core를 바꿨을 때)
node scripts/stage-web-client.mjs              # server/public에 웹 여관과 런타임 배치
cd server; npx wrangler dev --port 8787        # 로컬 서버: http://127.0.0.1:8787/play/
```

웹 여관만 고칠 때는 `html:dist` 없이 `node scripts/stage-web-client.mjs --lobby-only`로 충분합니다. 이때는 던전에 들어갈 수 없습니다.

Windows 클라이언트는 다른 터미널에서 로컬 서버를 지정해 실행합니다.

```powershell
$env:NOXAC_INN_URL = 'http://127.0.0.1:8787'
.\gradlew.bat -p inn-client :run
```

`run`이 아니라 `:run`을 씁니다. 콜론 없이 실행하면 게임 창을 닫은 뒤 원본 데스크톱 실행기까지 이어서 실행하다 실패합니다.

### 파티를 혼자 시험하기

브라우저 탭 두 개를 `http://localhost:8787/play/`와 `http://127.0.0.1:8787/play/`로 엽니다. 주소가 다르면 저장 공간이 따로라서 서로 다른 플레이어가 됩니다. 한쪽이 원정 → "동료 찾기"를 누르고, 다른 쪽이 그 캐릭터 위의 팻말을 누르면 파티가 시작됩니다. 브라우저는 뒤에 가려진 탭의 게임을 멈추므로, 파티장 탭이 앞에 있어야 동료의 명령이 처리됩니다. 웹과 Windows 클라이언트끼리도 같은 로컬 서버에서 매칭됩니다.

## 풀 리퀘스트 전 검증

바꾼 영역에 맞춰 실행하고, 결과를 풀 리퀘스트에 적습니다.

| 바꾼 곳 | 실행할 것 |
| --- | --- |
| `core/` | `.\gradlew.bat -p inn-client :core:test :classes` 와 `.\gradlew.bat -PwebOnly html:dist` |
| `inn-client/` | `.\gradlew.bat -p inn-client :classes` 후 로컬 서버에 붙여 실행 |
| `server/` | `cd server; npm run typecheck; npm test` |
| `web-client/play/` | `node scripts/stage-web-client.mjs --lobby-only` 후 로컬 서버에서 브라우저로 확인 |
| 파티·던전 동작 | 위 "파티를 혼자 시험하기"로 호스트와 게스트 양쪽을 확인 |

`core`는 Windows(일반 JVM)와 웹(TeaVM) 양쪽에서 돌아갑니다. TeaVM은 리플렉션과 일부 JDK API를 지원하지 않으므로, `core`를 고쳤다면 `html:dist`가 성공하는지 꼭 확인합니다. 테스트가 통과해도 화면은 직접 띄워 봐야 압니다.

## 함께 바꿔야 하는 곳

한쪽만 바꾸면 다른 클라이언트나 서버와 어긋나는 지점입니다.

- **파티 패킷 형식**(`core/.../InnOriginalParty.java`의 명령과 스냅샷 필드): 바뀌면 던전 프로토콜 번호를 올립니다. `inn-client/.../InnApi.java`의 `DUNGEON_PROTOCOL`과 `web-client/play/session.mjs`의 `ORIGINAL_PROTOCOL`을 같은 값으로 올리고, [inn.md의 네트워크와 전투](inn.md#네트워크와-전투)에 적힌 번호도 고칩니다. 번호가 다른 클라이언트끼리는 매칭되지 않으므로, 배포 후 새 Windows 배포본도 내야 합니다.
- **서버 메시지 형식**(`server/src/protocol.ts`): [server/PROTOCOL.md](../server/PROTOCOL.md)와 두 클라이언트(`web-client/play/session.mjs`, `inn-client/.../LobbySession.java`, `DungeonSession.java`)를 함께 고칩니다.
- **여관 화면과 조작**: 웹(`web-client/play/`)과 Windows(`inn-client/.../NativeInnScene.java`, `NativeInnBoard.java`)가 따로 구현되어 있습니다. 한쪽에 넣은 기능은 다른 쪽에도 넣거나, 풀 리퀘스트에 남은 작업으로 적습니다.
- **여관 배치**: `web-client/play/assets/inn-layout.json`, `web-client/scripts/generate-inn.mjs`의 `MAP`, `NativeInnBoard.java`의 `HALL_MAP`·`HALL_FX`를 같은 값으로 맞춥니다([여관 배경 작업](inn.md#여관-배경-작업)).
- **외관 카탈로그**: `web-client/scripts/generate-avatars.py`로 다시 만들고, 저장된 프로필이 깨지지 않도록 기존 옵션 ID를 유지합니다.
- **게임 동작이나 조작이 바뀌면** [inn.md](inn.md)를 같은 풀 리퀘스트에서 고칩니다.

## 배포와 릴리스

- **웹과 서버**: 풀 리퀘스트가 `main`에 합쳐지면 자동으로 배포됩니다. [Actions 기록](https://github.com/kimyc3825/Co-Op-Pixel-Dungeon/actions)에서 "Deploy to Cloudflare"가 성공했는지 확인합니다. 배포 도중 `main`에 새 커밋이 들어오면 앞선 실행은 배포를 건너뛰고 최신 실행만 배포합니다.
- **새 버전이 배포된 뒤** 이미 열려 있던 브라우저 탭은 새로고침해야 새 화면과 그림을 받습니다.
- **배포 버전**: 버전은 저장소 루트의 `VERSION` 파일 하나에만 적습니다(예: `0.17.0`). 웹 여관은 이 값과 커밋을 화면 왼쪽 아래에 보여 주고 `/play/version.json`에 적으며, Windows 클라이언트도 같은 값을 빌드 버전으로 씁니다. `main`에 합쳐지는 풀 리퀘스트는 모두 곧바로 배포되므로, 풀 리퀘스트마다 `VERSION`을 올리고 [changelog.md](changelog.md) 맨 위에 그 버전과 바뀐 내용을 적습니다. 화면만 고친 작은 수정은 마지막 자리, 기능 추가나 프로토콜 변경은 가운데 자리를 올립니다. 다른 풀 리퀘스트가 먼저 합쳐져 번호가 겹치면 합치기 전에 다음 번호로 다시 올립니다.
- **Windows 배포본**: `.\gradlew.bat -p inn-client packageWindows`로 만들고 GitHub Releases에 올립니다. 프로토콜이 바뀐 배포에는 반드시 새 배포본을 냅니다.

## 비밀 값

- Cloudflare API 토큰과 계정 ID는 GitHub 저장소의 Settings → Secrets and variables → Actions(`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`)에만 둡니다. 코드, 문서, 이슈, 풀 리퀘스트, 채팅에 붙여 넣지 않습니다. 노출되었다면 Cloudflare에서 폐기하고 새로 발급합니다.
- `server/.dev.vars`, `local.properties` 같은 로컬 설정 파일은 커밋하지 않습니다(이미 `.gitignore`에 있습니다).

## 원본 업데이트

Shattered Pixel Dungeon 새 버전을 들여오는 절차는 [upstream-update.md](upstream-update.md)에 있습니다. 원본 병합은 다른 작업과 섞지 않고 별도 브랜치와 풀 리퀘스트로 합니다.
