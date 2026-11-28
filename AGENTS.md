# AGENTS.md

AI 코딩 에이전트(Codex, Claude Code 등)가 이 저장소에서 작업할 때 지키는 규칙입니다. 사람이 읽는 전체 설명은 [docs/development.md](docs/development.md)에 있고, 이 파일은 그중 반드시 지켜야 할 것만 모았습니다. 둘이 어긋나면 이 파일을 따르고, 어긋남을 고치는 풀 리퀘스트를 냅니다.

## 프로젝트

Shattered Pixel Dungeon 4.0.2에서 분기한 웹·Windows 2인 협동 던전입니다. 원본 엔진(`core/`)을 Windows 클라이언트(`inn-client/`)와 브라우저 런타임(`html/`, TeaVM)이 함께 쓰고, 여관·매칭·파티 중계는 Cloudflare Worker(`server/`)가 맡습니다. 웹 여관은 `web-client/play/`에 있습니다. 게임 규칙은 [docs/inn.md](docs/inn.md), 서버 메시지 형식은 [server/PROTOCOL.md](server/PROTOCOL.md)를 봅니다.

## 반드시 지킬 것

1. **`main`에 직접 푸시하지 않습니다.** `main`에 들어간 커밋은 곧바로 운영 서버(`https://co-op-pixel-dungeon.sidecat.workers.dev`)에 배포됩니다. 최신 `main`에서 `<에이전트 이름>/<주제>` 브랜치(예: `codex/guest-skills`)를 만들고 풀 리퀘스트를 엽니다. 사람이 명시적으로 `main`에 푸시하라고 한 경우만 예외입니다.
2. **시작 전에 열린 풀 리퀘스트와 이슈를 확인합니다.** 다른 작업자가 같은 파일을 고치고 있으면 범위를 나누거나 기다립니다. 다른 작업자의 브랜치에 푸시하거나 강제 푸시하지 않습니다.
3. **한 풀 리퀘스트에는 한 가지 목적만 담습니다.** 관련 없는 정리, 포맷 변경, 원본 코드 리팩터링을 섞지 않습니다.
4. **원본 코드는 최소한으로 고칩니다.** `core/`의 원본 파일은 원본 업데이트 병합 때 충돌하지 않도록 필요한 줄만 바꾸고, 새 기능은 `Inn`으로 시작하는 별도 클래스에 둡니다. `android/`, `ios/`, `services/`는 고치지 않습니다.
5. **비밀 값을 다루지 않습니다.** API 토큰, 계정 ID, `.dev.vars`를 커밋하거나 코드·이슈·풀 리퀘스트에 쓰지 않습니다. 배포 비밀 값은 GitHub Secrets에만 있습니다. 배포 설정(`.github/workflows/`, `server/wrangler.jsonc`)을 바꾸려면 먼저 사람에게 묻습니다.
6. **검증하지 않은 것을 검증했다고 쓰지 않습니다.** 풀 리퀘스트에 실행한 명령과 결과, 직접 확인하지 못한 것을 구분해서 적습니다.

## 검증 명령

저장소 루트에서 실행합니다. JDK 21이 필요합니다. Gradle은 항상 `-p inn-client`를 붙입니다(루트 빌드는 Android 설정까지 읽습니다). 예외는 브라우저 런타임 빌드입니다.

| 바꾼 곳 | 명령 |
| --- | --- |
| `core/` | `./gradlew -p inn-client :core:test :classes` 그리고 `./gradlew -PwebOnly html:dist` |
| `inn-client/` | `./gradlew -p inn-client :classes` |
| `server/` | `cd server && npm ci && npm run typecheck && npm test` |
| `web-client/play/` | `node scripts/stage-web-client.mjs --lobby-only` (실패 없이 끝나야 함) |

`core/`는 TeaVM으로도 변환되므로 리플렉션이나 TeaVM이 지원하지 않는 JDK API를 쓰지 않습니다. `html:dist` 성공이 그 확인입니다. 화면을 바꿨다면 [docs/development.md의 로컬 실행](docs/development.md#로컬에서-실행하기)대로 로컬 서버를 띄워 확인합니다.

## 함께 바꿔야 하는 곳

- 파티 패킷(`core/.../InnOriginalParty.java`의 명령·스냅샷 형식)을 바꾸면 `inn-client/.../InnApi.java`의 `DUNGEON_PROTOCOL`과 `web-client/play/session.mjs`의 `ORIGINAL_PROTOCOL`을 같은 값으로 1 올리고, `docs/inn.md`의 프로토콜 번호와 `core/src/test/.../InnPartyProtocolTest.java`를 고칩니다.
- 서버 메시지(`server/src/protocol.ts`)를 바꾸면 `server/PROTOCOL.md`와 두 클라이언트를 함께 고칩니다.
- 여관 화면·조작은 웹(`web-client/play/`)과 Windows(`inn-client/.../NativeInnScene.java`, `NativeInnBoard.java`)에 따로 구현되어 있습니다. 한쪽만 바꿨다면 풀 리퀘스트에 남은 작업으로 적습니다.
- 여관 배치는 `web-client/play/assets/inn-layout.json`, `web-client/scripts/generate-inn.mjs`의 `MAP`, `NativeInnBoard.java`의 `HALL_MAP`·`HALL_FX`를 함께 맞춥니다.
- 게임 동작·조작이 바뀌면 같은 풀 리퀘스트에서 `docs/inn.md`를 고칩니다.

## 커밋과 풀 리퀘스트

- 커밋 메시지는 영어 명령문 한 줄(70자 안쪽), 필요하면 빈 줄 뒤에 이유. 예: `Stop dungeon music when returning to the inn`.
- 문서와 게임 안 문구는 한국어로 씁니다.
- 풀 리퀘스트와 이슈는 제목, 본문, 댓글 모두 반드시 한국어로 씁니다. 코드, 명령, 파일 이름, 오류 메시지 같은 원문만 그대로 둡니다.
- 풀 리퀘스트 본문은 템플릿(`docs/PULL_REQUEST_TEMPLATE.md`)을 채웁니다. 합칠 때는 Squash and merge를 씁니다.
- 작업 도구가 남기는 체크포인트 커밋("WIP: checkpoint…")은 그대로 두어도 되지만, 합칠 때 하나로 합쳐지도록 Squash and merge로만 합칩니다.
