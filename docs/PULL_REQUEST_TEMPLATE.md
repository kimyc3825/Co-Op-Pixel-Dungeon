<!-- 제목과 본문은 한국어로 씁니다. -->

## 무엇을 왜

<!-- 바꾼 내용과 이유. 관련 이슈가 있으면 "Closes #번호". -->

## 확인한 것

<!-- 실행한 명령과 결과, 직접 띄워 본 화면. 해당 없는 줄은 지웁니다. -->
- [ ] `./gradlew -p inn-client :core:test :classes`
- [ ] `./gradlew -PwebOnly html:dist` (`core/`를 바꾼 경우)
- [ ] `cd server && npm run typecheck && npm test` (`server/`를 바꾼 경우)
- [ ] 로컬 서버에서 웹 / Windows 화면 확인

## 확인하지 못한 것

<!-- 테스트하지 못한 경로, 한쪽 클라이언트에만 넣은 기능 등. 없으면 "없음". -->

## 함께 바꿨는지

- [ ] 파티 패킷을 바꿨다면 `DUNGEON_PROTOCOL`과 `ORIGINAL_PROTOCOL`을 같은 값으로 올림
- [ ] 서버 메시지를 바꿨다면 `server/PROTOCOL.md`를 고침
- [ ] 게임 동작이나 조작이 바뀌었다면 `docs/inn.md`를 고침
