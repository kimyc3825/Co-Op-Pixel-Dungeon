# Noxac Pixel Dungeon

Shattered Pixel Dungeon v4.0.2 기반의 Windows·브라우저 협동 플레이 프로토타입입니다. 여관에서 다른 플레이어를 만나고, 두 사람이 같은 하수도에서 이동과 평타, 식량, 전리품을 함께 테스트할 수 있습니다.

## 브라우저로 테스트

[웹 여관에서 플레이](https://noxac-pixel-dungeon-inn.kimyc3825.chatgpt.site/play/)

닉네임을 입력하고 **입장**을 누릅니다. 브라우저와 Windows 0.7.0으로 같은 여관에 들어갈 수 있습니다. 두 클라이언트 모두 파티장과 참가자 역할을 맡을 수 있습니다. 웹에서 혼자 연습하려면 **길잡이와 입장**을 누릅니다.

여관에서 **하수도**와 **버려진 감옥**을 고를 수 있습니다. 감옥은 원정마다 방과 복도를 생성하고, 두 사람이 같은 지도를 봅니다.

## Windows 테스트

1. [0.7.0 테스트 릴리스](https://github.com/kimyc3825/noxac-pixel-dungeon/releases/tag/inn-v0.7.0)에서 `NoxacPixelDungeon-Inn-0.7.0-win-x64.zip`을 받습니다.
2. ZIP 전체를 푼 뒤 `NoxacPixelDungeon/NoxacPixelDungeon.exe`를 실행합니다. Windows x64용이며 Java 런타임을 포함합니다.
3. 두 사람 모두 0.7.0으로 여관에 접속해 **동료 구함**을 누릅니다. 혼자 연습하려면 **테스트 모드 → 길잡이와 입장**을 선택합니다.

이동은 WASD, 방향키 또는 숫자패드입니다. 두 방향키를 조합해 대각선으로 이동합니다. **Space**는 평타, **E**는 식량, **F1**은 조작 도움말입니다.

여관은 공용 Sites 서버를 사용합니다. 파티장의 PC에서 원정을 진행하며, 인게임 연결 방식은 하수도 화면 상단에 표시됩니다. 파티장이 나가면 원정이 끝납니다.

재현한 버그가 있다면 사용한 버전, 연결 표시, 입력 순서를 함께 남겨 주세요. [테스트 피드백](https://github.com/kimyc3825/noxac-pixel-dungeon/issues)

## 빌드와 소스

- [Windows 빌드, 행동력 규칙, 멀티플레이 프로토콜](docs/inn.md)
- [Sites Worker 로컬 개발 및 배포](inn-server/README.md)
- [원본 Android 빌드](docs/getting-started-android.md), [원본 데스크톱 빌드](docs/getting-started-desktop.md), [원본 iOS 빌드](docs/getting-started-ios.md)

## 원본과 라이선스

[Evan Debenham의 Shattered Pixel Dungeon v4.0.2](https://github.com/00-Evan/shattered-pixel-dungeon/tree/v4.0.2)에서 분기했습니다. Shattered Pixel Dungeon은 [Oleg Dolya의 Pixel Dungeon](https://github.com/watabou/pixel-dungeon)을 바탕으로 합니다.

[GPLv3](LICENSE.txt)와 [서드파티 라이선스 고지](inn-client/THIRD_PARTY_NOTICES.md)가 적용됩니다. Windows 배포본에 대응하는 소스 ZIP은 같은 릴리스에 있습니다.

- [원본 업데이트 검토](docs/upstream-update.md)
